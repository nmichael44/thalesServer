val scalaVer = "3.7.4"

val catsVersion = "2.13.0"
val catsEffectVersion = "3.6.3"

val postgresVersion = "42.7.10"

val log4catsSlf4jVersion = "2.7.1"
val logbackVersion = "1.5.32"
val doobieVersion = "1.0.0-RC12"
val http4sVersion = "0.23.33"
val jsoniterScalaVersion = "2.38.9"
val scalatestVersion = "3.2.19"
val pureConfigCoreVersion = "0.17.10"
val catsEffectTestingScalatestVersion = "1.7.0"
val jwtVersion = "11.0.3"
val password4jVersion = "1.8.4"
val emilVersion = "0.19.0"
val jMailVersion = "2.1.0"
val catsRetryVersion = "4.0.0"
val alloyCoreVersion = "0.3.36"
val smithy4sVersion = "0.18.48"

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := scalaVer

ThisBuild / Test / parallelExecution := false

lazy val root = project
  .in(file("."))
  .settings(
    name := "thalesServer",
    scalacOptions ++= Seq("-deprecation", "-Xmax-inlines:64", "-language:strictEquality", "-Yexplicit-nulls"),
    libraryDependencies ++= Seq(
      "com.neo" %% "thalesprotocol" % "0.1.0-SNAPSHOT",
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.github.cb372" %% "cats-retry" % catsRetryVersion,
      "org.postgresql" % "postgresql" % postgresVersion,
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      "org.tpolecat" %% "doobie-specs2" % doobieVersion,
      "org.tpolecat" %% "doobie-hikari" % doobieVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterScalaVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterScalaVersion % "compile-internal",
      "com.github.pureconfig" %% "pureconfig-core" % pureConfigCoreVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4catsSlf4jVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion % Runtime,
      "com.github.jwt-scala" %% "jwt-core" % jwtVersion,
      "com.password4j" % "password4j" % password4jVersion,
      "com.github.eikek" %% "emil-common" % emilVersion,
      "com.github.eikek" %% "emil-javamail" % emilVersion,
      "com.sanctionco.jmail" % "jmail" % jMailVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % smithy4sVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s-swagger" % smithy4sVersion,
      "com.disneystreaming.alloy" % "alloy-core" % alloyCoreVersion,
      "org.typelevel" %% "cats-effect-testing-scalatest" % catsEffectTestingScalatestVersion % Test,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
    ),
    assembly / mainClass := Some("app.Main"),
    assembly / assemblyJarName := "thalesServer.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "javamail.providers") => MergeStrategy.concat
      case PathList("META-INF", "mailcap") => MergeStrategy.concat
      case "module-info.class" => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
  )

addCommandAlias("fmt", "scalafmtAll")
addCommandAlias("z", "Test/compile")
addCommandAlias("x", "test")
