name := "clayer"

scalaVersion := "2.13.6"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "3.1.1",
  "dev.zio" %% "izumi-reflect" % "1.1.2"
)

addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.0" cross CrossVersion.full)
