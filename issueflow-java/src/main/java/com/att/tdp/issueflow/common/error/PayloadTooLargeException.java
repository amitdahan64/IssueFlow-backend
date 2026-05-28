package com.att.tdp.issueflow.common.error;

/** 413 Payload Too Large. Used by service-level size checks where the
 * Servlet/Spring multipart layer hasn't already rejected the request. */
public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
