package com.qe.agent.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Canonical statuses for the test generation and self-healing execution lifecycle.
 */
public enum TestStatus {
    PASSED("PASSED"),
    FAILED_AFTER_HEALING("FAILED_AFTER_HEALING"),
    PRE_COMPILE_FAILED("PRE_COMPILE_FAILED"),
    PRE_COMPILE_ERROR("PRE_COMPILE_ERROR"),
    FILE_WRITE_ERROR("FILE_WRITE_ERROR"),
    EXECUTION_ERROR("EXECUTION_ERROR"),
    GENERATION_FAILED("GENERATION_FAILED"),
    FILE_NOT_FOUND("FILE_NOT_FOUND"),
    INVALID_PATH("INVALID_PATH"),
    ERROR("ERROR");

    private final String value;

    TestStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
