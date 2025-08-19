package com.mailindra;

import io.undertow.Undertow;
import io.undertow.util.Headers;
import org.junit.jupiter.api.*;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UndertowBlockHoundTest {

    private Undertow server;
    private int port;

    static {
        BlockHound.install(builder ->
                builder.allowBlockingCallsInside("java.util.concurrent.CompletableFuture", "join")
        );

    }


    @BeforeEach
    void startServer() {
        server = Undertow.builder()
                .addHttpListener(0, "localhost") // 0 = ephemeral port
                .setHandler(exchange -> {
                    // Stay on I/O thread (no dispatch); do a blocking call
                    try {
                        Thread.sleep(10); // BlockHound should intercept this on I/O thread
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                        exchange.getResponseSender().send("OK (should not happen)");
                    } catch (Throwable t) { // BlockHound throws an Error
                        exchange.setStatusCode(500);
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                        exchange.getResponseSender().send("BLOCKED: " + t.getClass().getSimpleName());
                    }
                })
                .build();
        server.start();

        // find the actual bound port
        InetSocketAddress addr = (InetSocketAddress) server.getListenerInfo().get(0).getAddress();
        port = addr.getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

//    @Test
    void sanityCheck_blockhoundIsWorking() {
        Assertions.assertThrows(BlockingOperationError.class, () -> {
            try {
                Thread.sleep(10); // should be flagged
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }


//    @Test
    void blockingOnIoThread_isDetected_andReturns500() throws Exception {
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(resp.body()).contains("BlockingOperationError");
    }

//    @Test
    void blockingOnWorkerThread_isAllowed_whenDispatched() throws Exception {
        // restart server with dispatch to worker pool
        stopServer();
        server = Undertow.builder()
                .addHttpListener(0, "localhost")
                .setHandler(exchange -> exchange.dispatch(() -> {
                    try {
                        Thread.sleep(10); // runs on worker ("task-#"), should NOT be flagged
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                        exchange.getResponseSender().send("OK");
                    } catch (InterruptedException e) {
                        exchange.setStatusCode(500);
                        exchange.getResponseSender().send("Interrupted");
                    }
                }))
                .build();
        server.start();
        InetSocketAddress addr = (InetSocketAddress) server.getListenerInfo().get(0).getAddress();
        port = addr.getPort();

        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("OK");
    }
}
