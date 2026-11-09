package dev.keyvector.connector.backfill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.keyvector.connector.SyncEngine;
import dev.keyvector.connector.model.Change;
import dev.keyvector.connector.model.RowKey;
import dev.keyvector.connector.sink.VectorSink;

/**
 * Indexes the rows a table already had when the stream was switched on - the stream only
 * carries changes from then on.
 *
 * <p>Run it after the connector is running, not before: then a row updated during the
 * backfill is also on the stream and the stream's later write wins. A row the backfill reads,
 * and the stream then changes, and the backfill writes after the stream did, can leave the
 * older text indexed until that row next changes; {@code keyvector resync} fixes such a row,
 * and the window is one batch wide.
 */
public final class Backfill {

    private static final Logger logger = LoggerFactory.getLogger(Backfill.class);

    private final KeyspacesRows rows;
    private final SyncEngine engine;
    private final VectorSink sink;

    public Backfill(KeyspacesRows rows, SyncEngine engine, VectorSink sink) {
        this.rows = rows;
        this.engine = engine;
        this.sink = sink;
    }

    public record Options(int pageSize, int batchSize, long limit, boolean skipExisting) {
    }

    public SyncEngine.Result run(Options o) {
        List<Change> batch = new ArrayList<>(o.batchSize());
        SyncEngine.Result[] total = {SyncEngine.Result.empty()};
        long[] seen = {0};
        long[] skipped = {0};
        long started = System.currentTimeMillis();
        rows.scan(o.pageSize(), row -> {
                seen[0]++;
                batch.add(new Change(rows.keyOf(row), null, row, null));
                if (batch.size() >= o.batchSize()) {
                    skipped[0] += flush(batch, o.skipExisting(), total);
                    logger.info("Backfill: {} rows read, {} already indexed, {} rows/s", seen[0], skipped[0],
                        seen[0] * 1000 / Math.max(1, System.currentTimeMillis() - started));
                }
                return o.limit() <= 0 || seen[0] < o.limit();
            });
        skipped[0] += flush(batch, o.skipExisting(), total);
        logger.info("Backfill done: {} rows read, {} already indexed", seen[0], skipped[0]);
        return total[0];
    }

    private long flush(List<Change> batch, boolean skipExisting, SyncEngine.Result[] total) {
        if (batch.isEmpty()) {
            return 0;
        }
        long skipped = 0;
        try {
            List<Change> todo = batch;
            if (skipExisting) {
                List<String> keys = new ArrayList<>(batch.size());
                for (Change c : batch) {
                    keys.add(engine.indexKey(safeKey(c)));
                }
                Map<String, float[]> existing = sink.vectors(keys);
                todo = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    if (!existing.containsKey(keys.get(i))) {
                        todo.add(batch.get(i));
                    }
                }
                skipped = batch.size() - todo.size();
            }
            total[0] = total[0].plus(engine.apply(todo));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Backfill batch failed; re-run with --skip-existing to continue", e);
        }
        batch.clear();
        return skipped;
    }

    private static String safeKey(Change c) {
        try {
            return RowKey.encode(c.key());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
