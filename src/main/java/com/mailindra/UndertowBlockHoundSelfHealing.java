package com.mailindra;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import jdk.jfr.consumer.RecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.blockhound.BlockingOperationError;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class UndertowBlockHoundSelfHealing {
    private static final Logger log = LoggerFactory.getLogger(UndertowBlockHoundSelfHealing.class);

    // 1) Non-blocking executor (CPU-ish / callbacks). Common pool is instrumented by BlockHound.
    private static final Executor NON_BLOCKING = ForkJoinPool.commonPool();

    // 2) Dedicated blocking pool (DB, legacy libs, file I/O, etc.)
    private static final ExecutorService BLOCKING_POOL =
            new ThreadPoolExecutor(
                    8, 32,
                    60L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "blocking-pool-" + System.nanoTime());
                        t.setDaemon(true);
                        return t;
                    });

    public static void main(String[] args) {

        try (var jfr = JfrPinningProbe.start()) {
            log.info("Starting JFR Probing");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down thread pools...");
            BLOCKING_POOL.shutdown();
            try {
                if (!BLOCKING_POOL.awaitTermination(10, TimeUnit.SECONDS)) {
                    BLOCKING_POOL.shutdownNow();
                }
            } catch (InterruptedException e) {
                BLOCKING_POOL.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));


        Undertow server = Undertow.builder()
                .addHttpListener(8080, "0.0.0.0")
                .setHandler(new Router())
                .build();

        server.start();
        log.info("Server started on http://localhost:8080");

        if(jfr.hasPinningEvents()){
            jfr.printPinningEvents();
        }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------- Router -------
    static final class Router implements HttpHandler {
        @Override
        public void handleRequest(HttpServerExchange exchange) {
            switch (exchange.getRequestPath()) {
                case "/":
                    hello(exchange);
                    break;
                case "/maybe-blocking":
                    demoMaybeBlocking(exchange);
                    break;
                default:
                    exchange.setStatusCode(404);
                    exchange.getResponseSender().send("Not found");
            }
        }
    }

    // Simple hello (fully non-blocking)
    private static void hello(HttpServerExchange exchange) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
        exchange.getResponseSender().send("OK from: "+Thread.currentThread().getName());
    }

    // Demo endpoint: we *think* the operation is non-blocking, but it calls Thread.sleep() by mistake.
    private static void demoMaybeBlocking(HttpServerExchange exchange) {
        // Business logic supplier (has an accidental blocking call)
        Supplier<String> businessLogic = () -> {
            log.info("inside business logic");
            // ❌ This is illegal on non-blocking threads; BlockHound will throw BlockingOperationError
            sleep(Duration.ofMillis(120));
            log.info("returning from business logic");
            return "Processed on " + Thread.currentThread().getName();
        };

        // Run guarded: try on NON_BLOCKING first; if BlockHound fires, offload to BLOCKING_POOL automatically
        runGuarded(businessLogic, NON_BLOCKING, BLOCKING_POOL)
                .orTimeout(3, TimeUnit.SECONDS)
                .whenCompleteAsync((value, err) -> {
                    if (err != null) {
                        exchange.setStatusCode(500);
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                        exchange.getResponseSender().send("Error: " + rootMessage(err));
                    } else {
                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                        exchange.getResponseSender().send("Result: " + value);
                    }
                }, exchange.getIoThread()); // write the response back on Undertow's I/O thread
    }

    // -------- Core guard: self-heal blocking ----------

    /**
     * Tries to run the supplier on a non-blocking executor.
     * If a blocking call is detected (BlockHound throws BlockingOperationError),
     * it transparently resubmits the work to a dedicated blocking executor.
     */
    public static <T> CompletableFuture<T> runGuarded(
            Supplier<T> supplier,
            Executor nonBlockingExec,
            Executor blockingExec
    ) {
        CompletableFuture<T> promise = new CompletableFuture<>();

        // First attempt on non-blocking executor
        CompletableFuture.runAsync(() -> {
            try {
                T value = supplier.get();        // If this calls Thread.sleep / get() etc., BlockHound throws
                promise.complete(value);
            } catch (BlockingOperationError bhe) {
                // 🔁 Offload to blocking executor
                log.warn("Blocking detected on non-blocking path: {}. Offloading to blocking pool.",
                        bhe.getClass().getSimpleName());
                CompletableFuture.supplyAsync(supplier, blockingExec)
                        .whenComplete((v, ex) -> {
                            if (ex != null) promise.completeExceptionally(ex);
                            else promise.complete(v);
                        });
            } catch (Throwable t) {
                promise.completeExceptionally(t);
            }
        }, nonBlockingExec);

        return promise;
    }

    // Utility: a deliberately blocking sleep to trigger BlockHound
    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis()); // ❌
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null) x = x.getCause();
        return x.toString();
    }
}
