package com.mailindra.microservice.handler;

import com.mailindra.microservice.service.MetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

public class MetricsHandler implements HttpHandler {
    private final MetricsService metricsService;
    private final ObjectMapper objectMapper;
    private final HttpHandler next;

    // Constructor for endpoint handler
    public MetricsHandler(MetricsService metricsService) {
        this.metricsService = metricsService;
        this.objectMapper = new ObjectMapper();
        this.next = null;
    }

    // Constructor for middleware
    public MetricsHandler(MetricsService metricsService, HttpHandler next) {
        this.metricsService = metricsService;
        this.objectMapper = new ObjectMapper();
        this.next = next;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (next == null) {
            // This is the metrics endpoint handler
            try {
                String json = objectMapper.writeValueAsString(metricsService.getMetrics());
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                exchange.setStatusCode(StatusCodes.OK);
                exchange.getResponseSender().send(json);
            } catch (Exception e) {
                exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
                exchange.getResponseSender().send("{\"error\":\"Failed to generate metrics\"}");
            }
        } else {
            // This is middleware - track metrics and pass to next handler
            long startTime = System.currentTimeMillis();
            metricsService.incrementRequestCount();
            metricsService.incrementEndpointCount(exchange.getRequestPath());

            try {
                next.handleRequest(exchange);
                long responseTime = System.currentTimeMillis() - startTime;
                metricsService.addResponseTime(responseTime);
            } catch (Exception e) {
                metricsService.incrementErrorCount();
                throw e;
            }
        }
    }
}
