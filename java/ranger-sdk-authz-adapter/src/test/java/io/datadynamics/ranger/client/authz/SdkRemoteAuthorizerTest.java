package io.datadynamics.ranger.client.authz;

import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;
import org.apache.ranger.authz.api.RangerAuthzException;
import org.apache.ranger.authz.model.RangerAuthzRequest;
import org.apache.ranger.authz.model.RangerAuthzResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkRemoteAuthorizerTest {
    private static final String REQUEST = "{\"requestId\":\"r1\",\"user\":{\"name\":\"alice\"},"
            + "\"access\":{\"resource\":{\"name\":\"table:sales/orders\"},\"permissions\":[\"select\"]},"
            + "\"context\":{\"serviceType\":\"hive\",\"serviceName\":\"dev_hive\"}}";

    @Test
    void forwardsOfficialModelsToThePdp() throws Exception {
        Path token = Files.createTempFile("pdp", ".jwt");
        Files.writeString(token, "tok\n");
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "{\"requestId\":\"r1\",\"decision\":\"ALLOW\",\"permissions\":{\"select\":"
                    + "{\"permission\":\"select\",\"access\":{\"decision\":\"ALLOW\",\"policy\":{\"id\":3,\"version\":1}}}}}"));
            Properties p = new Properties();
            p.setProperty("ranger.sdk.pdp.urls", "http://127.0.0.1:9, " + s.url());
            p.setProperty("ranger.sdk.pdp.auth", "jwt");
            p.setProperty("ranger.sdk.pdp.auth.token.file", token.toString());
            SdkRemoteAuthorizer authz = new SdkRemoteAuthorizer(p);
            authz.init();
            RangerAuthzResult r = authz.authorize(RangerJson.read(REQUEST, RangerAuthzRequest.class));
            assertEquals("ALLOW", String.valueOf(r.getDecision()));
            assertEquals("Bearer tok", s.last().header("Authorization"));
            assertTrue(s.last().bodyText().contains("\"name\":\"table:sales/orders\""));
            authz.close();
        } finally {
            Files.delete(token);
        }
    }

    @Test
    void pdpErrorsBecomeAuthzExceptions() throws Exception {
        try (MockServer s = new MockServer()) {
            s.handler(r -> Reply.of(403, "{\"code\":403,\"message\":\"caller not allowed\"}"));
            Properties p = new Properties();
            p.setProperty("ranger.sdk.pdp.urls", s.url());
            SdkRemoteAuthorizer authz = new SdkRemoteAuthorizer(p);
            authz.init();
            RangerAuthzException e = assertThrows(RangerAuthzException.class,
                    () -> authz.authorize(RangerJson.read(REQUEST, RangerAuthzRequest.class)));
            assertTrue(e.getMessage().contains("caller not allowed"), e.getMessage());
        }
    }

    @Test
    void missingUrlsFailInit() {
        assertThrows(RangerAuthzException.class, () -> new SdkRemoteAuthorizer(new Properties()).init());
    }
}
