package com.mailindra;

import io.undertow.Undertow;
import io.undertow.util.Headers;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Undertow + JFR Pinning Probe
 */
public class UndertowPinningProbe {

    private static Recording recording;
    private static Path recordingFile;

    public static void main(String[] args) throws Exception {
        startJfrProbe();

        Undertow server = Undertow.builder()
                .addHttpListener(8080, "localhost")
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                    // Simulate blocking in Undertow I/O thread
                    Thread.sleep(100); // <-- should trigger pinning/blocking
                    exchange.getResponseSender().send("Hello World");
                })
                .build();

        server.start();

        System.out.println("Server started on http://localhost:8080");
        System.out.println("Press ENTER to stop and check pinning...");
        System.in.read();

        server.stop();
        stopAndCheckJfr();
    }

    private static void startJfrProbe() throws IOException {
        recordingFile = Files.createTempFile("undertow-pinning", ".jfr");
        recording = new Recording();
        recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(1));
        recording.enable("jdk.ThreadSleep").withThreshold(Duration.ofMillis(1));
        recording.setToDisk(true);
        recording.setDestination(recordingFile);
        recording.start();
        System.out.println("JFR recording started: " + recordingFile);
    }

    private static void stopAndCheckJfr() throws IOException {
        recording.stop();
        recording.close();
        System.out.println("JFR recording stopped.");

        List<RecordedEvent> events = RecordingFile.readAllEvents(recordingFile);

        boolean pinningDetected = events.stream()
                .anyMatch(e -> e.getEventType().getName().equals("jdk.VirtualThreadPinned"));

        boolean blockingDetected = events.stream()
                .anyMatch(e -> e.getEventType().getName().equals("jdk.ThreadSleep")
                        || e.getEventType().getName().equals("jdk.ThreadPark"));

        if (pinningDetected || blockingDetected) {
            System.out.println("⚠️ Potential blocking/pinning detected:");
            events.forEach(e -> {
                System.out.printf("[%s] %s%n", e.getEventType().getName(), e);
            });
        } else {
            System.out.println("✅ No blocking/pinning detected in Undertow run.");
        }
    }
}
