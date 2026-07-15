package orders

import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.math.BigDecimal
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID

val transitions = mapOf(
    "pending" to setOf("confirmed", "cancelled"),
    "confirmed" to setOf("shipped", "cancelled"),
    "shipped" to setOf("delivered"),
    "delivered" to emptySet(),
    "cancelled" to emptySet(),
)

val statuses = transitions.keys

data class CreateOrder(@JsonProperty("customer_id") val customerId: UUID?, val items: List<CreateItem>?)
data class CreateItem(@JsonProperty("product_id") val productId: UUID?, val quantity: Int, @JsonProperty("unit_price") val unitPrice: BigDecimal?)
data class UpdateStatus(val status: String?)

fun main() {
    embeddedServer(Netty, host = "0.0.0.0", port = System.getenv("SERVER_PORT")?.toInt() ?: 8080) {
        install(ContentNegotiation) { jackson() }
        routing {
            get("/health") { call.respond(mapOf("status" to "ok")) }
            post("/api/v1/orders") {
                val body = runCatching { call.receive<CreateOrder>() }.getOrNull()
                if (body == null || body.customerId == null || body.items.isNullOrEmpty() ||
                    body.items.any { it.productId == null || it.quantity <= 0 || it.unitPrice == null || it.unitPrice < BigDecimal.ZERO }) {
                    call.respond(HttpStatusCode.BadRequest, error("invalid order payload", "VALIDATION_ERROR"))
                    return@post
                }
                val id = UUID.randomUUID()
                val total = body.items.fold(BigDecimal.ZERO) { sum, item -> sum + item.unitPrice!!.multiply(BigDecimal(item.quantity)) }
                connect().use { conn ->
                    conn.autoCommit = false
                    try {
                        conn.prepareStatement("insert into orders (id, customer_id, status, total_amount) values (?::uuid, ?::uuid, 'pending', ?)").use {
                            it.setString(1, id.toString())
                            it.setString(2, body.customerId.toString())
                            it.setBigDecimal(3, total)
                            it.executeUpdate()
                        }
                        body.items.forEach { item ->
                            conn.prepareStatement("insert into order_items (id, order_id, product_id, quantity, unit_price) values (?::uuid, ?::uuid, ?::uuid, ?, ?)").use {
                                it.setString(1, UUID.randomUUID().toString())
                                it.setString(2, id.toString())
                                it.setString(3, item.productId.toString())
                                it.setInt(4, item.quantity)
                                it.setBigDecimal(5, item.unitPrice)
                                it.executeUpdate()
                            }
                        }
                        conn.commit()
                    } catch (ex: Exception) {
                        conn.rollback()
                        throw ex
                    }
                }
                call.respond(HttpStatusCode.Created, mapOf("data" to loadOrder(id)))
            }
            get("/api/v1/orders") {
                val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val clauses = mutableListOf<String>()
                val values = mutableListOf<Any>()
                call.request.queryParameters["customer_id"]?.let {
                    val parsed = parseUuid(it)
                    if (parsed == null) {
                        call.respond(HttpStatusCode.BadRequest, error("invalid customer_id", "INVALID_ID"))
                        return@get
                    }
                    clauses += "customer_id = ?::uuid"
                    values += parsed.toString()
                }
                call.request.queryParameters["status"]?.let {
                    if (it !in statuses) {
                        call.respond(HttpStatusCode.BadRequest, error("invalid status", "VALIDATION_ERROR"))
                        return@get
                    }
                    clauses += "status = ?"
                    values += it
                }
                val where = if (clauses.isEmpty()) "" else " where " + clauses.joinToString(" and ")
                connect().use { conn ->
                    val total = count(conn, where, values)
                    val ids = queryIds(conn, where, values + listOf(limit, (page - 1) * limit))
                    call.respond(mapOf("data" to ids.map { loadOrder(it) }, "total" to total, "page" to page, "limit" to limit))
                }
            }
            get("/api/v1/orders/{orderID}") {
                val id = parseUuid(call.parameters["orderID"])
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, error("invalid order ID", "INVALID_ID"))
                    return@get
                }
                val order = loadOrder(id)
                if (order == null) call.respond(HttpStatusCode.NotFound, error("order not found", "NOT_FOUND"))
                else call.respond(mapOf("data" to order))
            }
            patch("/api/v1/orders/{orderID}/status") {
                val id = parseUuid(call.parameters["orderID"])
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, error("invalid order ID", "INVALID_ID"))
                    return@patch
                }
                val body = runCatching { call.receive<UpdateStatus>() }.getOrNull()
                if (body?.status !in statuses) {
                    call.respond(HttpStatusCode.BadRequest, error("invalid status", "VALIDATION_ERROR"))
                    return@patch
                }
                val order = loadOrder(id)
                if (order == null) {
                    call.respond(HttpStatusCode.NotFound, error("order not found", "NOT_FOUND"))
                    return@patch
                }
                val current = order["status"].toString()
                val next = body!!.status!!
                if (next !in transitions.getValue(current)) {
                    call.respond(HttpStatusCode.Conflict, error("invalid status transition: $current -> $next", "INVALID_TRANSITION"))
                    return@patch
                }
                updateStatus(id, next)
                call.respond(mapOf("data" to loadOrder(id)))
            }
            delete("/api/v1/orders/{orderID}") {
                val id = parseUuid(call.parameters["orderID"])
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, error("invalid order ID", "INVALID_ID"))
                    return@delete
                }
                val order = loadOrder(id)
                if (order == null) {
                    call.respond(HttpStatusCode.NotFound, error("order not found", "NOT_FOUND"))
                    return@delete
                }
                val status = order["status"].toString()
                if (status != "pending" && status != "confirmed") {
                    call.respond(HttpStatusCode.Conflict, error("only pending or confirmed orders can be cancelled", "INVALID_OPERATION"))
                    return@delete
                }
                updateStatus(id, "cancelled")
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }.start(wait = true)
}

