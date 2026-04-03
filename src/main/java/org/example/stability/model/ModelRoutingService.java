package org.example.stability.model;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.config.AIRoutingProperties;
import org.example.stability.trace.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelRoutingService {

    private static final Logger logger = LoggerFactory.getLogger(ModelRoutingService.class);

    private final AIRoutingProperties properties;
    private final Map<String, CircuitState> states = new ConcurrentHashMap<>();

    public ModelRoutingService(AIRoutingProperties properties) {
        this.properties = properties;
    }

    public enum RouteGroup {
        CHAT,
        AIOPS
    }

    @FunctionalInterface
    public interface ModelCall<T> {
        T call(String model) throws Exception;
    }

    public List<String> getCandidates(RouteGroup group) {
        List<String> configured = switch (group) {
            case CHAT -> properties.getChat().getModels();
            case AIOPS -> properties.getAiops().getModels();
        };
        List<String> candidates = new ArrayList<>();
        if (configured != null) {
            for (String model : configured) {
                if (model != null && !model.trim().isEmpty()) {
                    candidates.add(model.trim());
                }
            }
        }
        if (candidates.isEmpty()) {
            candidates.add(DashScopeChatModel.DEFAULT_MODEL_NAME);
        }
        return candidates;
    }

    public <T> T executeWithFallback(RouteGroup group, String endpoint, ModelCall<T> call) {
        List<String> candidates = getCandidates(group);
        TraceLogger.info(logger, "model_candidates_loaded",
                "candidateCount", candidates.size(),
                "candidates", candidates);

        Throwable lastError = null;
        int attempt = 0;
        for (String model : candidates) {
            if (!allowAttempt(model)) {
                TraceLogger.info(logger, "model_attempt_skipped",
                        "model", model,
                        "reason", "circuit_open");
                continue;
            }

            attempt++;
            long start = System.currentTimeMillis();
            TraceLogger.info(logger, "model_attempt_start",
                    "endpoint", endpoint,
                    "model", model,
                    "attempt", attempt);

            try {
                T result = call.call(model);
                markSuccess(model);
                TraceLogger.info(logger, "model_attempt_success",
                        "endpoint", endpoint,
                        "model", model,
                        "attempt", attempt,
                        "elapsedMs", System.currentTimeMillis() - start);
                return result;
            } catch (Throwable ex) {
                markFailure(model);
                long elapsed = System.currentTimeMillis() - start;
                boolean retryable = !(ex instanceof NonRetryableModelException);
                TraceLogger.warn(logger, "model_attempt_fail",
                        "endpoint", endpoint,
                        "model", model,
                        "attempt", attempt,
                        "elapsedMs", elapsed,
                        "errorType", ex.getClass().getSimpleName(),
                        "errorMessage", Objects.toString(ex.getMessage(), ""),
                        "willRetry", retryable);
                lastError = ex;
                if (!retryable) {
                    throw unwrapRuntime(ex);
                }
            }
        }

        TraceLogger.error(logger, "fallback_exhausted",
                "endpoint", endpoint,
                "candidateCount", candidates.size(),
                "errorType", lastError == null ? "Unknown" : lastError.getClass().getSimpleName(),
                "errorMessage", lastError == null ? "" : Objects.toString(lastError.getMessage(), ""));

        if (lastError instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException("All model candidates failed", lastError);
    }

    public boolean allowAttempt(String model) {
        CircuitState state = stateOf(model);
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.status == Status.OPEN) {
                if (now < state.openUntilMs) {
                    return false;
                }
                state.status = Status.HALF_OPEN;
                state.halfOpenInFlight = false;
            }
            if (state.status == Status.HALF_OPEN) {
                if (state.halfOpenInFlight) {
                    return false;
                }
                state.halfOpenInFlight = true;
                return true;
            }
            return true;
        }
    }

    public void markSuccess(String model) {
        CircuitState state = stateOf(model);
        synchronized (state) {
            state.consecutiveFailures = 0;
            state.openUntilMs = 0L;
            state.halfOpenInFlight = false;
            state.status = Status.CLOSED;
        }
    }

    public void markFailure(String model) {
        CircuitState state = stateOf(model);
        synchronized (state) {
            if (state.status == Status.HALF_OPEN) {
                state.status = Status.OPEN;
                state.openUntilMs = System.currentTimeMillis() + properties.getCircuitBreaker().getOpenWindowMs();
                state.consecutiveFailures = 0;
                state.halfOpenInFlight = false;
                return;
            }

            state.consecutiveFailures++;
            if (state.consecutiveFailures >= properties.getCircuitBreaker().getFailureThreshold()) {
                state.status = Status.OPEN;
                state.openUntilMs = System.currentTimeMillis() + properties.getCircuitBreaker().getOpenWindowMs();
                state.consecutiveFailures = 0;
            }
        }
    }

    private RuntimeException unwrapRuntime(Throwable throwable) {
        if (throwable instanceof NonRetryableModelException nonRetryableModelException) {
            Throwable cause = nonRetryableModelException.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            return new RuntimeException(Objects.toString(cause == null ? throwable.getMessage() : cause.getMessage(), "Model call failed"), cause);
        }
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(throwable.getMessage(), throwable);
    }

    private CircuitState stateOf(String model) {
        return states.computeIfAbsent(model, ignored -> new CircuitState());
    }

    private enum Status {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private static final class CircuitState {
        private Status status = Status.CLOSED;
        private int consecutiveFailures = 0;
        private long openUntilMs = 0L;
        private boolean halfOpenInFlight = false;
    }
}

