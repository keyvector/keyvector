package dev.keyvector.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.keyvector.connector.embed.Embedder;
import dev.keyvector.connector.model.Action;
import dev.keyvector.connector.model.Change;
import dev.keyvector.connector.model.Planner;
import dev.keyvector.connector.sink.VectorDoc;
import dev.keyvector.connector.sink.VectorSink;

/**
 * Applies row changes to the vector index. Shared by the stream, the backfill and the
 * redrive, so all three follow exactly the same rules.
 *
 * <p>Order inside a batch: embed first, then write, then delete. Nothing is written until
 * every embedding in the batch succeeded, so a failed batch leaves the index untouched for
 * the retry. Keys are unique after planning, so deletes and puts never touch the same row.
 */
public final class SyncEngine {

    private static final Logger logger = LoggerFactory.getLogger(SyncEngine.class);

    private final Planner planner;
    private final Embedder embedder;
    private final VectorSink sink;

    public SyncEngine(Planner planner, Embedder embedder, VectorSink sink) {
        this.planner = planner;
        this.embedder = embedder;
        this.sink = sink;
    }

    public record Result(int embedded, int reused, int deleted, int unchanged, int rejected) {
        public Result plus(Result o) {
            return new Result(embedded + o.embedded, reused + o.reused, deleted + o.deleted,
                unchanged + o.unchanged, rejected + o.rejected);
        }

        public static Result empty() {
            return new Result(0, 0, 0, 0, 0);
        }
    }

    public Result apply(List<Change> changes) throws Exception {
        if (changes.isEmpty()) {
            return Result.empty();
        }
        Planner.Plan plan = planner.plan(changes);

        List<Action> toEmbed = new ArrayList<>();
        List<Action> toReuse = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();
        for (Action a : plan.actions()) {
            if (a.kind() == Action.Kind.DELETE) {
                toDelete.add(a.key());
            } else if (a.reuseVector()) {
                toReuse.add(a);
            } else {
                toEmbed.add(a);
            }
        }

        List<VectorDoc> docs = new ArrayList<>(toEmbed.size() + toReuse.size());
        int reused = 0;
        if (!toReuse.isEmpty()) {
            List<String> keys = new ArrayList<>(toReuse.size());
            toReuse.forEach(a -> keys.add(a.key()));
            Map<String, float[]> stored = sink.vectors(keys);
            for (Action a : toReuse) {
                float[] v = stored.get(a.key());
                if (v != null) {
                    docs.add(new VectorDoc(a.key(), v, a.metadata()));
                    reused++;
                } else {
                    // Only the metadata changed, but the row was never indexed (it predates the
                    // connector and no backfill ran): embed it now rather than leave it out.
                    toEmbed.add(a);
                }
            }
        }
        if (!toEmbed.isEmpty()) {
            List<String> texts = new ArrayList<>(toEmbed.size());
            toEmbed.forEach(a -> texts.add(a.text()));
            List<float[]> vectors = embedder.embedDocuments(texts);
            for (int i = 0; i < toEmbed.size(); i++) {
                Action a = toEmbed.get(i);
                docs.add(new VectorDoc(a.key(), vectors.get(i), a.metadata()));
            }
        }
        if (!docs.isEmpty()) {
            sink.put(docs);
        }
        if (!toDelete.isEmpty()) {
            sink.delete(toDelete);
        }
        Result r = new Result(toEmbed.size(), reused, toDelete.size(), plan.unchanged(), plan.rejected());
        logger.info("Applied {} changes: {} embedded, {} reused, {} deleted, {} unchanged, {} rejected",
            changes.size(), r.embedded(), r.reused(), r.deleted(), r.unchanged(), r.rejected());
        return r;
    }
}
