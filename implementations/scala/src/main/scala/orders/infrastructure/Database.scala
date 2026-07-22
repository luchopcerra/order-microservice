package orders.infrastructure

import cats.effect.IO
import doobie.Transactor

import java.net.URI

object Database:
  def transactor: Transactor[IO] =
    val uri =
      URI(sys.env.getOrElse("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/orders?sslmode=disable"))
    val Array(user, password) = uri.getUserInfo.split(":", 2)

    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      s"jdbc:postgresql://${uri.getHost}:${uri.getPort}${uri.getPath}?sslmode=disable",
      user,
      password,
      None
    )
