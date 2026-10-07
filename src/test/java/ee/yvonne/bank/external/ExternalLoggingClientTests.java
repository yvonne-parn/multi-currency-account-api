package ee.yvonne.bank.external;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import ee.yvonne.bank.common.ExternalSystemException;

class ExternalLoggingClientTests {
    private static final String URL = "http://logging.test/log";

    private MockRestServiceServer server;
    private ExternalLoggingClient service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new ExternalLoggingClient(builder.build(), URL);
    }

    @Test
    void successfulCallDoesNotThrow() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess());

        assertThatCode(service::logDebitAttempt).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void serverErrorThrowsExternalSystemException() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(service::logDebitAttempt).isInstanceOf(ExternalSystemException.class);
    }

    @Test
    void badRequestThrowsExternalSystemException() {
        server.expect(requestTo(URL)).andRespond(withBadRequest());

        assertThatThrownBy(service::logDebitAttempt).isInstanceOf(ExternalSystemException.class);
    }

    @Test
    void connectionFailureThrowsExternalSystemException() {
        server.expect(requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("timed out")));

        assertThatThrownBy(service::logDebitAttempt).isInstanceOf(ExternalSystemException.class);
    }
}
