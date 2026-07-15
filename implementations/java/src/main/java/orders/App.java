package orders;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

public class App {
  private static final Set<String> STATUSES = Set.of("pending", "confirmed", "shipped", "delivered", "cancelled");
  private static final Map<String, Set<String>> TRANSITIONS = Map.of(
      "pending", Set.of("confirmed", "cancelled"),
      "confirmed", Set.of("shipped", "cancelled"),
      "shipped", Set.of("delivered"),
      "delivered", Set.of(),
      "cancelled", Set.of());

  public static void main(String[] args) {
    Javalin app = Javalin.create();
    app.get("/health", ctx -> ctx.json(Map.of("status", "ok")));
    app.post("/api/v1/orders", ctx -> {
      CreateOrder body;
      try {
        body = ctx.bodyAsClass(CreateOrder.class);
        validateCreate(body);
      } catch (Exception e) {
        ctx.status(400).json(err("invalid order payload", "VALIDATION_ERROR"));
        return;
      }
      UUID id = UUID.randomUUID();
      BigDecimal total = body.items.stream()
          .map(i -> i.unitPrice.multiply(BigDecimal.valueOf(i.quantity)))
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      try (Connection conn = connect()) {
        conn.setAutoCommit(false);
        try {
          try (PreparedStatement ps = conn.prepareStatement("insert into orders (id, customer_id, status, total_amount) values (?::uuid, ?::uuid, 'pending', ?)")) {
            ps.setString(1, id.toString());
            ps.setString(2, body.customerId.toString());
            ps.setBigDecimal(3, total);
            ps.executeUpdate();
          }
          for (CreateItem item : body.items) {
            try (PreparedStatement ps = conn.prepareStatement("insert into order_items (id, order_id, product_id, quantity, unit_price) values (?::uuid, ?::uuid, ?::uuid, ?, ?)")) {
              ps.setString(1, UUID.randomUUID().toString());
              ps.setString(2, id.toString());
              ps.setString(3, item.productId.toString());
              ps.setInt(4, item.quantity);
              ps.setBigDecimal(5, item.unitPrice);
              ps.executeUpdate();
            }
          }
          conn.commit();
        } catch (Exception e) {
          conn.rollback();
          throw e;
        }
      }
      ctx.status(201).json(Map.of("data", loadOrder(id)));
    });
    app.get("/api/v1/orders", ctx -> {
      int page = Math.max(parseInt(ctx.queryParam("page"), 1), 1);
      int limit = Math.min(Math.max(parseInt(ctx.queryParam("limit"), 20), 1), 100);
      List<Object> values = new ArrayList<>();
      List<String> clauses = new ArrayList<>();
      String customer = ctx.queryParam("customer_id");
      if (customer != null) {
        if (!validUuid(customer)) {
          ctx.status(400).json(err("invalid customer_id", "INVALID_ID"));
          return;
        }
        values.add(customer);
        clauses.add("customer_id = ?::uuid");
      }
      String status = ctx.queryParam("status");
      if (status != null) {
        if (!STATUSES.contains(status)) {
          ctx.status(400).json(err("invalid status", "VALIDATION_ERROR"));
          return;
        }
        values.add(status);
        clauses.add("status = ?");
      }
      String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
      try (Connection conn = connect()) {
        int total = count(conn, where, values);
        values.add(limit);
        values.add((page - 1) * limit);
        List<Object> data = new ArrayList<>();
        try (PreparedStatement ps = prepare(conn, "select id from orders" + where + " order by created_at desc limit ? offset ?", values)) {
          ResultSet rs = ps.executeQuery();
          while (rs.next()) data.add(loadOrder(UUID.fromString(rs.getString(1))));
        }
        ctx.json(Map.of("data", data, "total", total, "page", page, "limit", limit));
      }
    });
    app.get("/api/v1/orders/{orderID}", ctx -> {
      UUID id = parseUuidParam(ctx.pathParam("orderID"), ctx);
      if (id == null) return;
      Object order = loadOrder(id);
      if (order == null) ctx.status(404).json(err("order not found", "NOT_FOUND"));
      else ctx.json(Map.of("data", order));
    });
    app.patch("/api/v1/orders/{orderID}/status", ctx -> {
      UUID id = parseUuidParam(ctx.pathParam("orderID"), ctx);
      if (id == null) return;
      UpdateStatus body = ctx.bodyAsClass(UpdateStatus.class);
      if (body.status == null || !STATUSES.contains(body.status)) {
        ctx.status(400).json(err("invalid status", "VALIDATION_ERROR"));
        return;
      }
      Map<String, Object> order = loadOrder(id);
      if (order == null) {
        ctx.status(404).json(err("order not found", "NOT_FOUND"));
        return;
      }
      String current = order.get("status").toString();
      if (!TRANSITIONS.get(current).contains(body.status)) {
        ctx.status(409).json(err("invalid status transition: " + current + " -> " + body.status, "INVALID_TRANSITION"));
        return;
      }
      updateStatus(id, body.status);
      ctx.json(Map.of("data", loadOrder(id)));
    });
    app.delete("/api/v1/orders/{orderID}", ctx -> {
      UUID id = parseUuidParam(ctx.pathParam("orderID"), ctx);
      if (id == null) return;
      Map<String, Object> order = loadOrder(id);
      if (order == null) {
        ctx.status(404).json(err("order not found", "NOT_FOUND"));
        return;
      }
      String status = order.get("status").toString();
      if (!status.equals("pending") && !status.equals("confirmed")) {
        ctx.status(409).json(err("only pending or confirmed orders can be cancelled", "INVALID_OPERATION"));
        return;
      }
      updateStatus(id, "cancelled");
      ctx.status(204);
    });
    app.start("0.0.0.0", Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080")));
  }

  static Map<String, String> err(String message, String code) {
    return Map.of("error", message, "code", code);
  }

  static void validateCreate(CreateOrder order) {
    if (order.customerId == null || order.items == null || order.items.isEmpty()) throw new IllegalArgumentException();
    for (CreateItem item : order.items) {
      if (item.productId == null || item.quantity <= 0 || item.unitPrice == null || item.unitPrice.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException();
    }
  }

  static Connection connect() throws SQLException {
    return DriverManager.getConnection(jdbcUrl());
  }

  static String jdbcUrl() {
    String raw = System.getenv().getOrDefault("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/orders?sslmode=disable");
    URI uri = URI.create(raw);
    String[] userInfo = uri.getUserInfo().split(":", 2);
    return "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath() + "?user=" + userInfo[0] + "&password=" + userInfo[1] + "&sslmode=disable";
  }

  static Map<String, Object> loadOrder(UUID id) throws SQLException {
    try (Connection conn = connect()) {
      try (PreparedStatement ps = conn.prepareStatement("select id, customer_id, status, total_amount::float, created_at, updated_at from orders where id = ?::uuid")) {
        ps.setString(1, id.toString());
        ResultSet rs = ps.executeQuery();
        if (!rs.next()) return null;
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", rs.getString("id"));
        order.put("customer_id", rs.getString("customer_id"));
        order.put("status", rs.getString("status"));
        order.put("total_amount", rs.getDouble("total_amount"));
        order.put("created_at", rs.getObject("created_at", OffsetDateTime.class).toString());
        order.put("updated_at", rs.getObject("updated_at", OffsetDateTime.class).toString());
        List<Map<String, Object>> items = new ArrayList<>();
        try (PreparedStatement ips = conn.prepareStatement("select id, order_id, product_id, quantity, unit_price::float from order_items where order_id = ?::uuid order by created_at, id")) {
          ips.setString(1, id.toString());
          ResultSet irs = ips.executeQuery();
          while (irs.next()) {
            items.add(Map.of("id", irs.getString("id"), "order_id", irs.getString("order_id"), "product_id", irs.getString("product_id"), "quantity", irs.getInt("quantity"), "unit_price", irs.getDouble("unit_price")));
          }
        }
        order.put("items", items);
        return order;
      }
    }
  }

  static void updateStatus(UUID id, String status) throws SQLException {
    try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement("update orders set status = ?, updated_at = now() where id = ?::uuid")) {
      ps.setString(1, status);
      ps.setString(2, id.toString());
      ps.executeUpdate();
    }
  }

  static int count(Connection conn, String where, List<Object> values) throws SQLException {
    try (PreparedStatement ps = prepare(conn, "select count(*) from orders" + where, values)) {
      ResultSet rs = ps.executeQuery();
      rs.next();
      return rs.getInt(1);
    }
  }

  static PreparedStatement prepare(Connection conn, String sql, List<Object> values) throws SQLException {
    PreparedStatement ps = conn.prepareStatement(sql);
    for (int i = 0; i < values.size(); i++) ps.setObject(i + 1, values.get(i));
    return ps;
  }

  static int parseInt(String value, int fallback) {
    try { return value == null ? fallback : Integer.parseInt(value); } catch (Exception e) { return fallback; }
  }

  static boolean validUuid(String value) {
    try { UUID.fromString(value); return true; } catch (Exception e) { return false; }
  }

  static UUID parseUuidParam(String value, io.javalin.http.Context ctx) {
    try { return UUID.fromString(value); } catch (Exception e) { ctx.status(400).json(err("invalid order ID", "INVALID_ID")); return null; }
  }

  public static class CreateOrder {
    @JsonProperty("customer_id") public UUID customerId;
    public List<CreateItem> items;
  }

  public static class CreateItem {
    @JsonProperty("product_id") public UUID productId;
    public int quantity;
    @JsonProperty("unit_price") public BigDecimal unitPrice;
  }

  public static class UpdateStatus {
    public String status;
  }
}
