package dev.keyvector.connector.embed;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

/**
 * Found by the live test on 2026-11-02: a new account gets 60 Titan V2 requests a minute,
 * and every throttle failed the batch on the spot because the retry check never matched.
 */
class ThrottlingTest {

    @Test
    void throttlesServerErrorsAndNetworkFailuresAreRetried() {
        // Exactly what Bedrock sent: status 429, message "Too many requests...".
        ThrottlingException throttled = (ThrottlingException) ThrottlingException.builder()
            .message("Too many requests, please wait before trying again.")
            .statusCode(429)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("ThrottlingException").build())
            .build();
        assertTrue(BedrockEmbedder.retryable(throttled));
        assertTrue(BedrockEmbedder.retryable((Exception) ValidationException.builder().statusCode(503).build()));
        assertTrue(BedrockEmbedder.retryable(SdkClientException.create("connection reset")));
    }

    @Test
    void mistakesAreNotRetried() {
        assertFalse(BedrockEmbedder.retryable((Exception) AccessDeniedException.builder().statusCode(403).build()));
        assertFalse(BedrockEmbedder.retryable((Exception) ValidationException.builder().statusCode(400).build()));
        assertFalse(BedrockEmbedder.retryable(new IllegalStateException("bad json")));
    }

    static final class CountingEmbedder extends BatchingEmbedder {
        final List<Long> starts = new ArrayList<>();

        CountingEmbedder() {
            super(1, 4, 2, "", "");
        }

        @Override
        protected synchronized List<float[]> request(List<String> texts, boolean query) {
            starts.add(System.nanoTime());
            return List.of(new float[] {1, 2});
        }

        @Override
        public String describe() {
            return "counting";
        }
    }

    @Test
    void theRateLimitSpacesRequestsAcrossThreads() throws Exception {
        CountingEmbedder e = new CountingEmbedder();
        e.withRateLimit(1200); // one request every 50 ms
        long t0 = System.nanoTime();
        e.embedDocuments(List.of("a", "b", "c", "d", "e", "f"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(6, e.starts.size());
        assertTrue(elapsedMs >= 240, "6 requests at 50 ms spacing took only " + elapsedMs + " ms");
        e.close();
    }

    @Test
    void noLimitMeansNoWaiting() throws Exception {
        CountingEmbedder e = new CountingEmbedder();
        e.withRateLimit(0);
        long t0 = System.nanoTime();
        e.embedDocuments(List.of("a", "b", "c", "d", "e", "f"));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200);
        e.close();
    }

    @Test
    void titanGetsItsQuotaByDefault() {
        assertEquals(60, Embedders.TITAN_DEFAULT_RPM);
    }
}
