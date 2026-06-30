package com.ledger.eventservice.metrics;

import com.ledger.eventservice.exception.AccountServiceRejectedException;
import com.ledger.eventservice.exception.AccountServiceUnavailableException;
import com.ledger.eventservice.pojo.EventRequest;
import com.ledger.eventservice.service.EventCreationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Records the custom domain metric {@code ledger.events.processed} (Prometheus
 * {@code ledger_events_processed_total}) as a cross-cutting concern, so the business logic in
 * {@link com.ledger.eventservice.service.EventService} stays free of instrumentation.
 *
 * <p>Unlike Actuator's {@code http.server.requests}, this counter exposes the idempotency-replay rate
 * and the business reason for failures rather than just the HTTP status. The {@code @Around} advice can
 * observe all four outcomes from outside the service: the method argument gives the {@code type} tag, the
 * return value distinguishes {@code created} from {@code duplicate}, and the thrown exception type
 * distinguishes {@code rejected} from {@code unavailable}.
 *
 * <p>The pointcut binds to the {@link TrackEventOutcome} annotation rather than a method name, so a
 * future rename of the annotated method cannot silently detach the metric.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class EventOutcomeMetricsAspect {

    private static final String EVENTS_PROCESSED_METRIC = "ledger.events.processed";

    private final MeterRegistry meterRegistry;

    @Around("@annotation(com.ledger.eventservice.metrics.TrackEventOutcome)")
    public Object recordOutcome(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        EventRequest request = (EventRequest) proceedingJoinPoint.getArgs()[0];
        try {
            EventCreationResult result = (EventCreationResult) proceedingJoinPoint.proceed();
            record(request.getType(), result.created() ? "created" : "duplicate");
            return result;
        } catch (AccountServiceRejectedException rejectedException) {
            record(request.getType(), "rejected");
            throw rejectedException;
        } catch (AccountServiceUnavailableException unavailableException) {
            record(request.getType(), "unavailable");
            throw unavailableException;
        }
    }

    private void record(String type, String outcome) {
        Counter.builder(EVENTS_PROCESSED_METRIC)
                .description("Event-create attempts by type and outcome")
                .tag("type", type)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
