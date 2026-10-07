package ee.yvonne.bank.external;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration 
public class ExternalClientConfig {

   @Bean
    RestClient externalRestClient(RestClient.Builder builder,
            @Value("${external.logging.connect-timeout}") Duration connectTimeout,
            @Value("${external.logging.read-timeout}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return builder.requestFactory(factory).build();
    }
}
