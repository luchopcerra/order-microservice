package orders

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import com.comcast.ip4s.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder

import java.math.BigDecimal
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

object Main extends IOApp.Simple:
  val transitions = Map(
    "pending" -> Set("confirmed", "cancelled"),
    "confirmed" -> Set("shipped", "cancelled"),
    "shipped" -> Set("delivered"),
    "delivered" -> Set.empty,
    "cancelled" -> Set.empty
  )

  case class CreateItem(product_id: UUID, quantity: Int, unit_price: BigDecimal)
  case class CreateOrder(customer_id: UUID, items: List[CreateItem])
  case class UpdateStatus(status: String)
  case class OrderItem(id: UUID, order_id: UUID, product_id: UUID, quantity: Int, unit_price: Double)
  case class Order(id: UUID, customer_id: UUID, status: String, total_amount: Double, created_at: OffsetDateTime, updated_at: OffsetDateTime, items: List[OrderItem])

  given Decoder[CreateItem] = deriveDecoder
  given Decoder[CreateOrder] = deriveDecoder
  given Decoder[UpdateStatus] = deriveDecoder
  given Encoder[OrderItem] = deriveEncoder
  given Encoder[Order] = deriveEncoder
  given EntityDecoder[IO, CreateOrder] = jsonOf
  given EntityDecoder[IO, UpdateStatus] = jsonOf

  val xa: Transactor[IO] =
    val uri = URI(sys.env.getOrElse("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/orders?sslmode=disable"))
    val Array(user, password) = uri.getUserInfo.split(":", 2)
    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      s"jdbc:postgresql://${uri.getHost}:${uri.getPort}${uri.getPath}?sslmode=disable",
      user,
      password,
      None
    )

  def error(message: String, code: String): Json = Json.obj("error" -> message.asJson, "code" -> code.asJson)
  def parseUuid(value: String): Either[Throwable, UUID] = Either.catchNonFatal(UUID.fromString(value))
  def validCreate(o: CreateOrder): Boolean =
    o.customer_id != null && o.items.nonEmpty && o.items.forall(i => i.product_id != null && i.quantity > 0 && i.unit_price.compareTo(BigDecimal.ZERO) >= 0)

  def loadOrder(id: UUID): IO[Option[Order]] =
    val orderSql = sql"select id, customer_id, status, total_amount::float8, created_at, updated_at from orders where id = $id"
      .query[(UUID, UUID, String, Double, OffsetDateTime, OffsetDateTime)].option
    val itemsSql = sql"select id, order_id, product_id, quantity, unit_price::float8 from order_items where order_id = $id order by created_at, id"
      .query[OrderItem].to[List]
    (orderSql, itemsSql).mapN((order, items) => order.map(o => Order(o._1, o._2, o._3, o._4, o._5, o._6, items))).transact(xa)

  def updateStatus(id: UUID, status: String): IO[Int] =
    sql"update orders set status = $status, updated_at = now() where id = $id".update.run.transact(xa)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" => Ok(Json.obj("status" -> "ok".asJson))

    case req @ POST -> Root / "api" / "v1" / "orders" =>
      req.as[CreateOrder].attempt.flatMap {
        case Left(_) => BadRequest(error("invalid order payload", "VALIDATION_ERROR"))
        case Right(body) if !validCreate(body) => BadRequest(error("invalid order payload", "VALIDATION_ERROR"))
        case Right(body) =>
          val id = UUID.randomUUID()
          val total = body.items.foldLeft(BigDecimal.ZERO)((sum, i) => sum.add(i.unit_price.multiply(BigDecimal.valueOf(i.quantity.toLong))))
          val insertOrder = sql"insert into orders (id, customer_id, status, total_amount) values ($id, ${body.customer_id}, 'pending', $total)".update.run
          val insertItems = body.items.traverse { item =>
            val itemId = UUID.randomUUID()
            sql"insert into order_items (id, order_id, product_id, quantity, unit_price) values ($itemId, $id, ${item.product_id}, ${item.quantity}, ${item.unit_price})".update.run
          }
          (insertOrder *> insertItems).transact(xa) *> loadOrder(id).flatMap(order => Created(Json.obj("data" -> order.asJson)))
      }

    case req @ GET -> Root / "api" / "v1" / "orders" =>
      val params = req.uri.query.params
      val page = params.get("page").flatMap(_.toIntOption).getOrElse(1).max(1)
      val limit = params.get("limit").flatMap(_.toIntOption).getOrElse(20).max(1).min(100)
      val customer = params.get("customer_id").traverse(parseUuid)
      val status = params.get("status")
      if customer.isLeft then BadRequest(error("invalid customer_id", "INVALID_ID"))
      else if status.exists(!transitions.contains(_)) then BadRequest(error("invalid status", "VALIDATION_ERROR"))
      else
        val cid = customer.toOption.flatten
        val base = fr"from orders where 1=1" ++ cid.fold(fr"")(id => fr"and customer_id = $id") ++ status.fold(fr"")(s => fr"and status = $s")
        val totalQ = (fr"select count(*)" ++ base).query[Int].unique
        val idsQ = (fr"select id" ++ base ++ fr"order by created_at desc limit $limit offset ${(page - 1) * limit}").query[UUID].to[List]
        (totalQ, idsQ).mapN((total, ids) => (total, ids)).transact(xa).flatMap { case (total, ids) =>
          ids.traverse(loadOrder).flatMap(orders => Ok(Json.obj("data" -> orders.flatten.asJson, "total" -> total.asJson, "page" -> page.asJson, "limit" -> limit.asJson)))
        }

    case GET -> Root / "api" / "v1" / "orders" / orderID =>
      parseUuid(orderID) match
        case Left(_) => BadRequest(error("invalid order ID", "INVALID_ID"))
        case Right(id) => loadOrder(id).flatMap {
          case None => NotFound(error("order not found", "NOT_FOUND"))
          case Some(order) => Ok(Json.obj("data" -> order.asJson))
        }

    case req @ PATCH -> Root / "api" / "v1" / "orders" / orderID / "status" =>
      parseUuid(orderID) match
        case Left(_) => BadRequest(error("invalid order ID", "INVALID_ID"))
        case Right(id) =>
          req.as[UpdateStatus].attempt.flatMap {
            case Left(_) => BadRequest(error("invalid status", "VALIDATION_ERROR"))
            case Right(body) if !transitions.contains(body.status) => BadRequest(error("invalid status", "VALIDATION_ERROR"))
            case Right(body) => loadOrder(id).flatMap {
              case None => NotFound(error("order not found", "NOT_FOUND"))
              case Some(order) if !transitions(order.status).contains(body.status) =>
                Conflict(error(s"invalid status transition: ${order.status} -> ${body.status}", "INVALID_TRANSITION"))
              case Some(_) => updateStatus(id, body.status) *> loadOrder(id).flatMap(order => Ok(Json.obj("data" -> order.asJson)))
            }
          }

    case DELETE -> Root / "api" / "v1" / "orders" / orderID =>
      parseUuid(orderID) match
        case Left(_) => BadRequest(error("invalid order ID", "INVALID_ID"))
        case Right(id) => loadOrder(id).flatMap {
          case None => NotFound(error("order not found", "NOT_FOUND"))
          case Some(order) if order.status != "pending" && order.status != "confirmed" =>
            Conflict(error("only pending or confirmed orders can be cancelled", "INVALID_OPERATION"))
          case Some(_) => updateStatus(id, "cancelled") *> NoContent()
        }
  }

  def run: IO[Unit] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(Port.fromInt(sys.env.getOrElse("SERVER_PORT", "8080").toInt).get)
      .withHttpApp(routes.orNotFound)
      .build
      .useForever
