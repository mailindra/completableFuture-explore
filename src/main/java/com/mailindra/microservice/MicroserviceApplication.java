package com.mailindra.microservice;

import com.mailindra.microservice.client.ExternalServiceClient;
import com.mailindra.microservice.controller.UserController;
import com.mailindra.microservice.handler.ErrorHandlingHandler;
import com.mailindra.microservice.handler.HealthCheckHandler;
import com.mailindra.microservice.handler.MetricsHandler;
import com.mailindra.microservice.handler.RequestLoggingHandler;
import com.mailindra.microservice.service.DatabaseService;
import com.mailindra.microservice.service.MetricsService;
import com.mailindra.microservice.service.UserService;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MicroserviceApplication {
    private static final int PORT = Integer.parseInt(System.getProperty("server.port", "8080"));
    private static final int IO_THREADS = Integer.parseInt(System.getProperty("io.threads", "4"));
    private static final int WORKER_THREADS = Integer.parseInt(System.getProperty("worker.threads", "200"));

    public static void main(String[] args) {
        // Initialize components
        ObjectMapper objectMapper = createObjectMapper();
        DatabaseService databaseService = new DatabaseService();
        ExternalServiceClient externalServiceClient = new ExternalServiceClient();
        UserService userService = new UserService(databaseService, externalServiceClient);
        UserController userController = new UserController(userService, objectMapper);
        MetricsService metricsService = new MetricsService();

        // Create routing handler
        RoutingHandler routingHandler = new RoutingHandler()
                .get("/health", new HealthCheckHandler())
                .get("/metrics", new MetricsHandler(metricsService))
                .get("/users/{userId}", userController::getUser)
                .post("/users", userController::createUser)
                .put("/users/{userId}", userController::updateUser)
                .delete("/users/{userId}", userController::deleteUser)
                .setFallbackHandler(exchange -> {
                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                    exchange.getResponseSender().send("Not Found");
                });

        // Add middleware
        HttpHandler handler = new RequestLoggingHandler(
                new ErrorHandlingHandler(
                        new MetricsHandler(metricsService, routingHandler)
                )
        );

        // Configure Undertow server
        Undertow server = Undertow.builder()
                .addHttpListener(PORT, "0.0.0.0")
                .setIoThreads(IO_THREADS)
                .setWorkerThreads(WORKER_THREADS)
                .setHandler(handler)
                .build();

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down gracefully...");
            server.stop();
            databaseService.close();
            externalServiceClient.close();
        }));

        server.start();
        System.out.println("Server started on port " + PORT);
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
