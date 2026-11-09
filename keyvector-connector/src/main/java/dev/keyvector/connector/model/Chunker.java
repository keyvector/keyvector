package dev.keyvector.connector.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a long text into overlapping chunks, each embedded as its own vector.
 *
 * <p>Chunks are measured in words, not tokens, so the same setting works for every embedder;
 * English runs about 1.3 tokens a word, so keep {@code chunk-words} times 1.3 under the model's
 * token limit. Sentences are kept whole where they fit: a chunk ends at a sentence or paragraph
 * boundary unless a single sentence is longer than a chunk. Each chunk after the first starts
 * with the last {@code overlap} words of the one before, so an idea that spans a boundary is
 * still found.
 *
 * <p>Deterministic, which matters: the same text always gives the same chunks, so a chunk's
 * text can be rebuilt from the row at query time (for reranking) and an unchanged row never
 * re-embeds. A text that fits in one chunk is returned unchanged.
 */
public final class Chunker {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\n\\s*\\n");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private final int maxWords;
    private final int overlap;
    private final int maxChunks;

    public Chunker(int maxWords, int overlap, int maxChunks) {
        if (maxWords < 10) {
            throw new IllegalArgumentException("sync.chunk-words must be at least 10; got " + maxWords);
        }
        if (overlap < 0 || overlap > maxWords / 2) {
            throw new IllegalArgumentException("sync.chunk-overlap-words must be between 0 and half of chunk-words (" + maxWords / 2 + "); got " + overlap);
        }
        if (maxChunks < 1 || maxChunks > 1000) {
            throw new IllegalArgumentException("sync.max-chunks must be between 1 and 1000; got " + maxChunks);
        }
        this.maxWords = maxWords;
        this.overlap = overlap;
        this.maxChunks = maxChunks;
    }

    public int maxChunks() {
        return maxChunks;
    }

    /** Characters a chunk suffix can take ({@code #} and the largest chunk number). */
    public int keySuffixLength() {
        return 1 + Integer.toString(maxChunks - 1).length();
    }

    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (words(text).length <= maxWords) {
            return List.of(text);
        }
        // Sentences, each as a list of words. A sentence too long to share a chunk with the
        // overlap is cut into pieces that leave room for it.
        int pieceWords = maxWords - overlap;
        List<String[]> pieces = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(text.strip())) {
            String[] w = words(sentence);
            for (int i = 0; i < w.length; i += pieceWords) {
                pieces.add(Arrays.copyOfRange(w, i, Math.min(w.length, i + pieceWords)));
            }
        }
        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int fresh = 0; // words in the current chunk that are not overlap
        for (String[] piece : pieces) {
            if (fresh > 0 && current.size() + piece.length > maxWords) {
                chunks.add(String.join(" ", current));
                if (chunks.size() == maxChunks) {
                    return chunks;
                }
                List<String> carry = overlap == 0 ? List.of()
                    : new ArrayList<>(current.subList(Math.max(0, current.size() - overlap), current.size()));
                current = new ArrayList<>(carry);
                // A long piece after the overlap could overflow; trim the overlap to make room.
                while (!current.isEmpty() && current.size() + piece.length > maxWords) {
                    current.remove(0);
                }
                fresh = 0;
            }
            current.addAll(Arrays.asList(piece));
            fresh += piece.length;
        }
        if (fresh > 0) {
            chunks.add(String.join(" ", current));
        }
        return chunks.size() > maxChunks ? chunks.subList(0, maxChunks) : chunks;
    }

    private static String[] words(String s) {
        String t = s.strip();
        return t.isEmpty() ? new String[0] : SPACE.split(t);
    }
}
