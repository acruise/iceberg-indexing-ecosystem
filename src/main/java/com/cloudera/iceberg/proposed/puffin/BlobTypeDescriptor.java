package com.cloudera.iceberg.proposed.puffin;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** What engines and maintenance tools may know about a blob type without decoding its payloads (Section 5). */
public record BlobTypeDescriptor(
    String type,
    String title,
    URI definition,
    Scope scope,
    InputFields inputFields,
    Map<String, PropertySpec> properties,
    Compaction compaction,
    Optional<MergePrecision> mergePrecision,
    Deletes deletes,
    List<String> compression,
    ConfigSpec config) {

  public enum Scope {
    SNAPSHOT("snapshot"),
    DATA_FILE("data-file"),
    DELETE_FILE("delete-file"),
    /** Valid for as long as the Puffin file that contains it, e.g. an in-band build configuration. */
    PUFFIN_FILE("puffin-file");

    private final String json;

    Scope(String json) {
      this.json = json;
    }

    public String json() {
      return json;
    }
  }

  /** Section 8.3. */
  public enum Compaction {
    DROP,
    REBUILD,
    MERGE,
    RETAIN;

    public String json() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  public enum MergePrecision {
    EXACT,
    CONSERVATIVE;

    public String json() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  public enum Deletes {
    CONSERVATIVE,
    APPROXIMATE,
    INVALIDATED;

    public String json() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /** How many field ids a blob records, and optionally which Iceberg primitive types they may have. */
  public record InputFields(int count, List<String> types) {
    public InputFields {
      types = List.copyOf(types);
    }
  }

  public record PropertySpec(boolean required, String syntax) {}

  /**
   * Section 7.5. {@code schema} is the URL of the JSON Schema for the type's configuration documents and
   * {@code schemaDigest} the digest of that document's canonical form, which prefixes every configuration digest under
   * the type (Section 7.2). {@code evaluation} lists the configuration keys readers need in order to evaluate
   * predicates; {@code composition} lists the keys that must agree for blobs to be merged, or is exactly
   * {@code ["digest"]} when only identical configurations will do.
   */
  public record ConfigSpec(
      Optional<URI> schema, Optional<SchemaDigest> schemaDigest, List<String> evaluation, List<String> composition) {
    public static final ConfigSpec NONE = new ConfigSpec(Optional.empty(), Optional.empty(), List.of(), List.of());
    public static final String DIGEST = "digest";

    public ConfigSpec {
      Objects.requireNonNull(schema, "schema");
      Objects.requireNonNull(schemaDigest, "schemaDigest");
      evaluation = List.copyOf(evaluation);
      composition = List.copyOf(composition);
      if (schema.isPresent() != schemaDigest.isPresent()) {
        throw new IllegalArgumentException("schema and schema-digest are declared together");
      }
      if (schema.isEmpty() && !(evaluation.isEmpty() && composition.isEmpty())) {
        throw new IllegalArgumentException("a type with configuration parameters must declare their schema");
      }
      if (composition.contains(DIGEST) && composition.size() > 1) {
        throw new IllegalArgumentException("composition is either a list of keys or [\"digest\"], not both");
      }
    }

    public static ConfigSpec of(String schema, SchemaDigest schemaDigest, List<String> evaluation, List<String> composition) {
      return new ConfigSpec(Optional.of(URI.create(schema)), Optional.of(schemaDigest), evaluation, composition);
    }

    /** Whether every blob of the type must carry a configuration reference that readers resolve. */
    public boolean requiresConfig() {
      return !evaluation.isEmpty();
    }

    /** Whether blobs built under these configurations may be composed; an absent configuration composes only when no keys must agree. */
    public boolean composable(List<Optional<BuildConfig>> configs) {
      if (composition.isEmpty() || configs.size() < 2) {
        return true;
      }
      if (configs.stream().anyMatch(Optional::isEmpty)) {
        return false;
      }
      BuildConfig first = configs.get(0).get();
      for (Optional<BuildConfig> c : configs.subList(1, configs.size())) {
        BuildConfig other = c.get();
        boolean agree =
            composition.equals(List.of(DIGEST))
                ? first.equals(other)
                : composition.stream().allMatch(k -> first.agreesOn(other, k));
        if (!agree) {
          return false;
        }
      }
      return true;
    }
  }

  public BlobTypeDescriptor {
    BlobTypeIdentifiers.requireValid(type);
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(inputFields, "inputFields");
    properties = Map.copyOf(properties);
    Objects.requireNonNull(compaction, "compaction");
    Objects.requireNonNull(deletes, "deletes");
    compression = List.copyOf(compression);
    Objects.requireNonNull(config, "config");
    if ((compaction == Compaction.MERGE) != mergePrecision.isPresent()) {
      throw new IllegalArgumentException("merge-precision is declared exactly when compaction is merge: " + type);
    }
    if (compaction == Compaction.RETAIN && scope == Scope.DATA_FILE) {
      throw new IllegalArgumentException("retain does not apply to data-file-scoped blobs: " + type);
    }
  }

  public static Builder builder(String type) {
    return new Builder(type);
  }

  /** The JSON form published with the type's definition and listed in the registry. */
  public ObjectNode toJson() {
    JsonNodeFactory f = JsonNodeFactory.instance;
    ObjectNode out = f.objectNode();
    out.put("type", type);
    out.put("title", title);
    out.put("definition", definition.toString());
    out.put("scope", scope.json());
    ObjectNode fields = out.putObject("input-fields");
    fields.put("count", inputFields.count());
    if (!inputFields.types().isEmpty()) {
      ArrayNode types = fields.putArray("types");
      inputFields.types().forEach(types::add);
    }
    ObjectNode props = out.putObject("properties");
    properties.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> props.putObject(e.getKey()).put("required", e.getValue().required()).put("syntax", e.getValue().syntax()));
    out.put("compaction", compaction.json());
    mergePrecision.ifPresent(p -> out.put("merge-precision", p.json()));
    out.put("deletes", deletes.json());
    ArrayNode codecs = out.putArray("compression");
    compression.forEach(codecs::add);
    if (!config.equals(ConfigSpec.NONE)) {
      ObjectNode c = out.putObject("config");
      config.schema().ifPresent(s -> c.put("schema", s.toString()));
      config.schemaDigest().ifPresent(s -> c.put("schema-digest", s.toString()));
      config.evaluation().forEach(c.putArray("evaluation")::add);
      config.composition().forEach(c.putArray("composition")::add);
    }
    return out;
  }

  public static final class Builder {
    private final String type;
    private String title;
    private URI definition;
    private Scope scope;
    private InputFields inputFields = new InputFields(0, List.of());
    private final Map<String, PropertySpec> properties = new LinkedHashMap<>();
    private Compaction compaction = Compaction.DROP;
    private Optional<MergePrecision> mergePrecision = Optional.empty();
    private Deletes deletes;
    private List<String> compression = List.of("none");
    private ConfigSpec config = ConfigSpec.NONE;

    private Builder(String type) {
      this.type = type;
    }

    public Builder title(String v) {
      title = v;
      return this;
    }

    public Builder definition(String v) {
      definition = URI.create(v);
      return this;
    }

    public Builder scope(Scope v) {
      scope = v;
      return this;
    }

    public Builder inputFields(int count, String... types) {
      inputFields = new InputFields(count, List.of(types));
      return this;
    }

    public Builder property(String key, boolean required, String syntax) {
      properties.put(key, new PropertySpec(required, syntax));
      return this;
    }

    public Builder compaction(Compaction v) {
      compaction = v;
      return this;
    }

    public Builder merge(MergePrecision precision) {
      compaction = Compaction.MERGE;
      mergePrecision = Optional.of(precision);
      return this;
    }

    public Builder deletes(Deletes v) {
      deletes = v;
      return this;
    }

    public Builder compression(String... codecs) {
      compression = List.of(codecs);
      return this;
    }

    public Builder config(ConfigSpec v) {
      config = v;
      return this;
    }

    public BlobTypeDescriptor build() {
      return new BlobTypeDescriptor(
          type, title, definition, scope, inputFields, properties, compaction, mergePrecision, deletes, compression, config);
    }
  }
}
