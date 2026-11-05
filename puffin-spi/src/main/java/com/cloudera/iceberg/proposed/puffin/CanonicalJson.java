package com.cloudera.iceberg.proposed.puffin;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The JSON Canonicalization Scheme of RFC 8785: object members sorted by the UTF-16 code units of their names, no
 * insignificant whitespace, strings escaped minimally, and numbers serialized as ECMAScript serializes IEEE 754 doubles.
 *
 * <p>Two deliberate restrictions, both of which reject input rather than canonicalize it silently: integers that a
 * double cannot represent exactly (beyond 2^53) and strings containing unpaired surrogates.
 */
final class CanonicalJson {
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final BigInteger MAX_EXACT = BigInteger.ONE.shiftLeft(53);

  private CanonicalJson() {}

  static ObjectMapper mapper() {
    return MAPPER;
  }

  static JsonNode parse(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage(), e);
    }
  }

  static String write(JsonNode node) {
    StringBuilder out = new StringBuilder();
    write(node, out);
    return out.toString();
  }

  private static void write(JsonNode node, StringBuilder out) {
    switch (node.getNodeType()) {
      case OBJECT -> {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names); // String.compareTo orders by UTF-16 code units, as RFC 8785 requires
        out.append('{');
        for (int i = 0; i < names.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          writeString(names.get(i), out);
          out.append(':');
          write(node.get(names.get(i)), out);
        }
        out.append('}');
      }
      case ARRAY -> {
        out.append('[');
        for (int i = 0; i < node.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          write(node.get(i), out);
        }
        out.append(']');
      }
      case STRING -> writeString(node.textValue(), out);
      case NUMBER -> out.append(number(node));
      case BOOLEAN -> out.append(node.booleanValue());
      case NULL -> out.append("null");
      default -> throw new IllegalArgumentException("not representable in JSON: " + node.getNodeType());
    }
  }

  private static String number(JsonNode node) {
    if (node.isIntegralNumber() && node.bigIntegerValue().abs().compareTo(MAX_EXACT) > 0) {
      throw new IllegalArgumentException("integer not exactly representable as a double: " + node.asText());
    }
    return formatDouble(node.doubleValue());
  }

  /** ECMAScript Number::toString, which RFC 8785 adopts for serializing numbers. */
  static String formatDouble(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new IllegalArgumentException("not representable in JSON: " + d);
    }
    if (d == 0) {
      return "0"; // including -0
    }
    BigDecimal shortest = shortestDecimal(Math.abs(d));
    String digits = shortest.unscaledValue().toString();
    int k = digits.length();
    int n = k - shortest.scale(); // the value is 0.digits * 10^n
    StringBuilder out = new StringBuilder();
    if (d < 0) {
      out.append('-');
    }
    if (k <= n && n <= 21) {
      out.append(digits).append("0".repeat(n - k));
    } else if (0 < n && n <= 21) {
      out.append(digits, 0, n).append('.').append(digits, n, k);
    } else if (-6 < n && n <= 0) {
      out.append("0.").append("0".repeat(-n)).append(digits);
    } else {
      int e = n - 1;
      out.append(digits.charAt(0));
      if (k > 1) {
        out.append('.').append(digits, 1, k);
      }
      out.append('e').append(e < 0 ? '-' : '+').append(Math.abs(e));
    }
    return out.toString();
  }

  /**
   * The decimal with the fewest significant digits that rounds to {@code d}, the closest such if there are several.
   * Since JDK 19 {@link Double#toString} computes exactly that, except that it never uses fewer than two digits: where
   * one would do it prints the closest two-digit decimal instead (4.9E-324, not 5E-324), so that case is redone here.
   */
  private static BigDecimal shortestDecimal(double d) {
    BigDecimal fromJdk = new BigDecimal(Double.toString(d)).stripTrailingZeros();
    if (fromJdk.precision() != 2) {
      return fromJdk;
    }
    BigDecimal exact = new BigDecimal(d);
    BigDecimal best = null;
    for (BigDecimal candidate :
        new BigDecimal[] {
          fromJdk.round(new MathContext(1, RoundingMode.FLOOR)),
          fromJdk.round(new MathContext(1, RoundingMode.CEILING))
        }) {
      if (candidate.doubleValue() == d
          && (best == null || candidate.subtract(exact).abs().compareTo(best.subtract(exact).abs()) < 0)) {
        best = candidate;
      }
    }
    return best == null ? fromJdk : best.stripTrailingZeros();
  }

  private static void writeString(String s, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
        out.append(c).append(s.charAt(++i));
        continue;
      }
      if (Character.isSurrogate(c)) {
        throw new IllegalArgumentException("unpaired surrogate in JSON string");
      }
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
