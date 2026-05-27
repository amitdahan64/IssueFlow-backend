package com.att.tdp.issueflow.common.domain;

public enum TicketStatus {
    TODO,
    IN_PROGRESS,
    IN_REVIEW,
    DONE;

    public boolean isForwardOrEqual(TicketStatus next) {
        return next.ordinal() >= this.ordinal();
    }
}