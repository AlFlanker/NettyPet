package com.otr.portal.command.model;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@With
@Value
@Builder
public class CommandResilienceHolder {
    Bulkhead bulkhead;
    TimeLimiter timeLimiter;
    CircuitBreaker circuitBreaker;
    Retry retry;
}
