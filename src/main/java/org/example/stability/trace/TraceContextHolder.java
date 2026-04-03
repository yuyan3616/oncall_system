package org.example.stability.trace;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

public final class TraceContextHolder {

    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    public static TraceContext start(String requestType, String endpoint, String sessionId) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        TraceContext context = TraceContext.builder()
                .traceId(traceId)
                .requestType(requestType)
                .endpoint(endpoint)
                .sessionId(sessionId == null ? "" : sessionId)
                .startAtMs(System.currentTimeMillis())
                .build();
        attach(context);
        return context;
    }

    public static TraceContext current() {
        return CONTEXT.get();
    }

    public static void attach(TraceContext context) {
        if (context == null) {
            clear();
            return;
        }
        CONTEXT.set(context);
        MDC.put("traceId", context.getTraceId());
        MDC.put("requestType", context.getRequestType());
        MDC.put("endpoint", context.getEndpoint());
        MDC.put("sessionId", context.getSessionId());
    }

    public static Runnable wrap(Runnable runnable) {
        TraceContext capturedContext = current();
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();

        return () -> {
            TraceContext previousContext = current();
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            try {
                if (capturedMdc != null) {
                    MDC.setContextMap(capturedMdc);
                } else {
                    MDC.clear();
                }
                attach(capturedContext);
                runnable.run();
            } finally {
                if (previousMdc != null) {
                    MDC.setContextMap(previousMdc);
                } else {
                    MDC.clear();
                }
                if (previousContext != null) {
                    attach(previousContext);
                } else {
                    clear();
                }
            }
        };
    }

    public static void clear() {
        CONTEXT.remove();
        MDC.remove("traceId");
        MDC.remove("requestType");
        MDC.remove("endpoint");
        MDC.remove("sessionId");
    }
}

