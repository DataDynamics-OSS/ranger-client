package io.datadynamics.ranger.client.contract;

import io.datadynamics.ranger.client.admin.PolicyFilter;
import io.datadynamics.ranger.client.admin.RangerAdminClient;
import io.datadynamics.ranger.client.core.AbstractClientBuilder;
import io.datadynamics.ranger.client.core.RangerException;
import io.datadynamics.ranger.client.core.RetryPolicy;
import io.datadynamics.ranger.client.kms.RangerKmsClient;
import io.datadynamics.ranger.client.model.AuthzResult;
import io.datadynamics.ranger.client.model.GrantRevokeRoleRequest;
import io.datadynamics.ranger.client.model.RangerJson;
import io.datadynamics.ranger.client.model.RangerModel;
import io.datadynamics.ranger.client.model.RangerPolicy;
import io.datadynamics.ranger.client.model.ResourceInfo;
import io.datadynamics.ranger.client.pdp.RangerPdpClient;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runs spec/contract/scenarios.yaml (shared with python/tests/test_contract.py) against the Java SDK. */
class HttpContractTest {
    private static final RetryPolicy RETRY = RetryPolicy.exponential(3, Duration.ZERO).withoutJitter();

    @TestFactory
    Stream<DynamicTest> scenarios() {
        return Spec.cases("scenarios.yaml", "scenarios").stream()
                .map(sc -> DynamicTest.dynamicTest((String) sc.get("name"), () -> run(sc)));
    }

    @SuppressWarnings("unchecked")
    private static void run(Map<String, Object> sc) throws Exception {
        try (MockServer server = new MockServer()) {
            for (Map<String, Object> r : (List<Map<String, Object>>) sc.get("responses")) {
                int status = ((Number) r.get("status")).intValue();
                server.enqueue(r.containsKey("body") ? Reply.of(status, Spec.json(r.get("body"))) : Reply.of(status));
            }
            String op = (String) sc.get("op");
            Map<String, Object> args = (Map<String, Object>) sc.get("args");
            String version = (String) sc.get("server_version");
            Consumer<AbstractClientBuilder<?, ?>> setup = b -> {
                b.retry(RETRY);
                if (version != null) {
                    b.serverVersion(version);
                }
            };
            if (sc.containsKey("expect_error")) {
                Class<? extends RangerException> type = (Class<? extends RangerException>)
                        Class.forName("io.datadynamics.ranger.client.core." + sc.get("expect_error"));
                assertThrows(type, () -> invoke(op, args, server.url(), setup));
            } else {
                Object result = invoke(op, args, server.url(), setup);
                if (sc.containsKey("expect_result")) {
                    Spec.subset(sc.get("expect_result"), resultMap(result), "$");
                }
                if (sc.containsKey("expect_result_size")) {
                    assertEquals(((Number) sc.get("expect_result_size")).intValue(), ((List<?>) result).size());
                }
            }
            if (sc.containsKey("expect_request_count")) {
                assertEquals(((Number) sc.get("expect_request_count")).intValue(), server.requests().size());
            }
            Map<String, Object> exp = (Map<String, Object>) sc.get("expect_request");
            if (exp != null) {
                MockServer.Recorded req = server.requests().get(0);
                assertEquals(exp.get("method"), req.method);
                assertEquals(exp.get("path"), req.rawPath);
                if (exp.containsKey("query")) {
                    Map<String, Object> q = new LinkedHashMap<>();
                    ((Map<String, Object>) exp.get("query")).forEach((k, v) -> q.put(k, String.valueOf(v)));
                    Spec.subset(q, req.query, "query");
                }
                if (exp.containsKey("body")) {
                    Spec.subset(exp.get("body"), Spec.JSON.readValue(req.body, Object.class), "body");
                }
                if (exp.containsKey("headers")) {
                    ((Map<String, Object>) exp.get("headers")).forEach((k, v) -> assertEquals(v, req.header(k), "header " + k));
                }
                if (Boolean.TRUE.equals(exp.get("request_id_matches_body"))) {
                    Object bodyId = Spec.JSON.readValue(req.body, Map.class).get("requestId");
                    assertNotNull(bodyId);
                    assertEquals(bodyId, req.header("X-Request-Id"));
                }
            }
        }
    }

