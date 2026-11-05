package com.cloudera.iceberg.proposed.puffin;

/**
 * A blob's payload or properties do not conform to its type's definition.
 *
 * <p>Readers treat such a blob as absent: it costs index coverage, never correctness. The exception is unchecked so
 * that implementations of {@link PuffinBlobType#deserialize} can throw it from parsing code without declaring it, and it
 * deliberately does not extend {@link IllegalArgumentException}, which this package reserves for caller errors.
 */
public class MalformedBlobException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public MalformedBlobException(String message) {
    super(message);
  }

  public MalformedBlobException(String message, Throwable cause) {
    super(message, cause);
  }
}