fun error(message: String, code: String) = mapOf("error" to message, "code" to code)
fun parseUuid(value: String?) = runCatching { UUID.fromString(value) }.getOrNull()

fun jdbcUrl(): String {
    val raw = URI(System.getenv("DATABASE_URL") ?: "postgres://postgres:postgres@localhost:5432/orders?sslmode=disable")
    val user = raw.userInfo.split(":", limit = 2)
    return "jdbc:postgresql://${raw.host}:${raw.port}${raw.path}?user=${user[0]}&password=${user[1]}&sslmode=disable"
}

fun connect(): Connection = DriverManager.getConnection(jdbcUrl())

fun loadOrder(id: UUID): Map<String, Any?>? = connect().use { conn ->
    conn.prepareStatement("select id, customer_id, status, total_amount::float, created_at, updated_at from orders where id = ?::uuid").use { ps ->
        ps.setString(1, id.toString())
        val rs = ps.executeQuery()
        if (!rs.next()) return null
        val items = mutableListOf<Map<String, Any?>>()
        conn.prepareStatement("select id, order_id, product_id, quantity, unit_price::float from order_items where order_id = ?::uuid order by created_at, id").use { ips ->
            ips.setString(1, id.toString())
            val irs = ips.executeQuery()
            while (irs.next()) items += mapOf("id" to irs.getString("id"), "order_id" to irs.getString("order_id"), "product_id" to irs.getString("product_id"), "quantity" to irs.getInt("quantity"), "unit_price" to irs.getDouble("unit_price"))
        }
        mapOf(
            "id" to rs.getString("id"),
            "customer_id" to rs.getString("customer_id"),
            "status" to rs.getString("status"),
            "total_amount" to rs.getDouble("total_amount"),
            "created_at" to rs.getObject("created_at", OffsetDateTime::class.java).toString(),
            "updated_at" to rs.getObject("updated_at", OffsetDateTime::class.java).toString(),
            "items" to items,
        )
    }
}

fun updateStatus(id: UUID, status: String) = connect().use { conn ->
    conn.prepareStatement("update orders set status = ?, updated_at = now() where id = ?::uuid").use {
        it.setString(1, status)
        it.setString(2, id.toString())
        it.executeUpdate()
    }
}

fun count(conn: Connection, where: String, values: List<Any>): Int =
    conn.prepareStatement("select count(*) from orders$where").use {
        values.forEachIndexed { index, value -> it.setObject(index + 1, value) }
        val rs = it.executeQuery()
        rs.next()
        rs.getInt(1)
    }

fun queryIds(conn: Connection, where: String, values: List<Any>): List<UUID> =
    conn.prepareStatement("select id from orders$where order by created_at desc limit ? offset ?").use {
        values.forEachIndexed { index, value -> it.setObject(index + 1, value) }
        val rs = it.executeQuery()
        val ids = mutableListOf<UUID>()
        while (rs.next()) ids += UUID.fromString(rs.getString(1))
        ids
    }
