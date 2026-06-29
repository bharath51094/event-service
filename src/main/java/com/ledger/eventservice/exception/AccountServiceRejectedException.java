package com.ledger.eventservice.exception;

import lombok.Getter;

/**
 * Raised when the Account Service rejects a forwarded transaction for a business reason
 * (e.g. an account already exists with a different currency). Carries the status returned
 * by the Account Service so the Gateway can surface a meaningful error to its caller.
 */
@Getter
public class AccountServiceRejectedException extends RuntimeException {

    private final int status;

    public AccountServiceRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }
}
