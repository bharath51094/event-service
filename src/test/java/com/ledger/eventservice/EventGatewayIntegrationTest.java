package com.ledger.eventservice;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Event Gateway. A WireMock server stands in for the internal Account
 * Service, so the real RestClient, error mapping, and circuit breaker are exercised. Covers the
 * full Gateway -> Account flow, idempotency, validation, ordered listing, trace propagation,
 * graceful degradation, currency-conflict propagation, the circuit breaker, and the balance proxy.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventGatewayIntegrationTest {

    static final WireMockServer accountService = new WireMockServer(options().dynamicPort());

    static {
        accountService.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", () -> "http://localhost:" + accountService.port());
    }

    @AfterAll
    static void stopWireMock() {
        accountService.stop();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void reset() {
        accountService.resetAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    private void stubTransactionsOk() {
        accountService.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));
    }

    private String event(String eventId, String accountId, String type, String amount, String timestamp) {
        return """
                {"eventId":"%s","accountId":"%s","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"%s"}
                """.formatted(eventId, accountId, type, amount, timestamp);
    }

    @Test
    void fullFlow_forwardsToAccountService_andStoresEvent() throws Exception {
        stubTransactionsOk();

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("evt-1", "acct-1", "CREDIT", "150.00", "2026-05-15T14:02:11Z")))
                .andExpect(status().isCreated());

        accountService.verify(postRequestedFor(urlEqualTo("/accounts/acct-1/transactions")));

        mockMvc.perform(get("/events/{id}", "evt-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-1"));
    }

    @Test
    void duplicateEvent_isIdempotent_andForwardsOnce() throws Exception {
        stubTransactionsOk();
        String body = event("dup-1", "acct-2", "CREDIT", "100.00", "2026-05-15T14:02:11Z");

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()); // replay -> 200

        accountService.verify(1, postRequestedFor(urlEqualTo("/accounts/acct-2/transactions")));
    }

    @Test
    void customMetric_recordsCreatedAndDuplicateOutcomes() throws Exception {
        stubTransactionsOk();
        String body = event("met-1", "acct-met", "CREDIT", "100.00", "2026-05-15T14:02:11Z");

        // The registry is shared across the test context, so assert on deltas, not absolute counts.
        double createdBefore = outcomeCount("CREDIT", "created");
        double duplicateBefore = outcomeCount("CREDIT", "duplicate");

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()); // idempotent replay

        assertThat(outcomeCount("CREDIT", "created") - createdBefore).isEqualTo(1.0);
        assertThat(outcomeCount("CREDIT", "duplicate") - duplicateBefore).isEqualTo(1.0);
    }

    @Test
    void customMetric_recordsUnavailableOutcome() throws Exception {
        accountService.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(503)));

        double unavailableBefore = outcomeCount("CREDIT", "unavailable");

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("met-503", "acct-met2", "CREDIT", "5.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isServiceUnavailable());

        assertThat(outcomeCount("CREDIT", "unavailable") - unavailableBefore).isEqualTo(1.0);
    }

    private double outcomeCount(String type, String outcome) {
        Counter counter = meterRegistry.find("ledger.events.processed")
                .tags("type", type, "outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void invalidEvent_returns400_andDoesNotForward() throws Exception {
        String bad = """
                {"eventId":"","accountId":"acct-3","type":"FOO","amount":-5,"currency":"USD"}
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());

        accountService.verify(0, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    void listEvents_areOrderedByEventTimestamp() throws Exception {
        stubTransactionsOk();
        // submit out of order: late first, then early
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("ord-late", "acct-ord", "CREDIT", "10.00", "2026-05-15T12:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("ord-early", "acct-ord", "CREDIT", "10.00", "2026-05-15T08:00:00Z")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/events").param("account", "acct-ord"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("ord-early"))
                .andExpect(jsonPath("$[1].eventId").value("ord-late"));
    }

    @Test
    void traceId_isPropagatedToAccountService_andEchoed() throws Exception {
        stubTransactionsOk();

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-xyz")
                        .content(event("tr-1", "acct-tr", "CREDIT", "5.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", "trace-xyz"));

        accountService.verify(postRequestedFor(urlEqualTo("/accounts/acct-tr/transactions"))
                .withHeader("X-Trace-Id", equalTo("trace-xyz")));
    }

    @Test
    void internalApiKey_isSentToAccountService() throws Exception {
        stubTransactionsOk();

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("key-1", "acct-key", "CREDIT", "5.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isCreated());

        // The Gateway must authenticate to the internal Account Service on every forwarded call.
        accountService.verify(postRequestedFor(urlEqualTo("/accounts/acct-key/transactions"))
                .withHeader("X-Internal-Api-Key", equalTo("local-internal-api-key")));
    }

    @Test
    void accountServiceUnavailable_returns503_andEventNotStored() throws Exception {
        accountService.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("deg-1", "acct-deg", "CREDIT", "5.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isServiceUnavailable());

        // forward-first: the event must not be stored when the apply failed
        mockMvc.perform(get("/events/{id}", "deg-1")).andExpect(status().isNotFound());
    }

    @Test
    void currencyConflict_fromAccountService_propagatesAs409() throws Exception {
        accountService.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":409,"message":"Currency mismatch","fieldErrors":null}
                                """)));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("cc-1", "acct-cc", "CREDIT", "5.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isConflict());
    }

    @Test
    void circuitBreaker_opensAfterRepeatedFailures_thenShortCircuits() throws Exception {
        accountService.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(503)));

        // breaker config: minimum-number-of-calls=5, failure-rate-threshold=50%
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                            .content(event("cb-" + i, "acct-cb", "CREDIT", "5.00", "2026-05-15T10:00:00Z")))
                    .andExpect(status().isServiceUnavailable());
        }

        assertThat(circuitBreakerRegistry.circuitBreaker("accountService").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        // a further call is short-circuited: still 503, but the Account Service is not even called
        accountService.resetRequests();
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(event("cb-6", "acct-cb", "CREDIT", "5.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isServiceUnavailable());
        accountService.verify(0, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    void balanceProxy_returnsAccountServiceBalance() throws Exception {
        accountService.stubFor(WireMock.get(urlEqualTo("/accounts/acct-bp/balance"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"accountId":"acct-bp","balance":250.00,"currency":"USD"}
                                """)));

        mockMvc.perform(get("/accounts/{id}/balance", "acct-bp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-bp"))
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    @Test
    void unknownAccountBalance_propagatesAs404() throws Exception {
        accountService.stubFor(WireMock.get(urlPathMatching("/accounts/.*/balance"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":404,"message":"Account not found","fieldErrors":null}
                                """)));

        mockMvc.perform(get("/accounts/{id}/balance", "nope"))
                .andExpect(status().isNotFound());
    }
}
