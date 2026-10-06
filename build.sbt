scalaVersion := "2.13.18"

lazy val root = rootProject
  .settings(
    name := "progressive-indexing",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Xlint", "-release:21"),
    javacOptions ++= Seq("--release", "21", "-Xlint:all"),
    libraryDependencies ++= Seq(
      "org.apache.iceberg" % "iceberg-core" % "1.12.0",
      "com.ibm.icu" % "icu4j" % "78.3",
      "org.scalameta" %% "munit" % "1.2.3" % Test,
      "com.github.sbt.junit" % "jupiter-interface" % JupiterKeys.jupiterVersion.value % Test,
      "org.junit.jupiter" % "junit-jupiter-params" % "6.0.3" % Test,
      "org.assertj" % "assertj-core" % "3.27.7" % Test
    )
  )
