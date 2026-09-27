package com.josephinealinea.planner.shared;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpCallLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("external-api");
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void capture() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void release() {
        logger.detachAppender(lines);
    }

    @Test
    void logsTheRequestAndTheResponseAtInfoAndLeavesTheBodyReadable() {
        var builder = RestClient.builder().baseUrl("http://example.test");
        HttpCallLog.on(builder, "Demo");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("[{\"n\":1}]", MediaType.APPLICATION_JSON));

        String body = builder.build().get().uri("/flights?access_key=SECRET123&x=1").retrieve().body(String.class);

        assertThat(body).isEqualTo("[{\"n\":1}]");
        assertThat(lines.list).extracting(ILoggingEvent::getLevel).allMatch(l -> l.toString().equals("INFO"));
        String all = lines.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
        assertThat(all).contains("Demo request: GET", "Demo response: 200", "[{\"n\":1}]");
        assertThat(all).doesNotContain("SECRET123").contains("access_key=***");
    }

    @Test
    void masksSecretQueryParametersAndNothingElse() {
        assertThat(HttpCallLog.redact("http://h/x?a=1&key=abc&apikey=def&b=2")).isEqualTo("http://h/x?a=1&key=***&apikey=***&b=2");
    }
}
