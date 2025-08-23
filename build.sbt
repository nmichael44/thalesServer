ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.2"

val catsVersion = "2.13.0"
val catsEffectVersion = "3.6.3"

val microsoftSqlServerVersion = "12.10.1.jre11"

val log4catsSlf4jVersion = "2.7.1"
val logbackVersion = "1.5.18"
val doobieVersion = "1.0.0-RC10"
val http4sVersion = "0.23.30"
val circeVersion = "0.14.14"
val scalatestVersion = "3.2.19"
val pureConfigCoreVersion = "0.17.9"
val catsEffectTestingScalatestVersion = "1.6.0"
val jwtCirceVersion = "11.0.2"
val password4jVersion = "1.8.4"
val emilVersion = "0.19.0"
val jMailVersion = "2.0.2"
val catsRetryVersion = "4.0.0"
val tapirVersion = "1.11.42"

lazy val root = (project in file("."))
  .settings(
    name := "thalesServer",
    scalacOptions ++= Seq("-deprecation", "-Xmax-inlines:64", "-language:strictEquality", "-Yexplicit-nulls"),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.github.cb372" %% "cats-retry" % catsRetryVersion,
      "com.microsoft.sqlserver" % "mssql-jdbc" % microsoftSqlServerVersion,
      "org.tpolecat" %% "doobie-core" % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      "org.tpolecat" %% "doobie-specs2" % doobieVersion,
      "org.tpolecat" %% "doobie-hikari" % doobieVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-literal" % circeVersion,
      "com.github.pureconfig" %% "pureconfig-core" % pureConfigCoreVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4catsSlf4jVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion % Runtime,
      "com.github.jwt-scala" %% "jwt-circe" % jwtCirceVersion,
      "com.password4j" % "password4j" % password4jVersion,
      "com.github.eikek" %% "emil-common" % emilVersion,
      "com.github.eikek" %% "emil-javamail" % emilVersion,
      "com.sanctionco.jmail" % "jmail" % jMailVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
      "org.typelevel" %% "cats-effect-testing-scalatest" % catsEffectTestingScalatestVersion % Test,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
    ),
    assembly / mainClass := Some("app.Main"),
    assembly / assemblyJarName := "postg.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "javamail.providers") => MergeStrategy.concat
      case PathList("META-INF", "mailcap") => MergeStrategy.concat
      case "module-info.class" => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
  )
