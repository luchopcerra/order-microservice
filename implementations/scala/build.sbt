ThisBuild / scalaVersion := "3.5.1"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "orders-service-scala",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % "0.23.28",
      "org.http4s" %% "http4s-circe" % "0.23.28",
      "org.http4s" %% "http4s-dsl" % "0.23.28",
      "io.circe" %% "circe-generic" % "0.14.10",
      "io.circe" %% "circe-parser" % "0.14.10",
      "org.tpolecat" %% "doobie-core" % "1.0.0-RC5",
      "org.tpolecat" %% "doobie-postgres" % "1.0.0-RC5",
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "org.scalameta" %% "munit" % "1.0.0" % Test
    )
  )
