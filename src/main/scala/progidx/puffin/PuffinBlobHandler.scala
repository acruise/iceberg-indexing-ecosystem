package progidx.puffin

import org.apache.iceberg.puffin.BlobMetadata

import java.nio.ByteBuffer

/** Encodes and decodes the payload of one Puffin blob type.
  *
  * Iceberg treats a Puffin blob as a type string plus opaque bytes and keeps no registry of blob types (as of 1.12.0),
  * so handlers are discovered with `java.util.ServiceLoader`: list implementations, one per line, in
  * `META-INF/services/progidx.puffin.PuffinBlobHandler`. See [[PuffinBlobHandlers]].
  *
  * Implementations need a public no-argument constructor and must be thread-safe. This trait has only abstract
  * members, so it compiles to a plain Java interface and can be implemented from Java.
  */
trait PuffinBlobHandler[T] {
  /** The blob `type` written in the Puffin footer, e.g. `progidx-f16-presence-v1`. At most one handler per type. */
  def blobType: String

  /** The decoded representation. */
  def valueClass: Class[T]

  def encode(value: T): ByteBuffer

  /** `data` is the decompressed payload; `metadata` gives access to the blob's fields and properties. */
  def decode(metadata: BlobMetadata, data: ByteBuffer): T
}
