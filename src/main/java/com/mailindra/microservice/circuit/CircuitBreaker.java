package com.mailindra.microservice.circuit;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class CircuitBreaker {
    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final int successThreshold;
    private final long timeoutDuration;

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    public CircuitBreaker(int failureThreshold, int successThreshold, long timeoutDuration) {
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.timeoutDuration = timeoutDuration;
    }

    public boolean allowRequest() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - lastFailureTime.get() > timeoutDuration) {
                state = State.HALF_OPEN;
                return true;
            }
            return false;
        }
        return true;
    }

    public void recordSuccess() {
        failureCount.set(0);
        if (state == State.HALF_OPEN) {
            if (successCount.incrementAndGet() >= successThreshold) {
                state = State.CLOSED;
                successCount.set(0);
            }
        }
    }

    public void recordFailure() {
        successCount.set(0);
        int failures = failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        if (failures >= failureThreshold) {
            state = State.OPEN;
        }
    }

    public State getState() {
        return state;
    }
}
