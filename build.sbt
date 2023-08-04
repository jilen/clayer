name := "cats-layer"

scalaVersion := "2.13.11"

scalacOptions := {
  val shared = Seq("-feature", "-deprecation", "-release:17")
  val byVersion =
    if (scalaVersion.value.startsWith("2.13"))
      Seq(
        "-Xsource:3"
      )
    else Seq()
  shared ++ byVersion
}

crossScalaVersions := Seq("2.13.11", "3.3.0")

def reflectDeps(v: String) = {
  if (v.startsWith("2.13"))
    Seq("org.scala-lang" % "scala-reflect" % v)
  else
    Seq()
}

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "3.5.1",
  "dev.zio" %% "izumi-reflect" % "2.3.8",
  "org.typelevel" %% "munit-cats-effect" % "2.0.0-M3" % Test,
  "org.hamcrest" % "hamcrest" % "2.2" % Test
) ++ reflectDeps(scalaVersion.value)
