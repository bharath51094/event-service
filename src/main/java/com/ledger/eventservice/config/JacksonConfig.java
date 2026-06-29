package com.ledger.eventservice.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Trims every incoming JSON string value so that values that differ only by leading/trailing
 * whitespace (e.g. {@code "acct-123"} vs {@code " acct-123"}) are treated identically. This keeps
 * idempotency keys (eventId) and account lookups (accountId) consistent.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer trimStringsCustomizer() {
        return builder -> builder.deserializerByType(String.class, new JsonDeserializer<String>() {
            @Override
            public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                String value = parser.getValueAsString();
                return value == null ? null : value.trim();
            }
        });
    }
}
