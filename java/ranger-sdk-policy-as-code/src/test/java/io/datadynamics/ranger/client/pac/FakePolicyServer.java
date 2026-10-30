package io.datadynamics.ranger.client.pac;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Stateful in-memory policy and role endpoints, enough for plan/apply/export round trips. */
final class FakePolicyServer implements AutoCloseable {
    private static final String API = "/service/public/v2/api";
    private static final ObjectMapper JSON = new ObjectMapper();

    final MockServer server = new MockServer();
    final Map<Long, Map<String, Object>> policies = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> roles = new LinkedHashMap<>();
    private long nextId = 1;

    FakePolicyServer() {
        server.handler(this::handle);
    }

    String url() {
        return server.url();
    }

    @SuppressWarnings("unchecked")
    private synchronized Reply handle(MockServer.Recorded r) {
        try {
            String path = r.rawPath;
            if (path.equals(API + "/policy") && r.method.equals("GET")) {
                int start = Integer.parseInt(r.query.getOrDefault("startIndex", "0"));
                List<Map<String, Object>> hits = policies.values().stream()
                        .filter(p -> p.get("service").equals(r.query.get("serviceName")))
                        .skip(start).collect(Collectors.toList());
                return ok(hits);
            }
            if (path.equals(API + "/policy") && r.method.equals("POST")) {
                Map<String, Object> p = JSON.readValue(r.body, Map.class);
                long id = nextId++;
                p.put("id", id);
                p.put("version", 1);
                policies.put(id, p);
                return ok(p);
            }
            if (path.startsWith(API + "/policy/")) {
                long id = Long.parseLong(path.substring((API + "/policy/").length()));
                Map<String, Object> cur = policies.get(id);
                if (cur == null) {
                    return Reply.of(404, "{\"msgDesc\":\"no policy\"}");
                }
                switch (r.method) {
                    case "GET":
                        return ok(cur);
                    case "DELETE":
                        policies.remove(id);
                        return Reply.of(204);
                    default:
                        Map<String, Object> p = JSON.readValue(r.body, Map.class);
                        p.put("version", ((Number) cur.get("version")).intValue() + 1);
                        policies.put(id, p);
                        return ok(p);
                }
            }
            if (path.startsWith(API + "/roles/name/")) {
                Map<String, Object> role = roles.get(path.substring((API + "/roles/name/").length()));
                return role == null ? Reply.of(400, "{\"msgDesc\":\"Role with name: x does not exist\"}") : ok(role);
            }
            if (path.equals(API + "/roles") && r.method.equals("POST")) {
                Map<String, Object> role = JSON.readValue(r.body, Map.class);
                role.put("id", nextId++);
                roles.put((String) role.get("name"), role);
                return ok(role);
            }
            return Reply.of(404, "{\"msgDesc\":\"not faked: " + r.method + " " + path + "\"}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Reply ok(Object body) throws IOException {
        return Reply.of(200, JSON.writeValueAsString(body));
    }

    List<String> names() {
        return new ArrayList<>(policies.values().stream().map(p -> (String) p.get("name")).collect(Collectors.toList()));
    }

    @Override
    public void close() {
        server.close();
    }
}
