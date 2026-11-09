package dev.keyvector.connector;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.keyvector.connector.model.Chunker;

class ChunkerTest {

    private static String words(String prefix, int n) {
        List<String> w = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            w.add(prefix + i);
        }
        return String.join(" ", w);
    }

    private static int count(String s) {
        return s.isBlank() ? 0 : s.strip().split("\\s+").length;
    }

    @Test
    void aShortTextIsOneChunkUnchanged() {
        String t = "Line one.\n\nLine  two keeps its spacing.";
        assertEquals(List.of(t), new Chunker(50, 10, 100).split(t));
        assertEquals(List.of(), new Chunker(50, 10, 100).split("   "));
    }

    @Test
    void chunksStayUnderTheLimitAndCoverEveryWord() {
        Chunker c = new Chunker(20, 5, 100);
        String text = words("w", 95);
        List<String> chunks = c.split(text);
        assertTrue(chunks.size() > 1);
        chunks.forEach(ch -> assertTrue(count(ch) <= 20, ch));
        // Every word appears, and the last word is in the last chunk.
        String all = String.join(" ", chunks);
        for (int i = 0; i < 95; i++) {
            assertTrue((" " + all + " ").contains(" w" + i + " "), "missing w" + i);
        }
        assertTrue(chunks.get(chunks.size() - 1).endsWith("w94"));
    }

    @Test
    void eachChunkStartsWithTheEndOfThePreviousOne() {
        List<String> chunks = new Chunker(20, 5, 100).split(words("w", 60));
        for (int i = 1; i < chunks.size(); i++) {
            String[] prev = chunks.get(i - 1).split(" ");
            String[] next = chunks.get(i).split(" ");
            assertEquals(prev[prev.length - 5], next[0], "chunk " + i + " should start with the previous chunk's last 5 words");
        }
    }

    @Test
    void sentencesAreKeptWholeWhereTheyFit() {
        String s1 = words("a", 8) + ".";
        String s2 = words("b", 8) + ".";
        String s3 = words("c", 8) + ".";
        List<String> chunks = new Chunker(18, 0, 100).split(s1 + " " + s2 + " " + s3);
        assertEquals(List.of(s1 + " " + s2, s3), chunks, "a chunk ends at a sentence boundary, not mid-sentence");
    }

    @Test
    void aSentenceLongerThanAChunkIsSplit() {
        List<String> chunks = new Chunker(10, 0, 100).split(words("x", 25));
        assertEquals(3, chunks.size());
        assertEquals(10, count(chunks.get(0)));
    }

    @Test
    void theSameTextAlwaysGivesTheSameChunks() {
        Chunker c = new Chunker(15, 3, 100);
        String t = words("a", 12) + ". " + words("b", 30) + ".\n\n" + words("c", 7) + "?";
        assertEquals(c.split(t), c.split(t));
    }

    @Test
    void stopsAtMaxChunks() {
        List<String> chunks = new Chunker(10, 0, 3).split(words("x", 100));
        assertEquals(3, chunks.size());
    }

    @Test
    void refusesSettingsThatCannotWork() {
        assertThrows(IllegalArgumentException.class, () -> new Chunker(5, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Chunker(20, 11, 10), "overlap over half a chunk");
        assertThrows(IllegalArgumentException.class, () -> new Chunker(20, 2, 0));
        assertEquals(3, new Chunker(20, 2, 100).keySuffixLength(), "#99");
    }
}
