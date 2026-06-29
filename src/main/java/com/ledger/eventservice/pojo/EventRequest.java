package com.ledger.eventservice.pojo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EventRequest {

    @NotBlank(message = "eventId must not be blank")
    private String eventId;

    @NotBlank(message = "accountId must not be blank")
    private String accountId;

    @NotBlank(message = "type must not be blank")
    @Pattern(regexp = "CREDIT|DEBIT", message = "type must be CREDIT or DEBIT")
    private String type;

    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0", inclusive = false, message = "amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "currency must not be blank")
    private String currency;

    @NotNull(message = "eventTimestamp must not be null")
    private OffsetDateTime eventTimestamp;

    private Map<String, Object> metadata;
}
