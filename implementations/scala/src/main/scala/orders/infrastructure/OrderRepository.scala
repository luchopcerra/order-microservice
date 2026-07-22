package orders.infrastructure

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import orders.domain.{Order, OrderItem}

import java.time.OffsetDateTime
import java.util.UUID

final class OrderRepository(xa: Transactor[IO]):
  def find(id: UUID): IO[Option[Order]] =
    val order =
      sql"select id, customer_id, status, total_amount::float8, created_at, updated_at from orders where id = $id"
        .query[(UUID, UUID, String, Double, OffsetDateTime, OffsetDateTime)]
        .option
    val items =
      sql"select id, order_id, product_id, quantity, unit_price::float8 from order_items where order_id = $id order by created_at, id"
        .query[OrderItem]
        .to[List]

    (order, items)
      .mapN((row, orderItems) => row.map(o => Order(o._1, o._2, o._3, o._4, o._5, o._6, orderItems)))
      .transact(xa)

  def updateStatus(id: UUID, status: String): IO[Int] =
    sql"update orders set status = $status, updated_at = now() where id = $id".update.run.transact(xa)

  def list(customerId: Option[UUID], status: Option[String], page: Int, limit: Int): IO[(Int, List[Order])] =
    val base = fr"from orders where 1=1" ++ customerId.fold(fr"")(id => fr"and customer_id = $id") ++ status.fold(fr"")(
      value => fr"and status = $value"
    )
    val total = (fr"select count(*)" ++ base).query[Int].unique
    val ids = (fr"select id" ++ base ++ fr"order by created_at desc limit $limit offset ${(page - 1) * limit}").query[
      UUID
    ].to[List]
    (total, ids).mapN((count, orderIds) => (count, orderIds)).transact(xa).flatMap { case (count, orderIds) =>
      orderIds.traverse(find).map(orders => (count, orders.flatten))
    }

  def create(id: UUID, customerId: UUID, items: List[orders.domain.CreateItem]): IO[Unit] =
    val total = items.foldLeft(java.math.BigDecimal.ZERO)((sum, item) =>
      sum.add(item.unitPrice.multiply(java.math.BigDecimal.valueOf(item.quantity.toLong)))
    )
    val order =
      sql"insert into orders (id, customer_id, status, total_amount) values ($id, $customerId, 'pending', $total)"
        .update.run
    val orderItems = items.traverse { item =>
      val itemId = UUID.randomUUID()
      sql"insert into order_items (id, order_id, product_id, quantity, unit_price) values ($itemId, $id, ${item.productId}, ${item.quantity}, ${item.unitPrice})"
        .update.run
    }
    (order *> orderItems).transact(xa).void
