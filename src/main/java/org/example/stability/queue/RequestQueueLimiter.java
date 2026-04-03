package org.example.stability.queue;

import org.example.config.LocalRateLimitProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RequestQueueLimiter {

    private final LocalRateLimitProperties properties;
    private final AtomicInteger waitingCounter = new AtomicInteger(0);
    private volatile Semaphore semaphore;

    public RequestQueueLimiter(LocalRateLimitProperties properties) {
        this.properties = properties;
        this.semaphore = new Semaphore(Math.max(1, properties.getMaxConcurrent()), true);
    }

    public Permit acquire() {
        if (!properties.isEnabled()) {
            return Permit.noop();
        }
        ensureSemaphore();

        long queueStartAt = System.currentTimeMillis();
        if (semaphore.tryAcquire()) {
            return new Permit(semaphore, 0L);
        }

        int waitingNow = waitingCounter.incrementAndGet();
        if (waitingNow > properties.getMaxQueueSize()) {
            waitingCounter.decrementAndGet();
            throw new RateLimitRejectedException(
                    RateLimitRejectedException.Reason.QUEUE_FULL,
                    "系统繁忙，请稍后再试"
            );
        }

        try {
            boolean acquired = semaphore.tryAcquire(
                    Math.max(1, properties.getMaxWaitSeconds()),
                    TimeUnit.SECONDS
            );
            if (!acquired) {
                throw new RateLimitRejectedException(
                        RateLimitRejectedException.Reason.QUEUE_TIMEOUT,
                        "系统繁忙，请稍后再试"
                );
            }
            return new Permit(semaphore, System.currentTimeMillis() - queueStartAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RateLimitRejectedException(
                    RateLimitRejectedException.Reason.QUEUE_TIMEOUT,
                    "系统繁忙，请稍后再试"
            );
        } finally {
            waitingCounter.decrementAndGet();
        }
    }

    private void ensureSemaphore() {
        if (semaphore == null) {
            this.semaphore = new Semaphore(Math.max(1, properties.getMaxConcurrent()), true);
        }
    }

    public static final class Permit implements AutoCloseable {

        private static final Permit NOOP = new Permit(null, 0L);

        private final Semaphore semaphore;
        private final long waitMs;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(Semaphore semaphore, long waitMs) {
            this.semaphore = semaphore;
            this.waitMs = waitMs;
        }

        public static Permit noop() {
            return NOOP;
        }

        public long getWaitMs() {
            return waitMs;
        }

        @Override
        public void close() {
            if (semaphore == null) {
                return;
            }
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
