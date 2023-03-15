name := "cats-layer"

scalaVersion := "2.13.10"

scalacOptions := Seq(
  "-Xsource:3"
)

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "3.4.8",
  "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "dev.zio" %% "izumi-reflect" % "2.3.0",
  "org.scalameta" %% "munit" % "1.0.0-M7" % Test,
  "org.hamcrest" % "hamcrest" % "2.2" % Test
)
