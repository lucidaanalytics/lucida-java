package dev.lucida;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Real local HTTP server (JDK built-in) recording POST bodies; {@code statuses} scripts replies, then 200. */
final class FakeServer implements AutoCloseable {
    static final String DEFS = "{\"flags\":[{\"key\":\"on\",\"type\":\"boolean\",\"active\":true,\"salt\":\"s\",\"conditions\":[{\"filters\":[],\"rollout_pct\":100}]}]}";
    final List<JsonNode> bodies = Collections.synchronizedList(new ArrayList<>());
    final List<String> queries = Collections.synchronizedList(new ArrayList<>());
    final List<String> auth = Collections.synchronizedList(new ArrayList<>());
    final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    final List<String> ifNoneMatch = Collections.synchronizedList(new ArrayList<>());
    /** Reply body for POST /v1/flags. */
    volatile String remoteReply = "{\"flags\":{}}";
    /** Reply body for GET /v1/flags/{key}/remote-config; null = 404. */
    volatile String remoteConfigReply;
    final List<String> rawPaths = Collections.synchronizedList(new ArrayList<>());
    final Deque<Integer> statuses = new ArrayDeque<>();
    private final HttpServer server;

    FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            paths.add(ex.getRequestURI().getPath());
            rawPaths.add(ex.getRequestURI().getRawPath());
            if (ex.getRequestURI().getPath().endsWith("/remote-config")) {
                String rc = remoteConfigReply;
                if (rc == null) {
                    ex.sendResponseHeaders(404, -1);
                } else {
                    byte[] b = rc.getBytes();
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                }
                ex.close();
                return;
            }
            if (ex.getRequestMethod().equals("GET")) {
                String inm = ex.getRequestHeaders().getFirst("If-None-Match");
                ifNoneMatch.add(String.valueOf(inm));
                if ("\"v1\"".equals(inm)) {
                    ex.sendResponseHeaders(304, -1);
                    ex.close();
                    return;
                }
                byte[] b = DEFS.getBytes();
                ex.getResponseHeaders().add("ETag", "\"v1\"");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
                return;
            }
            String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
            queries.add(q);
            try (InputStream in = ex.getRequestBody()) {
                byte[] raw = q.contains("compression=gzip") ? new GZIPInputStream(in).readAllBytes() : in.readAllBytes();
                bodies.add(new ObjectMapper().readTree(raw));
            }
            Integer s;
            synchronized (statuses) {
                s = statuses.poll();
            }
            if (ex.getRequestURI().getPath().equals("/v1/flags")) {
                byte[] b = remoteReply.getBytes();
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            } else {
                ex.sendResponseHeaders(s == null ? 200 : s, -1);
            }
            ex.close();
        });
        server.start();
    }

    String host() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
