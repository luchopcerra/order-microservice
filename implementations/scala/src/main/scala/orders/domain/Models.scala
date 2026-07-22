package orders.domain

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

final case class CreateItem(productId: UUID, quantity: Int, unitPrice: BigDecimal)
final case class CreateOrder(customerId: UUID, items: List[CreateItem])
final case class UpdateStatus(status: String)
final case class OrderItem(id: UUID, orderId: UUID, productId: UUID, quantity: Int, unitPrice: Double)
final case class Order(
    id: UUID,
    customerId: UUID,
    status: String,
    totalAmount: Double,
    createdAt: OffsetDateTime,
    updatedAt: OffsetDateTime,
    items: List[OrderItem]
)
