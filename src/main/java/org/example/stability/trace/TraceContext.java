package org.example.stability.trace;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TraceContext {
    String traceId;
    String requestType;
    String endpoint;
    String sessionId;
    long startAtMs;
}

