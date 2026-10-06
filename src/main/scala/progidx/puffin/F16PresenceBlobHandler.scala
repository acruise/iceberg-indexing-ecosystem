package progidx.puffin

import org.apache.iceberg.puffin.BlobMetadata
import progidx.index.F16Presence

import java.nio.ByteBuffer

/** Puffin blobs of type `progidx-f16-presence-v1`: the payload is `F16Presence.toBytes`. */
final class F16PresenceBlobHandler extends PuffinBlobHandler[F16Presence] {
  def blobType: String = F16PresenceBlobHandler.BlobType
  def valueClass: Class[F16Presence] = classOf[F16Presence]

  def encode(value: F16Presence): ByteBuffer = ByteBuffer.wrap(value.toBytes)

  def decode(metadata: BlobMetadata, data: ByteBuffer): F16Presence = {
    val bytes = new Array[Byte](data.remaining)
    data.duplicate().get(bytes)
    F16Presence.fromBytes(bytes)
  }
}

object F16PresenceBlobHandler {
  val BlobType = "progidx-f16-presence-v1"
}
