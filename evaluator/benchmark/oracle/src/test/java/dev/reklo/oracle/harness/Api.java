package dev.reklo.oracle.harness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** HTTP access to the app under test. Base URL from -Dreklo.baseUrl (default http://localhost:8080). */
public final class Api {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    private Api() {}

    public static String baseUrl() {
        return System.getProperty("reklo.baseUrl", "http://localhost:8080");
    }

    public static Resp call(String method, String path, String token, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(30));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : Json.write(body)));
        }
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.headers().map(), r.body());
        } catch (IOException e) {
            throw new AssertionError("no response from " + method + " " + baseUrl() + path + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    public static Resp get(String path, String token) { return call("GET", path, token, null); }
    public static Resp post(String path, String token, Object body) { return call("POST", path, token, body); }
    public static Resp put(String path, String token, Object body) { return call("PUT", path, token, body); }
    public static Resp delete(String path, String token) { return call("DELETE", path, token, null); }

    /** Login with the seeded credentials of a role's user; the token is cached for the run. */
    public static String as(String username) {
        return TOKENS.computeIfAbsent(username, u -> {
            Resp r = call("POST", "/api/auth/login", null, Map.of("username", u, "password", Contract.passwordFor(u)));
            if (r.status() != 200) throw new AssertionError("login as seeded user '" + u + "' failed: " + r);
            String token = r.str("token");
            if (token == null || token.isBlank()) throw new AssertionError("login response has no token: " + r);
            return token;
        });
    }

    /** Wait for GET /actuator/health to report UP. */
    public static void awaitHealthy(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "no response";
        while (System.nanoTime() < deadline) {
            try {
                Resp r = call("GET", "/actuator/health", null, null);
                if (r.status() == 200 && "UP".equals(r.str("status"))) return;
                last = r.toString();
            } catch (AssertionError | RuntimeException e) {
                last = String.valueOf(e.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("app at " + baseUrl() + " is not healthy after " + timeout.toSeconds() + " s: " + last);
    }
}
