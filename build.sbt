// Bare settings apply to every subproject under sbt 2.
scalaVersion := "2.13.18"
organization := "com.cloudera.acruise"
version := "0.1.0-SNAPSHOT"
scalacOptions ++= Seq("-deprecation", "-feature", "-Xlint", "-release:21")
javacOptions ++= Seq("--release", "21", "-Xlint:all")
Compile / doc / javacOptions --= Seq("-Xlint:all") // javadoc rejects it
Test / fork := true
libraryDependencies ++= Seq(
  "org.scalameta" %% "munit" % "1.2.3" % Test,
  "com.github.sbt.junit" % "jupiter-interface" % JupiterKeys.jupiterVersion.value % Test,
  "org.junit.jupiter" % "junit-jupiter-params" % "6.0.3" % Test,
  "org.assertj" % "assertj-core" % "3.27.7" % Test
)

// The proposed service-provider interface and the build-configuration machinery
// (com.cloudera.iceberg.proposed.puffin). Pure Java; downstream code depends on this.
lazy val puffinSpi = project
  .in(file("puffin-spi"))
  .settings(
    name := "iceberg-indexing-puffin-spi",
    crossPaths := false, // a Java library, so no Scala-version suffix on the artifact
    autoScalaLibrary := false,
    libraryDependencies += "org.apache.iceberg" % "iceberg-core" % "1.12.0"
  )

// Reference blob types built against the SPI: the f16 presence filter and the n-gram Bloom filter.
lazy val blobTypes = project
  .in(file("blob-types"))
  .dependsOn(puffinSpi)
  .settings(
    name := "iceberg-indexing-blob-types",
    libraryDependencies += "com.ibm.icu" % "icu4j" % "78.3"
  )

// The progressive-indexing simulator. Not published.
lazy val simulator = project
  .in(file("simulator"))
  .dependsOn(blobTypes)
  .settings(
    name := "progressive-indexing-simulator",
    publish / skip := true,
    // Simulations hold a whole table in memory; keep them out of sbt's own heap.
    Test / javaOptions += "-Xmx2g",
    run / fork := true,
    run / javaOptions += "-Xmx16g" // seed sweeps run many simulations at once
  )

lazy val root = rootProject
  .aggregate(puffinSpi, blobTypes, simulator)
  .settings(
    name := "progressive-indexing",
    publish / skip := true
  )
