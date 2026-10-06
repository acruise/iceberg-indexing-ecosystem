package progidx.puffin

import org.apache.iceberg.puffin.{Blob, BlobMetadata, PuffinCompressionCodec}

import scala.jdk.CollectionConverters._

/** The convention for index blobs that belong to one data file rather than to a snapshot.
  *
  * It follows what Iceberg does for deletion vectors: snapshot id and sequence number are -1 (the blob is valid for as
  * long as its data file is live, in whatever snapshots contain it), and the `referenced-data-file` property holds the
  * data file's location. The blob's input field is the indexed column's field id.
  */
object DataFileIndexBlobs {
  /** The property key Iceberg's deletion-vector writer uses for the same purpose. */
  val ReferencedDataFile = "referenced-data-file"

  /** Iceberg 1.12.0's Puffin writer implements zstd but not LZ4 for blob compression. */
  def blob[T](
      handlers: PuffinBlobHandlers,
      value: T,
      valueClass: Class[T],
      dataFileLocation: String,
      fieldId: Int,
      compression: Option[PuffinCompressionCodec] = None,
      properties: Map[String, String] = Map.empty
  ): Blob = {
    val handler = handlers.handlerFor(valueClass)
    new Blob(
      handler.blobType,
      List(Int.box(fieldId)).asJava,
      -1L,
      -1L,
      handler.encode(value),
      compression.orNull,
      (properties + (ReferencedDataFile -> dataFileLocation)).asJava
    )
  }

  def referencedDataFile(metadata: BlobMetadata): Option[String] =
    Option(metadata.properties()).flatMap(p => Option(p.get(ReferencedDataFile)))

  def fieldId(metadata: BlobMetadata): Option[Int] = metadata.inputFields().asScala.headOption.map(_.intValue)
}
