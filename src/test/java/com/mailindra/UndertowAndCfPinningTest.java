package com.mailindra;

import io.undertow.Undertow;
import io.undertow.util.Headers;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 21 pinning detection using JFR's jdk.VirtualThreadPinned.
 * We treat "pinning detected" as "bad (blocking in non-blocking context)".
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UndertowAndCfPinningTest {
    private static final Logger log = LoggerFactory.getLogger(UndertowAndCfPinningTest.class);

    Undertow server;
    int port;
    ExecutorService vtExec;

    @BeforeEach
    void setUp() {
        vtExec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            try { server.stop(); } catch (Throwable ignored) {}
        }
        if (vtExec != null) {
            vtExec.shutdown();
        }
    }

    // ---------------- Undertow: Bad (pinning) ----------------
    @Test @Order(1)
    void undertow_virtualThread_withPinning_isDetected() throws Exception {
        server = Undertow.builder()
                .addHttpListener(0, "localhost")
                .setHandler(exchange -> {
                    // move work from I/O thread to a VIRTUAL THREAD
                    exchange.dispatch(vtExec, () -> {
                        Object lock = UndertowAndCfPinningTest.class; // shared monitor
                        synchronized (lock) {
                            try {
                                // Sleeping while holding a monitor => virtual thread will PARK while owning a monitor => PINNED
                                Thread.sleep(50);
                            } catch (InterruptedException ignored) {}
                        }
                        log.info("Logic Executed.");
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                        exchange.getResponseSender().send("done");
                    });
                })
                .build();
        server.start();
        port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();

        try (var jfr = JfrPinningProbe.start()) {
            var client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(resp.statusCode()).isEqualTo(200);

            // Stop JFR and assert we DID detect pinning
            List<jdk.jfr.consumer.RecordedEvent> pinned = jfr.stopAndGetPinnedEvents();
            String report = jfr.summarize(pinned);
            log.info("Successfull pinning capture: "+report);
            assertThat(pinned)
                    .withFailMessage(() -> "Expected at least 1 pinning event, but got 0.\n" + report)
                    .isNotEmpty();
        }
    }

    // ---------------- Undertow: Good (no pinning) ----------------
    @Test @Order(2)
    void undertow_virtualThread_withoutPinning_hasNoEvents() throws Exception {
        server = Undertow.builder()
                .addHttpListener(0, "localhost")
                .setHandler(exchange -> {
                    exchange.dispatch(vtExec, () -> {
                        // No monitor held while sleeping => NOT pinned
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                        exchange.getResponseSender().send("done");
                    });
                })
                .build();
        server.start();
        port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();

        try (var jfr = JfrPinningProbe.start()) {
            var client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);

            List<jdk.jfr.consumer.RecordedEvent> pinned = jfr.stopAndGetPinnedEvents();
            String report = jfr.summarize(pinned);
            assertThat(pinned)
                    .withFailMessage(() -> "Expected 0 pinning events, but got " + pinned.size() + ".\n" + report)
                    .isEmpty();
        }
    }

    // ---------------- CompletableFuture: Bad (pinning) ----------------
    @Test @Order(3)
    void completableFuture_virtualThread_withPinning_isDetected() throws Exception {
        try (var jfr = JfrPinningProbe.start()) {
            var fut = CompletableFuture.supplyAsync(() -> {
                Object lock = UndertowAndCfPinningTest.class;
                synchronized (lock) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
                return 42;
            }, vtExec);

            Integer result = fut.get(); // wait normally
            assertThat(result).isEqualTo(42);

            List<jdk.jfr.consumer.RecordedEvent> pinned = jfr.stopAndGetPinnedEvents();
            String report = jfr.summarize(pinned);
            assertThat(pinned)
                    .withFailMessage(() -> "Expected pinning in CF task, got none.\n" + report)
                    .isNotEmpty();
        }
    }

    // ---------------- CompletableFuture: Good (no pinning) ----------------
    @Test @Order(4)
    void completableFuture_virtualThread_withoutPinning_hasNoEvents() throws Exception {
        try (var jfr = JfrPinningProbe.start()) {
            var fut = CompletableFuture.supplyAsync(() -> {
                // No monitor held
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                return "ok";
            }, vtExec);

            String result = fut.get();
            assertThat(result).isEqualTo("ok");

            List<jdk.jfr.consumer.RecordedEvent> pinned = jfr.stopAndGetPinnedEvents();
            String report = jfr.summarize(pinned);
            assertThat(pinned)
                    .withFailMessage(() -> "Expected no pinning in CF task, but got some.\n" + report)
                    .isEmpty();
        }
    }
}
