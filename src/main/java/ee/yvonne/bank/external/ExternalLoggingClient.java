package ee.yvonne.bank.external;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ee.yvonne.bank.common.ExternalSystemException;

@Component
public class ExternalLoggingClient {

    private final RestClient restClient;
    private final String loggingUrl;

   
    public ExternalLoggingClient(RestClient restClient, @Value("${external.logging.url}") String loggingUrl) {
        this.restClient = restClient;
        this.loggingUrl = loggingUrl;
    }

    public void logDebitAttempt() {
        try {
            restClient.get()
                    .uri(loggingUrl)
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientException e) {
            throw new ExternalSystemException("External logging call failed", e);
        }
    }
}
