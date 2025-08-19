package com.mailindra;

import io.undertow.Undertow;
import io.undertow.util.Headers;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public class UndertowPinningMonitor {

    private static final Path JFR_FILE = Path.of("undertow-pinning.jfr");
    private static final Recording recording = new Recording();

    public static void main(String[] args) throws IOException {
        // Start JFR recording
        recording.enable("jdk.VirtualThreadPinned");
        recording.setDumpOnExit(true);
        recording.setToDisk(true);
        recording.setDestination(JFR_FILE);
        recording.start();

        // Start Undertow
        Undertow server = Undertow.builder()
                .addHttpListener(8080, "0.0.0.0")
                .setHandler(exchange -> {
                    if (exchange.getRequestPath().equals("/check-pinning")) {

                        if (!Files.exists(JFR_FILE) || Files.size(JFR_FILE) == 0) {
                            throw new RuntimeException("JFR file not ready: " + JFR_FILE);
                        }
                        boolean pinned = checkRecentPinning(Duration.ofSeconds(50));
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                        exchange.getResponseSender().send(pinned
                                ? "⚠️ Pinning detected in last 5s"
                                : "✅ No pinning detected");
                    } else if (exchange.getRequestPath().equals("/simulate-blocking")) {
                        // Example of blocking inside CompletableFuture
                        CompletableFuture.runAsync(() -> {
                            System.out.println("simulate blocking");
                            try {
                                Thread.sleep(2000); // This may cause pinning if on vthread
                            } catch (InterruptedException ignored) {}
                        }, Executors.newVirtualThreadPerTaskExecutor());

                        exchange.getResponseSender().send("Simulated blocking submitted");
                    } else {
                        exchange.getResponseSender().send("Use /check-pinning or /simulate-blocking");
                    }
                })
                .build();

        server.start();
        System.out.println("Undertow started at http://localhost:8080");
    }

    private static boolean checkRecentPinning(Duration lookback) {
        long cutoff = System.currentTimeMillis() - lookback.toMillis();
        try {
            List<RecordedEvent> events = RecordingFile.readAllEvents(JFR_FILE);
            return events.stream()
                    .filter(e -> e.getEventType().getName().equals("jdk.VirtualThreadPinned"))
                    .anyMatch(e -> e.getStartTime().toEpochMilli() >= cutoff);
        } catch (IOException e) {
            throw new RuntimeException("Error reading JFR file", e);
        }
    }
}
