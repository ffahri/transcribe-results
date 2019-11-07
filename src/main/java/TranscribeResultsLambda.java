import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.event.S3EventNotification;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.ifountain.opsgenie.client.OpsGenieClient;
import com.ifountain.opsgenie.client.swagger.ApiException;
import com.ifountain.opsgenie.client.swagger.api.AlertApi;
import com.ifountain.opsgenie.client.swagger.model.CreateAlertRequest;
import com.ifountain.opsgenie.client.swagger.model.SuccessResponse;
import com.ifountain.opsgenie.client.swagger.model.TeamRecipient;
import model.TranscribeResults;
import model.Transcript;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


public class TranscribeResultsLambda implements RequestHandler<S3EventNotification, String> {
    final static Logger LOGGER = Logger.getLogger(TranscribeResultsLambda.class);

    public String handleRequest(S3EventNotification s3EventNotification, Context context) {

        String objectName = s3EventNotification.getRecords().get(0).getS3().getObject().getKey();
        String bucketName = s3EventNotification.getRecords().get(0).getS3().getBucket().getName();
        AmazonS3 amazonS3 = AmazonS3Client.builder().build();
        try {
            TranscribeResults transcribeResults = unmarshallJson(amazonS3.getObject(new GetObjectRequest(bucketName, objectName)).getObjectContent());
            LOGGER.info("OBJECT NAME : " + objectName);
            if (transcribeResults != null) {
                LOGGER.info(transcribeResults.getResults().getTranscripts().get(0).getTranscript());
                createAlert(transcribeResults);

            }

        } catch (IOException e) {
            e.printStackTrace();
        } catch (ApiException e) {
            e.printStackTrace();
            LOGGER.error("oh no");
        }

        return "OK";
    }

    private TranscribeResults unmarshallJson(InputStream input) throws IOException {
        // Read the text input stream one line at a time and display each line.
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder json = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            json.append(line);
        }
        TranscribeResults transcribeResult = null;
        LOGGER.info("JSON:\n" + json.toString());
        try {
            transcribeResult = mapper.readValue(json.toString(), TranscribeResults.class);
        } catch (MismatchedInputException e) {
            LOGGER.error("Transcribe probably returns empty array.");
        }
        return transcribeResult;
    }

    private void createAlert(TranscribeResults transcriptResults) throws ApiException {
        AlertApi client = new OpsGenieClient().alertV2();
        client.getApiClient().setApiKey(System.getenv("GENIE_KEY"));

        CreateAlertRequest request = new CreateAlertRequest();
        request.setMessage("[Radio Alert] - " + getFreq(transcriptResults.getJobName()) + "Mhz" + "- " + getTime(transcriptResults.getJobName()));

        StringBuilder description = new StringBuilder();
        for (Transcript transcript : transcriptResults.getResults().getTranscripts()) {
            description.append("Transcript : ").append(transcript.getTranscript()).append("\n");
        }
        request.setDescription(description.toString());
        request.setTeams(Collections.singletonList(new TeamRecipient().name(getTeamBasedOnTranscript(transcriptResults.getResults().getTranscripts()))));
        request.setTags(Arrays.asList("radio", "rf-signals"));
        request.setPriority(CreateAlertRequest.PriorityEnum.P3);
        request.setNote("Alert created");

        SuccessResponse response = client.createAlert(request);
        Float took = response.getTook();
        String requestId = response.getRequestId();
        String message = response.getResult();
        LOGGER.info("Response: " + response);
        LOGGER.info("Req ID : " + requestId + "Took:" + took);

    }

    private String getTime(String fileName) {
        String arr[] = fileName.split("_");
        String epochTime = arr[1].split(".")[0];

        String date = new java.text.SimpleDateFormat("MM/dd/yyyy HH:mm:ss").format(new java.util.Date(Long.valueOf(epochTime)));
        return date;
    }

    private String getFreq(String fileName) {
        String arr[] = fileName.split("_");

        return arr[0];
    }

    private String getTeamBasedOnTranscript(List<Transcript> transcripts) {
        for (Transcript ts : transcripts) {
            if (ts.getTranscript().contains("blue") || (ts.getTranscript().contains("Blue"))) {
                return "BlueTeam";
            }
            if (ts.getTranscript().contains("red") || (ts.getTranscript().contains("Red"))) {
                return "RedTeam";
            }
        }
        return "GreenTeam";
    }
}


