val Fs2Version = "3.0.0-M9"
lazy val Fs2 = Seq(
  "co.fs2" %% "fs2-core",
  "co.fs2" %% "fs2-io",
).map(_%Fs2Version)
lazy val PureConfig =  "com.github.pureconfig" %% "pureconfig" % "0.14.1"

lazy val app = (project in file(".")).settings(
  name := "cinvestav-ds-spawner",
  version := "0.1",
  scalaVersion := "2.13.5",
  libraryDependencies ++= Seq(PureConfig) ++Fs2,
  dockerRepository := Some("nachocode"),
  packageName in Docker := "cinvestav-ds-spawner",
  version in Docker := "latest"
)
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
