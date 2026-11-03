package dev.keyvector.app;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.oss.driver.api.core.CqlSession;

import dev.keyvector.connector.Settings;
import dev.keyvector.connector.SyncEngine;
import dev.keyvector.connector.backfill.Backfill;
import dev.keyvector.connector.backfill.KeyspacesRows;
import dev.keyvector.connector.backfill.Redrive;
import dev.keyvector.connector.backfill.Resync;
import dev.keyvector.connector.embed.Embedder;
import dev.keyvector.connector.embed.Embedders;
import dev.keyvector.connector.query.KeyvectorQuery;
import dev.keyvector.connector.sink.Sinks;
import dev.keyvector.connector.sink.VectorSink;
import dev.keyvector.core.config.KeyspacesConfig;
import dev.keyvector.core.helpers.AWSHelpers;
import dev.keyvector.core.scheduler.KCLScheduler;
import software.amazon.kinesis.coordinator.Scheduler;

/** The {@code keyvector} command. */
public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    static final String USAGE = String.join("\n",
        "Usage: keyvector <command> [--config keyvector.conf] [options]",
        "",
        "Commands:",
        "  run                       Follow the table's CDC stream and keep the vector index in sync",
        "  backfill                  Index the rows the table already had",
        "      --page-size N           rows per CQL page (default 500)",
        "      --batch-size N          rows per embedding batch (default 100)",
        "      --limit N               stop after N rows",
        "      --skip-existing         leave rows that already have a vector (to resume a backfill)",
        "  resync KEY [KEY...]       Re-read these rows from Keyspaces and fix their vectors",
        "  redrive [--queue-url U]   Resync every row parked in the dead-letter queue",
        "  query TEXT                Semantic search; prints one JSON line per hit",
        "      --top-k N               results (default 10, at most 100)",
        "      --filter JSON           S3 Vectors metadata filter, e.g. '{\"genre\":\"drama\"}'",
        "      --no-rows               return keys and metadata without reading Keyspaces",
        "  check                     Validate the configuration against AWS without writing anything",
        "",
        "The config file defaults to $APPLICATION_CONF_LOCATION, then ./keyvector.conf.");

    private Main() {
    }

    public static void main(String[] argv) throws Exception {
        Args args = Args.parse(argv);
        if (args.command == null || args.has("help") || "help".equals(args.command)) {
            System.out.println(USAGE);
            return;
        }
        String configPath = args.get("config", System.getenv().getOrDefault("APPLICATION_CONF_LOCATION", "keyvector.conf"));
        if (!new File(configPath).exists()) {
            System.err.println("No config file at " + configPath + " (see keyvector.example.conf)");
            System.exit(2);
        }
        KeyspacesConfig config = new KeyspacesConfig(configPath);
        Settings settings = Settings.from(config.getConfig());

        switch (args.command) {
            case "run":
                run(config);
                break;
            case "backfill":
                withEngine(config, settings, (rows, engine, sink, embedder) -> {
                    SyncEngine.Result r = new Backfill(rows, engine, sink).run(new Backfill.Options(
                        args.getInt("page-size", 500), args.getInt("batch-size", 100),
                        args.getLong("limit", 0), args.has("skip-existing")));
                    System.out.println(Json.write(summary(r)));
                });
                break;
            case "resync":
                if (args.positional.isEmpty()) {
                    throw new IllegalArgumentException("resync needs at least one key");
                }
                withEngine(config, settings, (rows, engine, sink, embedder) ->
                    System.out.println(Json.write(summary(new Resync(rows, engine).keys(args.positional)))));
                break;
            case "redrive":
                withEngine(config, settings, (rows, engine, sink, embedder) -> {
                    String queue = args.get("queue-url", KeyspacesConfig.getConfigValue(config.getConfig(),
                        "keyvector.processor.dead-letter-queue-url", "", false));
                    System.out.println(Json.write(summary(new Redrive(new Resync(rows, engine), queue, settings.region()).drain())));
                });
                break;
            case "query":
                query(config, settings, args);
                break;
            case "check":
                System.exit(Check.run(config, settings) ? 0 : 1);
                break;
            default:
                System.err.println("Unknown command: " + args.command + "\n\n" + USAGE);
                System.exit(2);
        }
        System.exit(0);
    }

    interface EngineTask {
        void accept(KeyspacesRows rows, SyncEngine engine, VectorSink sink, Embedder embedder) throws Exception;
    }

    private static void withEngine(KeyspacesConfig config, Settings settings, EngineTask task) throws Exception {
        try (Embedder embedder = Embedders.create(settings); VectorSink sink = Sinks.create(settings)) {
            CqlSession session = config.getOrCreateCqlSession();
            try {
                KeyspacesRows rows = new KeyspacesRows(session, settings.keyspace(), settings.table());
                task.accept(rows, new SyncEngine(settings.planner(), embedder, sink), sink, embedder);
            } finally {
                session.close();
            }
        }
    }

    private static void query(KeyspacesConfig config, Settings settings, Args args) throws Exception {
        String text = String.join(" ", args.positional);
        if (text.isBlank()) {
            throw new IllegalArgumentException("query needs some text");
        }
        Map<String, Object> filter = args.has("filter") ? Json.readMap(args.get("filter", "{}")) : null;
        try (Embedder embedder = Embedders.create(settings); VectorSink sink = Sinks.create(settings)) {
            CqlSession session = args.has("no-rows") ? null : config.getOrCreateCqlSession();
            try {
                KeyspacesRows rows = session == null ? null : new KeyspacesRows(session, settings.keyspace(), settings.table());
                for (KeyvectorQuery.Result r : new KeyvectorQuery(embedder, sink, rows).search(text, args.getInt("top-k", 10), filter)) {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("key", r.key());
                    line.put("distance", r.distance());
                    line.put("metadata", r.metadata());
                    if (r.row() != null) {
                        line.put("row", r.row());
                    }
                    System.out.println(Json.write(line));
                }
            } finally {
                if (session != null) {
                    session.close();
                }
            }
        }
    }

    private static Map<String, Object> summary(SyncEngine.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("embedded", r.embedded());
        out.put("reused", r.reused());
        out.put("deleted", r.deleted());
        out.put("unchanged", r.unchanged());
        out.put("rejected", r.rejected());
        return out;
    }

    private static void run(KeyspacesConfig config) throws Exception {
        KCLScheduler kcl = new KCLScheduler(config);
        String workerId = AWSHelpers.createWorkerIdFromSTS();
        Scheduler scheduler = kcl.createScheduler(workerId);
        logger.info("Starting Keyvector worker {} for {}.{}", workerId, config.getKeyspaceName(), config.getTableName());
        CompletableFuture<Void> running = CompletableFuture.runAsync(scheduler::run);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Ask KCL to stop and checkpoint; processors mid-retry stop without checkpointing.
            // Bounded: while KCL is still starting (it waits for its lease table's index,
            // which can take minutes) a graceful shutdown never completes, and an unbounded
            // wait left the process unkillable except by SIGKILL. Nothing is lost by giving
            // up: an unacknowledged batch is simply read again by the next worker.
            try {
                scheduler.startGracefulShutdown().get(SHUTDOWN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Graceful shutdown did not finish within {}s ({}); stopping anyway",
                    SHUTDOWN_TIMEOUT_SECONDS, e.toString());
                scheduler.shutdown();
            }
        }));
        try {
            HealthServer.start();
        } catch (Exception e) {
            // A health endpoint that cannot bind must not take the worker down with it.
            logger.error("Health endpoint did not start ({}); the worker keeps running", e.toString());
        }
        running.join();
    }

    /** --name value, --flag, and positional arguments. */
    static final class Args {
        String command;
        final Map<String, String> options = new LinkedHashMap<>();
        final List<String> positional = new ArrayList<>();

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String s = argv[i];
                if (s.startsWith("--")) {
                    String name = s.substring(2);
                    int eq = name.indexOf('=');
                    if (eq >= 0) {
                        a.options.put(name.substring(0, eq), name.substring(eq + 1));
                    } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--") && takesValue(name)) {
                        a.options.put(name, argv[++i]);
                    } else {
                        a.options.put(name, "true");
                    }
                } else if (a.command == null) {
                    a.command = s;
                } else {
                    a.positional.add(s);
                }
            }
            return a;
        }

        private static boolean takesValue(String name) {
            return List.of("config", "page-size", "batch-size", "limit", "queue-url", "top-k", "filter").contains(name);
        }

        boolean has(String name) {
            return options.containsKey(name);
        }

        String get(String name, String def) {
            return options.getOrDefault(name, def);
        }

        int getInt(String name, int def) {
            return has(name) ? Integer.parseInt(options.get(name)) : def;
        }

        long getLong(String name, long def) {
            return has(name) ? Long.parseLong(options.get(name)) : def;
        }
    }
}
