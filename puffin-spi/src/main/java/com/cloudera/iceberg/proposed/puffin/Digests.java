package com.cloudera.iceberg.proposed.puffin;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** The one digest algorithm the prototype knows, and the {@code sha-256:<hex>} notation of Sections 7.2 and 7.5. */
final class Digests {
  static final String ALGORITHM = "sha-256";
  static final int LENGTH = 32;
  private static final Pattern SYNTAX = Pattern.compile("sha-256:[0-9a-f]{64}");

  private Digests() {}

  static boolean isDigest(String s) {
    return s != null && SYNTAX.matcher(s).matches();
  }

  /** SHA-256 over the concatenation of {@code parts}. */
  static byte[] sha256(byte[]... parts) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (byte[] p : parts) {
        md.update(p);
      }
      return md.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java platform must support SHA-256", e);
    }
  }

  static String format(byte[] digest) {
    return ALGORITHM + ":" + HexFormat.of().formatHex(digest);
  }

  /**
   * @throws IllegalArgumentException if {@code s} is not a supported digest
   */
  static byte[] parse(String s) {
    if (!isDigest(s)) {
      throw new IllegalArgumentException("not a supported digest: " + s);
    }
    return HexFormat.of().parseHex(s, ALGORITHM.length() + 1, s.length());
  }
}
