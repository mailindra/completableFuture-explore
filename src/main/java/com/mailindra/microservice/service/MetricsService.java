package com.mailindra.microservice.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.HashMap;

public class MetricsService {
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final Map<String, AtomicLong> endpointCounts = new ConcurrentHashMap<>();
    private final AtomicLong totalResponseTime = new AtomicLong(0);

    public void incrementRequestCount() {
        requestCount.incrementAndGet();
    }

    public void incrementErrorCount() {
        errorCount.incrementAndGet();
    }

    public void incrementEndpointCount(String endpoint) {
        endpointCounts.computeIfAbsent(endpoint, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void addResponseTime(long responseTime) {
        totalResponseTime.addAndGet(responseTime);
    }

    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("requests_total", requestCount.get());
        metrics.put("errors_total", errorCount.get());
        metrics.put("avg_response_time_ms",
                requestCount.get() > 0 ? totalResponseTime.get() / requestCount.get() : 0);
        metrics.put("endpoint_counts", endpointCounts);
        metrics.put("timestamp", java.time.Instant.now());
        return metrics;
    }
}