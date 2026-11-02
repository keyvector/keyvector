package dev.keyvector.connector.embed;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exponential backoff with jitter around one call. */
public final class Retry {

    private static final Logger logger = LoggerFactory.getLogger(Retry.class);

    private Retry() {
    }

    public static <T> T call(String what, int maxAttempts, Predicate<Exception> retryable, Callable<T> call) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.call();
            } catch (Exception e) {
                last = e;
                if (attempt == maxAttempts || !retryable.test(e)) {
                    throw e;
                }
                long cap = Math.min(20_000L, 250L * (1L << Math.min(attempt, 16)));
                long delay = ThreadLocalRandom.current().nextLong(cap / 2, cap + 1);
                logger.warn("{} failed (attempt {}/{}), retrying in {} ms: {}", what, attempt, maxAttempts, delay, e.toString());
                Thread.sleep(delay);
            }
        }
        throw last;
    }

    /** An HTTP failure worth retrying: rate limited, timed out or the server failed. */
    public static final class HttpStatusException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;

        public HttpStatusException(int status, String message) {
            super("HTTP " + status + ": " + message);
            this.status = status;
        }

        public int status() {
            return status;
        }

        public boolean retryable() {
            return status == 408 || status == 425 || status == 429 || status >= 500;
        }
    }

    public static boolean retryableHttp(Exception e) {
        if (e instanceof HttpStatusException) {
            return ((HttpStatusException) e).retryable();
        }
        return e instanceof java.io.IOException;
    }
}
