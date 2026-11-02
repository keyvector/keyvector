package dev.keyvector.core.helpers;

import java.util.Comparator;
import java.util.Optional;

import software.amazon.awssdk.services.keyspacesstreams.KeyspacesStreamsClient;
import software.amazon.awssdk.services.keyspacesstreams.model.GetStreamRequest;
import software.amazon.awssdk.services.keyspacesstreams.model.GetStreamResponse;
import software.amazon.awssdk.services.keyspacesstreams.model.ListStreamsRequest;
import software.amazon.awssdk.services.keyspacesstreams.model.Stream;
import software.amazon.awssdk.services.keyspacesstreams.model.StreamStatus;
import software.amazon.awssdk.services.keyspacesstreams.model.StreamViewType;

/**
 * Finds a table's live CDC stream.
 *
 * <p>A table that has had CDC switched off and on again lists several streams, and the
 * upstream code took whichever came first - possibly a disabled one, which yields nothing
 * forever without an error. This takes the newest enabled stream, or the one named.
 */
public final class StreamLocator {

    private StreamLocator() {
    }

    public static Optional<GetStreamResponse> find(KeyspacesStreamsClient client, String keyspace, String table, String label) {
        return client.listStreamsPaginator(ListStreamsRequest.builder().keyspaceName(keyspace).tableName(table).build())
            .streams().stream()
            .filter(s -> s.keyspaceName().equalsIgnoreCase(keyspace) && s.tableName().equalsIgnoreCase(table))
            .filter(s -> label == null || label.isBlank() || s.streamLabel().equalsIgnoreCase(label))
            .sorted(Comparator.comparing(Stream::streamLabel).reversed())
            .map(s -> client.getStream(GetStreamRequest.builder().streamArn(s.streamArn()).maxResults(1).build()))
            .filter(r -> (label != null && !label.isBlank()) || r.streamStatus() == StreamStatus.ENABLED
                || r.streamStatus() == StreamStatus.ENABLING)
            .findFirst();
    }

    /**
     * Keyvector reads a change as "the row is gone" when it carries no new image. On a stream
     * without new images every insert would therefore delete its vector, so such a stream is
     * refused outright rather than allowed to empty the index.
     */
    public static void requireNewImages(StreamViewType viewType) {
        if (viewType != StreamViewType.NEW_AND_OLD_IMAGES && viewType != StreamViewType.NEW_IMAGE) {
            throw new IllegalStateException("The table's CDC stream records " + viewType
                + "; Keyvector needs NEW_AND_OLD_IMAGES (or NEW_IMAGE). A stream's view type cannot be changed:"
                + " disable CDC and enable it again with view_type NEW_AND_OLD_IMAGES");
        }
    }
}
