package com.ledger.eventservice.config;

import com.ledger.eventservice.web.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    @Bean
    public RestClient accountServiceRestClient(RestClient.Builder builder,
                                               @Value("${account-service.base-url}") String baseUrl,
                                               @Value("${account-service.api-key}") String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    // Propagate the current request's trace id to the Account Service so the
                    // trace is continuous across both services.
                    String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
                    if (StringUtils.hasText(traceId)) {
                        request.getHeaders().add(TraceIdFilter.TRACE_ID_HEADER, traceId);
                    }
                    // Authenticate to the internal Account Service with the shared secret.
                    request.getHeaders().add(INTERNAL_API_KEY_HEADER, apiKey);
                    return execution.execute(request, body);
                })
                .build();
    }
}
