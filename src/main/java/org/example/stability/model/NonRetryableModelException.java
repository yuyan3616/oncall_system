package org.example.stability.model;

public class NonRetryableModelException extends RuntimeException {
    public NonRetryableModelException(String message, Throwable cause) {
        super(message, cause);
    }
}