    /** Run a contract op; {@code setup} configures whichever client builder the op needs. */
    @SuppressWarnings("unchecked")
    static Object invoke(String op, Map<String, Object> a, String url, Consumer<AbstractClientBuilder<?, ?>> setup) {
        if (op.startsWith("pdp.")) {
            RangerPdpClient.Builder pb = RangerPdpClient.builder().urls(url)
                    .service((String) a.get("service"), (String) a.get("service_type"));
            setup.accept(pb);
            RangerPdpClient pdp = pb.build();
            List<String> kv = new java.util.ArrayList<>();
            for (List<String> pair : (List<List<String>>) a.get("resource")) {
                kv.addAll(pair);
            }
            return pdp.authorize((String) a.get("user"), ResourceInfo.of(kv.toArray(new String[0])), (String) a.get("access"));
        }
        if (op.startsWith("kms.")) {
            RangerKmsClient.Builder kb = RangerKmsClient.builder().urls(url);
            setup.accept(kb);
            RangerKmsClient kms = kb.build();
            switch (op) {
                case "kms.metadata":
                    return kms.metadata((String) a.get("name"));
                case "kms.versions":
                    return kms.versions((String) a.get("name"));
                case "kms.delete_key":
                    kms.deleteKey((String) a.get("name"));
                    return null;
                default:
                    throw new IllegalArgumentException("no Java mapping for contract op " + op);
            }
        }
        RangerAdminClient.Builder ab = RangerAdminClient.builder().urls(url);
        setup.accept(ab);
        RangerAdminClient admin = ab.build();
        switch (op) {
            case "policies.get":
                return admin.policies().get(((Number) a.get("id")).longValue());
            case "policies.get_by_name":
                return admin.policies().getByName((String) a.get("service"), (String) a.get("name"));
            case "policies.create":
                return admin.policies().create(RangerJson.convert(a.get("policy"), RangerPolicy.class));
            case "policies.delete_by_name":
                admin.policies().deleteByName((String) a.get("service"), (String) a.get("name"));
                return null;
            case "policies.search":
                return admin.policies().search(PolicyFilter.service((String) a.get("service"))
                        .start(((Number) a.get("start_index")).intValue())
                        .limit(((Number) a.get("page_size")).intValue())).items();
            case "roles.get_by_name":
                return admin.roles().getByName((String) a.get("name"));
            case "users.get_by_name":
                return admin.users().getByName((String) a.get("name"));
            case "groups.get_by_name":
                return admin.groups().getByName((String) a.get("name"));
            case "zones.get_by_name":
                return admin.zones().getByName((String) a.get("name"));
            case "roles.delete_by_name":
                admin.roles().deleteByName((String) a.get("name"), null, null);
                return null;
            case "tags.get":
                return admin.tags().get(((Number) a.get("id")).longValue());
            case "tags.delete":
                admin.tags().delete(((Number) a.get("id")).longValue());
                return null;
            case "policies.export_json":
                return admin.policies().exportJson((List<String>) a.get("services"), null);
            case "roles.grant":
                return admin.roles().grant((String) a.get("service"),
                        RangerJson.convert(a.get("request"), GrantRevokeRoleRequest.class));
            default:
                throw new IllegalArgumentException("no Java mapping for contract op " + op);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultMap(Object result) {
        Map<String, Object> m = result instanceof RangerModel
                ? RangerJson.convert(result, Map.class) : (Map<String, Object>) result;
        if (result instanceof AuthzResult) {
            AuthzResult r = (AuthzResult) result;
            m.put("decision", r.getDecision() == null ? null : r.getDecision().name());
            m.put("decision_raw", r.getDecisionRaw());
        }
        return m;
    }
}
