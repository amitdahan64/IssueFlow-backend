package com.att.tdp.issueflow.common.domain;

public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public Priority next() {
        return this == CRITICAL ? CRITICAL : values()[ordinal() + 1];
    }
}