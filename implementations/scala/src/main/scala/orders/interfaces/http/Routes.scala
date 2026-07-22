package orders.interfaces.http

import cats.effect.IO
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.io.*
import orders.domain.*
import orders.infrastructure.OrderRepository

import java.util.UUID

final class Routes(repository: OrderRepository):
  import JsonCodecs.given

  private def error(message: String, code: String): Json =
    Json.obj("error" -> message.asJson, "code" -> code.asJson)

  private def parseUuid(value: String): Either[Throwable, UUID] =
    Either.catchNonFatal(UUID.fromString(value))

  private def validCreate(order: CreateOrder): Boolean =
    order.customerId != null && order.items.nonEmpty && order.items.forall(
      item =>
        item.productId != null && item.quantity > 0 && item.unitPrice.compareTo(
          java.math.BigDecimal.ZERO
        ) >= 0
    )

  val httpRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" => Ok(Json.obj("status" -> "ok".asJson))

    case request @ POST -> Root / "api" / "v1" / "orders" =>
      request.as[CreateOrder].attempt.flatMap {
        case Left(_) =>
          BadRequest(error("invalid order payload", "VALIDATION_ERROR"))
        case Right(body) if !validCreate(body) =>
          BadRequest(error("invalid order payload", "VALIDATION_ERROR"))
        case Right(body) =>
          val id = UUID.randomUUID()
          repository.create(id, body.customerId, body.items) *> repository
            .find(id)
            .flatMap(order => Created(Json.obj("data" -> order.asJson)))
      }

    case request @ GET -> Root / "api" / "v1" / "orders" =>
      val params = request.uri.query.params
      val page = params.get("page").flatMap(_.toIntOption).getOrElse(1).max(1)
      val limit =
        params.get("limit").flatMap(_.toIntOption).getOrElse(20).max(1).min(100)
      val customer = params.get("customer_id").traverse(parseUuid)
      val status = params.get("status")
      if customer.isLeft then
        BadRequest(error("invalid customer_id", "INVALID_ID"))
      else if status.exists(!OrderStatus.isKnown(_)) then
        BadRequest(error("invalid status", "VALIDATION_ERROR"))
      else
        repository
          .list(customer.toOption.flatten, status, page, limit)
          .flatMap { case (total, orders) =>
            Ok(
              Json.obj(
                "data" -> orders.asJson,
                "total" -> total.asJson,
                "page" -> page.asJson,
                "limit" -> limit.asJson
              )
            )
          }

    case GET -> Root / "api" / "v1" / "orders" / orderId =>
      parseUuid(orderId) match
        case Left(_)   => BadRequest(error("invalid order ID", "INVALID_ID"))
        case Right(id) =>
          repository.find(id).flatMap {
            case None        => NotFound(error("order not found", "NOT_FOUND"))
            case Some(order) => Ok(Json.obj("data" -> order.asJson))
          }

    case request @ PATCH -> Root / "api" / "v1" / "orders" / orderId / "status" =>
      parseUuid(orderId) match
        case Left(_)   => BadRequest(error("invalid order ID", "INVALID_ID"))
        case Right(id) =>
          request.as[UpdateStatus].attempt.flatMap {
            case Left(_) =>
              BadRequest(error("invalid status", "VALIDATION_ERROR"))
            case Right(UpdateStatus(status)) if !OrderStatus.isKnown(status) =>
              BadRequest(error("invalid status", "VALIDATION_ERROR"))
            case Right(UpdateStatus(status)) =>
              repository.find(id).flatMap {
                case None => NotFound(error("order not found", "NOT_FOUND"))
                case Some(order)
                    if !OrderStatus.canTransition(order.status, status) =>
                  Conflict(
                    error(
                      s"invalid status transition: ${order.status} -> $status",
                      "INVALID_TRANSITION"
                    )
                  )
                case Some(_) =>
                  repository.updateStatus(id, status) *> repository
                    .find(id)
                    .flatMap(order => Ok(Json.obj("data" -> order.asJson)))
              }
          }

    case DELETE -> Root / "api" / "v1" / "orders" / orderId =>
      parseUuid(orderId) match
        case Left(_)   => BadRequest(error("invalid order ID", "INVALID_ID"))
        case Right(id) =>
          repository.find(id).flatMap {
            case None => NotFound(error("order not found", "NOT_FOUND"))
            case Some(order)
                if !Set("pending", "confirmed").contains(order.status) =>
              Conflict(
                error(
                  "only pending or confirmed orders can be cancelled",
                  "INVALID_OPERATION"
                )
              )
            case Some(_) =>
              repository.updateStatus(id, "cancelled") *> NoContent()
          }
  }
