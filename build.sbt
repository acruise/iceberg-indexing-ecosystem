scalaVersion := "2.13.18"

lazy val root = rootProject
  .settings(
    name := "progressive-indexing",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Xlint", "-release:21"),
    javacOptions ++= Seq("--release", "21"),
    libraryDependencies ++= Seq(
      "org.apache.iceberg" % "iceberg-core" % "1.12.0",
      "org.scalameta" %% "munit" % "1.2.3" % Test
    )
  )
