package com.mailindra.microservice.handler;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class RequestLoggingHandler implements HttpHandler {
    private final HttpHandler next;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public RequestLoggingHandler(HttpHandler next) {
        this.next = next;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        long startTime = System.currentTimeMillis();
        String timestamp = LocalDateTime.now().format(formatter);
        String method = exchange.getRequestMethod().toString();
        String path = exchange.getRequestPath();
        String remoteAddress = exchange.getSourceAddress().getHostString();

        try {
            next.handleRequest(exchange);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            int statusCode = exchange.getStatusCode();

            System.out.printf("%s %s %s %s %d %dms%n",
                    timestamp, remoteAddress, method, path, statusCode, duration);
        }
    }
}
