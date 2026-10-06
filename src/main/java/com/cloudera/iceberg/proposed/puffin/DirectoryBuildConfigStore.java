package com.cloudera.iceberg.proposed.puffin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An out-of-band store in a local directory, standing in for an object-store prefix or a service:
 *
 * <pre>
 *   by-digest/sha-256/&lt;hex&gt;.json   the canonical document
 *   by-name/&lt;id&gt;/&lt;version&gt;        the digest published under that name, written once
 * </pre>
 *
 * Table property: {@code puffin.config-store.<name>.path}.
 */
public final class DirectoryBuildConfigStore implements BuildConfigStore {
  private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*");
  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private Path root;

  public DirectoryBuildConfigStore() {}

  public DirectoryBuildConfigStore(Path root) {
    this.root = root;
  }

  @Override
  public void initialize(String name, Map<String, String> properties) {
    String path = properties.get("path");
    if (path == null) {
      throw new IllegalArgumentException("configuration store " + name + " needs a path property");
    }
    root = Path.of(path);
  }

  /** Publishes a document by digest. Idempotent, since the content determines the file. */
  public void publish(BuildConfig config) {
    Path file = digestPath(config.digest());
    if (Files.exists(file)) {
      return;
    }
    try {
      Files.createDirectories(file.getParent());
      Path tmp = Files.createTempFile(file.getParent(), "publish", ".tmp");
      Files.write(tmp, config.canonicalBytes());
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Publishes a document and names it.
   *
   * @throws IllegalStateException if the name and version already refer to a different document
   */
  public void publish(BuildConfig config, String configId, String configVersion) {
    Path name = namePath(configId, configVersion);
    publish(config);
    try {
      Files.createDirectories(name.getParent());
      Files.writeString(name, config.digest(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
    } catch (FileAlreadyExistsException e) {
      String existing = read(name).orElse("");
      if (!existing.equals(config.digest())) {
        throw new IllegalStateException(
            configId + " version " + configVersion + " is already published as " + existing, e);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Optional<BuildConfig> resolve(String digest) {
    if (!BuildConfig.isDigest(digest)) {
      return Optional.empty();
    }
    return read(digestPath(digest)).map(BuildConfig::parse);
  }

  @Override
  public Optional<BuildConfig> resolve(String configId, String configVersion) {
    if (!ID.matcher(configId).matches() || !VERSION.matcher(configVersion).matches()) {
      return Optional.empty();
    }
    return read(namePath(configId, configVersion)).flatMap(this::resolve);
  }

  private Path digestPath(String digest) {
    String hex = digest.substring(digest.indexOf(':') + 1);
    return root.resolve("by-digest").resolve(BuildConfig.DIGEST_ALGORITHM).resolve(hex + ".json");
  }

  private Path namePath(String configId, String configVersion) {
    if (!ID.matcher(configId).matches()) {
      throw new IllegalArgumentException("not a valid configuration id: " + configId);
    }
    if (!VERSION.matcher(configVersion).matches()) {
      throw new IllegalArgumentException("not a valid configuration version: " + configVersion);
    }
    return root.resolve("by-name").resolve(configId).resolve(configVersion);
  }

  private static Optional<String> read(Path file) {
    try {
      return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
