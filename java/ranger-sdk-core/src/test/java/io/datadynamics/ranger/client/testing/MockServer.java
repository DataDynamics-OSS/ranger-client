package io.datadynamics.ranger.client.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Minimal in-process HTTP server for SDK tests: queued responses or a handler, and recorded requests. */
public final class MockServer implements AutoCloseable {
    /** A recorded request. */
    public static final class Recorded {
        public final String method;
        public final String rawPath;
        public final Map<String, String> query;
        public final Map<String, String> headers;
        public final byte[] body;

        Recorded(String method, String rawPath, Map<String, String> query, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.rawPath = rawPath;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** A canned response. */
    public static final class Reply {
        final int status;
        final String body;
        final Map<String, String> headers = new LinkedHashMap<>();

        private Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public static Reply of(int status, String json) {
            return new Reply(status, json);
        }

        public static Reply of(int status) {
            return new Reply(status, null);
        }

        public Reply header(String name, String value) {
            headers.put(name, value);
            return this;
        }
    }

    private final HttpServer server;
    private final Deque<Reply> queue = new ArrayDeque<>();
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Recorded, Reply> handler;

    public MockServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized MockServer enqueue(Reply reply) {
        queue.add(reply);
        return this;
    }

    public MockServer handler(Function<Recorded, Reply> handler) {
        this.handler = handler;
        return this;
    }

    public List<Recorded> requests() {
        return new ArrayList<>(requests);
    }

    public Recorded last() {
        return requests.get(requests.size() - 1);
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        Map<String, String> query = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
                query.merge(k, v, (a, b) -> a + "," + b);
            }
        }
        Recorded rec = new Recorded(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), query, headers, body);
        requests.add(rec);
        Reply reply;
        synchronized (this) {
            reply = queue.poll();
        }
        if (reply == null && handler != null) {
            reply = handler.apply(rec);
        }
        if (reply == null) {
            reply = Reply.of(500, "{\"msgDesc\":\"MockServer: no reply queued\"}");
        }
        reply.headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] out = reply.body == null ? new byte[0] : reply.body.getBytes(StandardCharsets.UTF_8);
        if (reply.body != null) {
            ex.getResponseHeaders().add("Content-Type", "application/json");
        }
        ex.sendResponseHeaders(reply.status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
