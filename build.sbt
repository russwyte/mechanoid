import xerial.sbt.Sonatype.sonatypeCentralHost

val scala3Version = "3.7.4"
val zioVersion    = "2.1.24"

usePgpKeyHex("2F64727A87F1BCF42FD307DD8582C4F16659A7D6")

lazy val root = project
  .in(file("."))
  .settings(
    name                 := "mechanoid",
    description          := "A type-safe, effect-oriented finite state machine library for Scala built on ZIO",
    licenses             := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    homepage             := Some(url("https://github.com/russwyte/mechanoid")),
    scalaVersion         := scala3Version,
    organization         := "io.github.russwyte",
    organizationName     := "russwyte",
    organizationHomepage := Some(url("https://github.com/russwyte")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/russwyte/mechanoid"),
        "scm:git@github.com:russwyte/mechanoid.git"
      )
    ),
    developers := List(
      Developer(
        id = "russwyte",
        name = "Russ White",
        email = "356303+russwyte@users.noreply.github.com",
        url = url("https://github.com/russwyte")
      )
    ),
    publishMavenStyle      := true,
    pomIncludeRepository   := { _ => false },
    sonatypeCredentialHost := sonatypeCentralHost,
    publishTo              := sonatypePublishToBundle.value,
    versionScheme          := Some("early-semver"),
    libraryDependencies ++= Seq(
      "io.github.russwyte"  %% "saferis"            % "0.1.1",
      "dev.zio"             %% "zio"                % zioVersion,
      "dev.zio"             %% "zio-streams"        % zioVersion,
      "dev.zio"             %% "zio-logging"        % "2.5.2",
      "dev.zio"             %% "zio-logging-slf4j"  % "2.5.2",
      "dev.zio"             %% "zio-json"           % "0.7.42",
      "dev.zio"             %% "zio-test"           % zioVersion % Test,
      "dev.zio"             %% "zio-test-sbt"       % zioVersion % Test,
      "dev.zio"             %% "zio-test-magnolia"  % zioVersion % Test,
      "org.testcontainers"   % "postgresql"         % "1.21.4"   % Test,
      "org.postgresql"       % "postgresql"         % "42.7.8"   % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-Wunused:all",
      "-feature"
    ),
    scalafixDependencies += "com.github.vovapolu" %% "scaluzzi" % "0.1.23"
  )
