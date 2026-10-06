package com.cloudera.iceberg.proposed.puffin;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The blob type identifier syntax of Section 4.1: {@code [namespace "."] name "-v" major}. */
public final class BlobTypeIdentifiers {
  private static final Pattern SYNTAX =
      Pattern.compile("(?:([a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)*)\\.)?([a-z0-9]+(?:-[a-z0-9]+)*)-v([1-9][0-9]*)");

  private BlobTypeIdentifiers() {}

  public static boolean isValid(String type) {
    return parse(type).isPresent();
  }

  /** Identifiers without a namespace are reserved for types defined by the Iceberg specification. */
  public static boolean isNamespaced(String type) {
    return namespace(type).isPresent();
  }

  /** Everything before the final dot, since a name cannot contain one. Empty for un-namespaced identifiers. */
  public static Optional<String> namespace(String type) {
    return parse(type).flatMap(m -> Optional.ofNullable(m.group(1)));
  }

  public static int majorVersion(String type) {
    return Integer.parseInt(parse(type).orElseThrow(() -> invalid(type)).group(3));
  }

  public static String requireValid(String type) {
    parse(type).orElseThrow(() -> invalid(type));
    return type;
  }

  private static Optional<Matcher> parse(String type) {
    if (type == null) {
      return Optional.empty();
    }
    Matcher m = SYNTAX.matcher(type);
    return m.matches() ? Optional.of(m) : Optional.empty();
  }

  private static IllegalArgumentException invalid(String type) {
    return new IllegalArgumentException("not a valid blob type identifier: " + type);
  }
}
