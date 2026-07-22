package orders.interfaces.http

import io.circe.*
import io.circe.generic.semiauto.*
import org.http4s.EntityDecoder
import org.http4s.circe.jsonOf
import cats.effect.IO
import orders.domain.*

object JsonCodecs:
  given Decoder[CreateItem]             = deriveDecoder
  given Decoder[CreateOrder]            = deriveDecoder
  given Decoder[UpdateStatus]           = deriveDecoder
  given Encoder[OrderItem]              = deriveEncoder
  given Encoder[Order]                  = deriveEncoder
  given EntityDecoder[IO, CreateOrder]  = jsonOf
  given EntityDecoder[IO, UpdateStatus] = jsonOf
