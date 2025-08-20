package com.mailindra.microservice.handler;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

public class ErrorHandlingHandler implements HttpHandler {
    private final HttpHandler next;

    public ErrorHandlingHandler(HttpHandler next) {
        this.next = next;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        try {
            next.handleRequest(exchange);
        } catch (Exception e) {
            handleException(exchange, e);
        }
    }

    private void handleException(HttpServerExchange exchange, Exception e) {
        System.err.println("Unhandled exception: " + e.getMessage());
        e.printStackTrace();

        if (!exchange.isResponseStarted()) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);

            String errorResponse = String.format(
                    "{\"error\":\"internal_server_error\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
                    e.getMessage() != null ? e.getMessage().replace("\"", "'") : "An unexpected error occurred",
                    java.time.Instant.now()
            );

            exchange.getResponseSender().send(errorResponse);
        }
    }
}