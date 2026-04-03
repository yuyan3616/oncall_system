package org.example.stability.queue;

public class RateLimitRejectedException extends RuntimeException {

    public enum Reason {
        QUEUE_FULL,
        QUEUE_TIMEOUT
    }

    private final Reason reason;

    public RateLimitRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

