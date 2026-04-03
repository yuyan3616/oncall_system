package org.example.stability.trace;

import org.slf4j.Logger;
import org.slf4j.MDC;

public final class TraceLogger {

    private TraceLogger() {
    }

    public static void info(Logger logger, String event, Object... kvPairs) {
        logger.info(buildMessage(event, kvPairs));
    }

    public static void warn(Logger logger, String event, Object... kvPairs) {
        logger.warn(buildMessage(event, kvPairs));
    }

    public static void error(Logger logger, String event, Object... kvPairs) {
        logger.error(buildMessage(event, kvPairs));
    }

    private static String buildMessage(String event, Object... kvPairs) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("event=").append(event);
        append(sb, "traceId", MDC.get("traceId"));
        append(sb, "endpoint", MDC.get("endpoint"));
        append(sb, "requestType", MDC.get("requestType"));
        append(sb, "sessionId", MDC.get("sessionId"));

        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            String key = String.valueOf(kvPairs[i]);
            Object value = kvPairs[i + 1];
            append(sb, key, value);
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        sb.append(' ').append(key).append('=').append(value);
    }
}

