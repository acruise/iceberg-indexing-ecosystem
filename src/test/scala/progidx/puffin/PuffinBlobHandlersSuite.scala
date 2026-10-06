package progidx.puffin

import org.apache.iceberg.Files
import org.apache.iceberg.puffin.{Blob, BlobMetadata, Puffin, PuffinCompressionCodec}
import progidx.index.F16Presence

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._
import scala.util.Using

class PuffinBlobHandlersSuite extends munit.FunSuite {
  private val tmp = FunFixture[java.io.File](
    setup = _ => java.nio.file.Files.createTempDirectory("puffin").toFile,
    teardown = dir => Option(dir.listFiles()).foreach(_.foreach(_.delete()))
  )

  test("ServiceLoader discovers the f16 handler") {
    val handlers = PuffinBlobHandlers.load()
    assert(handlers.blobTypes.contains(F16PresenceBlobHandler.BlobType))
    assertEquals(handlers.handlerFor(classOf[F16Presence]).blobType, F16PresenceBlobHandler.BlobType)
  }

  tmp.test("index blobs round-trip through a real Puffin file; unknown blob types are skipped") { dir =>
    val handlers = PuffinBlobHandlers.load()
    val temperature = F16Presence.of(Seq(19.5, 20.25, 31.0, Double.NaN))
    val dense = F16Presence.of((0 until 20000).map(_ * 0.01))
    val file = new java.io.File(dir, "indexes.puffin")

    Using.resource(Puffin.write(Files.localOutput(file)).createdBy("progidx test").build()) { writer =>
      writer.add(DataFileIndexBlobs.blob(handlers, temperature, classOf[F16Presence], "s3://lake/t/a.parquet", fieldId = 3))
      writer.add(
        new Blob("someone-elses-index-v7", List(Int.box(3)).asJava, 1L, 1L, ByteBuffer.wrap("opaque".getBytes(StandardCharsets.UTF_8)))
      )
      writer.add(
        DataFileIndexBlobs.blob(
          handlers,
          dense,
          classOf[F16Presence],
          "s3://lake/t/b.parquet",
          fieldId = 3,
          compression = Some(PuffinCompressionCodec.ZSTD)
        )
      )
    }

    val read = Using.resource(Puffin.read(Files.localInput(file)).build())(handlers.readKnown)
    assertEquals(read.size, 2)
    val byFile = read.map { case (md, value) => DataFileIndexBlobs.referencedDataFile(md).get -> (md, value) }.toMap
    assertEquals(byFile("s3://lake/t/a.parquet")._2, temperature)
    assertEquals(byFile("s3://lake/t/b.parquet")._2, dense)

    val md: BlobMetadata = byFile("s3://lake/t/b.parquet")._1
    assertEquals(md.`type`, F16PresenceBlobHandler.BlobType)
    assertEquals(md.snapshotId, -1L)
    assertEquals(md.sequenceNumber, -1L)
    assertEquals(DataFileIndexBlobs.fieldId(md), Some(3))
    assertEquals(md.compressionCodec, "zstd")
    assert(md.length < dense.sizeInBytes, s"compressed ${md.length} vs ${dense.sizeInBytes}")
  }

  test("two handlers claiming one blob type are rejected") {
    val err = intercept[IllegalArgumentException](PuffinBlobHandlers.of(new F16PresenceBlobHandler, new F16PresenceBlobHandler))
    assert(err.getMessage.contains(F16PresenceBlobHandler.BlobType))
  }

  test("asking for a representation nobody handles fails clearly") {
    val err = intercept[IllegalArgumentException](PuffinBlobHandlers.of().handlerFor(classOf[String]))
    assert(err.getMessage.contains("java.lang.String"))
  }
}
