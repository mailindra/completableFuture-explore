package com.mailindra.microservice.client;

import com.mailindra.microservice.circuit.CircuitBreaker;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ExternalServiceClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public ExternalServiceClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.baseUrl = System.getProperty("external.service.url", "https://api.example.com");
        this.circuitBreaker = new CircuitBreaker(5, 2, 60000); // 5 failures, 2 successes, 1 minute timeout
    }

    public CompletableFuture<Map<String, Object>> validateUser(String email) {
        if (!circuitBreaker.allowRequest()) {
            return CompletableFuture.completedFuture(
                    Map.of("valid", false, "reason", "circuit_breaker_open")
            );
        }

        String url = baseUrl + "/validate-email?email=" + email;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Microservice/1.0")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> result = objectMapper.readValue(
                                    response.body(), Map.class);
                            circuitBreaker.recordSuccess();
                            return CompletableFuture.completedFuture(result);
                        } catch (Exception e) {
                            circuitBreaker.recordFailure();
                            return CompletableFuture.failedFuture(
                                    new RuntimeException("Failed to parse response", e));
                        }
                    } else {
                        circuitBreaker.recordFailure();
                        return CompletableFuture.failedFuture(
                                new RuntimeException("External service error: " + response.statusCode()));
                    }
                })
                .exceptionally(throwable -> {
                    circuitBreaker.recordFailure();
                    // Return default validation result on error
                    return Map.of("valid", false, "reason", "validation_failed");
                });
    }

    public CompletableFuture<Void> notifyUserCreated(Long userId, String email) {
        if (!circuitBreaker.allowRequest()) {
            return CompletableFuture.completedFuture(null);
        }

        String url = baseUrl + "/notifications";

        Map<String, Object> payload = Map.of(
                "type", "user_created",
                "userId", userId,
                "email", email
        );

        try {
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            circuitBreaker.recordSuccess();
                        } else {
                            circuitBreaker.recordFailure();
                            System.err.println("Notification failed: " + response.statusCode());
                        }
                    })
                    .exceptionally(throwable -> {
                        circuitBreaker.recordFailure();
                        System.err.println("Notification error: " + throwable.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return CompletableFuture.failedFuture(e);
        }
    }

    public void close() {
        // HttpClient doesn't need explicit closing in Java 11+
    }
}