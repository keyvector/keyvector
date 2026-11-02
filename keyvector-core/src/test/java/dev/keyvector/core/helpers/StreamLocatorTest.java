package dev.keyvector.core.helpers;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.keyspacesstreams.model.StreamViewType;

class StreamLocatorTest {

    @Test
    void refusesStreamsThatWouldReadEveryChangeAsADelete() {
        assertDoesNotThrow(() -> StreamLocator.requireNewImages(StreamViewType.NEW_AND_OLD_IMAGES));
        assertDoesNotThrow(() -> StreamLocator.requireNewImages(StreamViewType.NEW_IMAGE));
        assertThrows(IllegalStateException.class, () -> StreamLocator.requireNewImages(StreamViewType.KEYS_ONLY));
        assertThrows(IllegalStateException.class, () -> StreamLocator.requireNewImages(StreamViewType.OLD_IMAGE));
        assertThrows(IllegalStateException.class, () -> StreamLocator.requireNewImages(null));
    }
}
