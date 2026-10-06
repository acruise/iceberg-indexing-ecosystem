package com.cloudera.acruise.progidx.f16presence

import org.apache.iceberg.puffin.BlobMetadata
import com.cloudera.iceberg.proposed.puffin.MergeablePuffinBlobType.MergeContext
import com.cloudera.iceberg.proposed.puffin.{BlobTypeDescriptor, BuildConfig, MalformedBlobException, MergeablePuffinBlobType}

import java.nio.{BufferUnderflowException, ByteBuffer}
import java.util.Optional
import scala.jdk.CollectionConverters._

/** Puffin blobs of type `com.example.f16-presence-v1`, the descriptor of proposal Section 5. The payload is
  * `F16Presence.toBytes`; the type has no configurable parameters, so blobs carry no configuration reference.
  */
final class F16PresenceBlobType extends MergeablePuffinBlobType[F16Presence] {
  def descriptor: BlobTypeDescriptor = F16PresenceBlobType.Descriptor
  def javaClass: Class[F16Presence] = classOf[F16Presence]

  def serialize(value: F16Presence): ByteBuffer = ByteBuffer.wrap(value.toBytes)

  def deserialize(metadata: BlobMetadata, data: ByteBuffer, config: Optional[BuildConfig]): F16Presence = {
    val bytes = new Array[Byte](data.remaining)
    data.duplicate().get(bytes)
    try F16Presence.fromBytes(bytes)
    catch {
      case e @ (_: IllegalArgumentException | _: BufferUnderflowException | _: NegativeArraySizeException) =>
        throw new MalformedBlobException(s"malformed ${F16PresenceBlobType.Type} payload: ${e.getMessage}", e)
    }
  }

  def merge(sources: java.util.List[F16Presence], context: MergeContext): F16Presence = {
    require(!sources.isEmpty, "nothing to merge")
    sources.asScala.reduce(_ or _)
  }
}

object F16PresenceBlobType {
  val Type = "com.example.f16-presence-v1"

  val Descriptor: BlobTypeDescriptor = BlobTypeDescriptor
    .builder(Type)
    .title("Half-precision presence filter")
    .definition("https://example.com/specs/f16-presence-v1")
    .scope(BlobTypeDescriptor.Scope.DATA_FILE)
    .inputFields(1, "float", "double", "int", "long", "decimal")
    .property("distinct-codes", false, "non-negative integer")
    .merge(BlobTypeDescriptor.MergePrecision.EXACT)
    .deletes(BlobTypeDescriptor.Deletes.CONSERVATIVE)
    .compression("none", "zstd")
    .config(new BlobTypeDescriptor.ConfigSpec(Optional.empty(), java.util.List.of(), java.util.List.of()))
    .build()
}
