package progidx.puffin

import org.apache.iceberg.puffin.{BlobMetadata, PuffinReader}

import java.nio.ByteBuffer
import java.util.ServiceLoader
import scala.jdk.CollectionConverters._

/** The set of Puffin blob types this process understands, by blob type. */
final class PuffinBlobHandlers private (byType: Map[String, PuffinBlobHandler[_]]) {
  def blobTypes: Set[String] = byType.keySet

  def handler(blobType: String): Option[PuffinBlobHandler[_]] = byType.get(blobType)

  /** The one handler whose decoded representation is `cls`. */
  def handlerFor[T](cls: Class[T]): PuffinBlobHandler[T] =
    byType.values.filter(_.valueClass == cls).toList match {
      case h :: Nil => h.asInstanceOf[PuffinBlobHandler[T]]
      case Nil      => throw new IllegalArgumentException(s"no Puffin blob handler for ${cls.getName}")
      case hs       => throw new IllegalArgumentException(s"several Puffin blob handlers for ${cls.getName}: ${hs.map(_.blobType)}")
    }

  /** The decoded blob, or None if no handler knows its type: Puffin readers ignore blob types they don't understand. */
  def decode(metadata: BlobMetadata, data: ByteBuffer): Option[Any] =
    handler(metadata.`type`).map(_.decode(metadata, data))

  /** Decodes every blob in the file whose type is known, skipping (and not even reading) the rest. */
  def readKnown(reader: PuffinReader): Seq[(BlobMetadata, Any)] = {
    val known = reader.fileMetadata().blobs().asScala.filter(b => byType.contains(b.`type`)).toList
    reader.readAll(known.asJava).asScala.toList.map { pair =>
      pair.first() -> byType(pair.first().`type`).decode(pair.first(), pair.second())
    }
  }
}

object PuffinBlobHandlers {
  /** Handlers registered with `ServiceLoader` and visible to the context class loader. */
  def load(): PuffinBlobHandlers = load(Thread.currentThread().getContextClassLoader)

  def load(classLoader: ClassLoader): PuffinBlobHandlers =
    of(ServiceLoader.load(classOf[PuffinBlobHandler[_]], classLoader).asScala.toSeq: _*)

  /** Two handlers claiming the same blob type is a configuration error, not something to resolve silently. */
  def of(handlers: PuffinBlobHandler[_]*): PuffinBlobHandlers = {
    val duplicates = handlers.groupBy(_.blobType).collect {
      case (t, hs) if hs.size > 1 => s"$t: ${hs.map(_.getClass.getName).mkString(", ")}"
    }
    require(duplicates.isEmpty, s"several Puffin blob handlers claim the same blob type: ${duplicates.mkString("; ")}")
    new PuffinBlobHandlers(handlers.map(h => h.blobType -> h).toMap)
  }
}
