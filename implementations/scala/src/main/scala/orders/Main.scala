package orders

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import orders.infrastructure.{Database, OrderRepository}
import orders.interfaces.http.Routes

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    val repository = OrderRepository(Database.transactor)
    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(
        Port.fromInt(sys.env.getOrElse("SERVER_PORT", "8080").toInt).get
      )
      .withHttpApp(Routes(repository).httpRoutes.orNotFound)
      .build
      .useForever
