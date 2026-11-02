package dev.keyvector.app;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * {@code GET /health} for the container's health check. On 8081 by default (HEALTH_PORT),
 * because 8080 is where a TEI sidecar in the same task listens.
 */
public final class HealthServer {

    private HealthServer() {
    }

    public static void start() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("HEALTH_PORT", "8081"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }
}
