package dev.keyvector.core.processor;

import java.util.concurrent.ThreadLocalRandom;

import com.typesafe.config.Config;

import dev.keyvector.core.config.KeyspacesConfig;

/**
 * What the processor does when a batch cannot be applied.
 *
 * <p>The upstream sample logged the failure and returned, and KCL then checkpointed past
 * the batch on the next success: rows silently never reached the target. Here a batch is
 * retried with backoff until it is applied. After {@code maxAttempts} it is parked in the
 * dead-letter queue, if one is configured, and only then is the shard allowed to move on.
 * With no dead-letter queue the shard keeps retrying - it stalls rather than loses data,
 * which is the visible failure (iterator age climbs) rather than the invisible one.
 */
public final class DeliveryPolicy {

    private final int maxAttempts;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final String deadLetterQueueUrl;

    public DeliveryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis, String deadLetterQueueUrl) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max-attempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = Math.max(1, baseDelayMillis);
        this.maxDelayMillis = Math.max(this.baseDelayMillis, maxDelayMillis);
        this.deadLetterQueueUrl = deadLetterQueueUrl == null || deadLetterQueueUrl.isBlank() ? null : deadLetterQueueUrl.trim();
    }

    public static DeliveryPolicy fromConfig(Config config) {
        return new DeliveryPolicy(
            KeyspacesConfig.getConfigValue(config, "keyvector.processor.max-attempts", 10, false),
            KeyspacesConfig.getConfigValue(config, "keyvector.processor.base-delay-millis", 500L, false),
            KeyspacesConfig.getConfigValue(config, "keyvector.processor.max-delay-millis", 30000L, false),
            KeyspacesConfig.getConfigValue(config, "keyvector.processor.dead-letter-queue-url", "", false));
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public String deadLetterQueueUrl() {
        return deadLetterQueueUrl;
    }

    public boolean hasDeadLetterQueue() {
        return deadLetterQueueUrl != null;
    }

    /** Exponential backoff with full jitter, for the given 1-based attempt that just failed. */
    public long delayAfter(int attempt) {
        double exp = baseDelayMillis * Math.pow(2, Math.min(attempt - 1, 30));
        long cap = (long) Math.min(exp, maxDelayMillis);
        return cap <= 1 ? cap : ThreadLocalRandom.current().nextLong(cap / 2, cap + 1);
    }
}
