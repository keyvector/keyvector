package dev.keyvector.connector.embed.local;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A model's files on disk: either a directory you provide (baked into an image, so nothing is
 * downloaded in production), or files fetched once from the Hugging Face Hub and cached.
 *
 * <p>Pin {@code revision} to a commit hash in production. A branch name like {@code main} means
 * the model you embed with can change under you, and vectors from two versions of a model
 * aren't comparable.
 */
public final class ModelFiles {

    private static final Logger logger = LoggerFactory.getLogger(ModelFiles.class);
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*(/[A-Za-z0-9_][A-Za-z0-9._-]*)*");

    private final Path dir;
    private final String repo;
    private final String revision;
    private final String endpoint;

    private ModelFiles(Path dir, String repo, String revision, String endpoint) {
        this.dir = dir;
        this.repo = repo;
        this.revision = revision;
        this.endpoint = endpoint;
    }

    /** Files already on disk. Nothing is downloaded. */
    public static ModelFiles local(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("embedder.model-path " + dir + " is not a directory");
        }
        return new ModelFiles(dir, null, null, null);
    }

    /** Files from the Hugging Face Hub, cached under {@code cacheRoot/<repo>/<revision>}. */
    public static ModelFiles hub(String repo, String revision, Path cacheRoot) {
        if (repo == null || !REPO.matcher(repo).matches() || repo.contains("..")) {
            throw new IllegalArgumentException("embedder.model must be a Hugging Face repo id like sentence-transformers/all-MiniLM-L6-v2; got " + repo);
        }
        if (!REVISION.matcher(revision).matches() || revision.contains("..")) {
            throw new IllegalArgumentException("embedder.model-revision is not a valid revision: " + revision);
        }
        String endpoint = System.getenv().getOrDefault("HF_ENDPOINT", "https://huggingface.co").replaceAll("/+$", "");
        if (!endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("HF_ENDPOINT must be https");
        }
        return new ModelFiles(cacheRoot.resolve(repo).resolve(revision), repo, revision, endpoint);
    }

    public static Path defaultCacheRoot() {
        String env = System.getenv("KEYVECTOR_MODEL_CACHE");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".cache", "keyvector", "models");
    }

    public String describe() {
        return repo == null ? dir.toString() : repo + "@" + revision;
    }

    /** The file's path, downloading it first if needed; null if the model doesn't have it. */
    public Path optional(String name) throws IOException, InterruptedException {
        if (!FILE.matcher(name).matches() || name.contains("..")) {
            throw new IllegalArgumentException("Not a model file name: " + name);
        }
        Path p = dir.resolve(name).normalize();
        if (!p.startsWith(dir)) {
            throw new IllegalArgumentException("Not a model file name: " + name);
        }
        if (Files.isRegularFile(p)) {
            return p;
        }
        if (repo == null) {
            return null;
        }
        return download(name, p) ? p : null;
    }

    public Path required(String name) throws IOException, InterruptedException {
        Path p = optional(name);
        if (p == null) {
            throw new IOException(describe() + " has no " + name);
        }
        return p;
    }

    private boolean download(String name, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint + "/" + repo + "/resolve/" + revision + "/" + name))
            .timeout(Duration.ofMinutes(10))
            .header("User-Agent", "keyvector");
        String token = System.getenv("HF_TOKEN");
        if (token != null && !token.isBlank()) {
            req.header("Authorization", "Bearer " + token);
        }
        HttpResponse<InputStream> res = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = res.body()) {
            if (res.statusCode() == 404) {
                return false;
            }
            if (res.statusCode() / 100 != 2) {
                throw new IOException("Downloading " + name + " from " + describe() + " failed: HTTP " + res.statusCode());
            }
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".download-", ".part");
            try {
                Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        logger.info("Downloaded {} from {} ({} bytes)", name, describe(), Files.size(target));
        return true;
    }
}
