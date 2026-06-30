package com.ledger.eventservice.metrics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose event-processing outcome should be recorded as the custom
 * {@code ledger.events.processed} metric by {@link EventOutcomeMetricsAspect}.
 *
 * <p>The aspect's pointcut binds to this annotation rather than to a method name, so renaming the
 * annotated method does not silently detach the metric — the annotation travels with the method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackEventOutcome {
}
