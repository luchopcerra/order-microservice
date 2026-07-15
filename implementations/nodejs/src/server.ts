import express from "express";
import pg from "pg";
import { randomUUID } from "node:crypto";
import { z } from "zod";

const { Pool } = pg;
const pool = new Pool({
  connectionString: process.env.DATABASE_URL || "postgres://postgres:postgres@localhost:5432/orders?sslmode=disable",
});
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const isUUID = (value: string) => uuidPattern.test(value);

const statuses = ["pending", "confirmed", "shipped", "delivered", "cancelled"] as const;
type Status = (typeof statuses)[number];

const transitions: Record<Status, Status[]> = {
  pending: ["confirmed", "cancelled"],
  confirmed: ["shipped", "cancelled"],
  shipped: ["delivered"],
  delivered: [],
  cancelled: [],
};

const createOrderSchema = z.object({
  customer_id: z.string().uuid(),
  items: z.array(z.object({
    product_id: z.string().uuid(),
    quantity: z.number().int().positive(),
    unit_price: z.number().nonnegative(),
  })).min(1),
});

const updateStatusSchema = z.object({ status: z.enum(statuses) });

function error(error: string, code: string) {
  return { error, code };
}

async function loadOrder(id: string) {
  const orderResult = await pool.query("select id, customer_id, status, total_amount::float, created_at, updated_at from orders where id = $1", [id]);
  if (orderResult.rowCount === 0) return null;
  const itemResult = await pool.query("select id, order_id, product_id, quantity, unit_price::float from order_items where order_id = $1 order by created_at, id", [id]);
  return { ...orderResult.rows[0], items: itemResult.rows };
}

const app = express();
app.use(express.json());

app.get("/health", (_req, res) => res.json({ status: "ok" }));

app.post("/api/v1/orders", async (req, res) => {
  const parsed = createOrderSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json(error("invalid order payload", "VALIDATION_ERROR"));

  const id = randomUUID();
  const total = parsed.data.items.reduce((sum, item) => sum + item.quantity * item.unit_price, 0);
  const client = await pool.connect();
  try {
    await client.query("begin");
    await client.query(
      "insert into orders (id, customer_id, status, total_amount) values ($1, $2, 'pending', $3)",
      [id, parsed.data.customer_id, total],
    );
    for (const item of parsed.data.items) {
      await client.query(
        "insert into order_items (id, order_id, product_id, quantity, unit_price) values ($1, $2, $3, $4, $5)",
        [randomUUID(), id, item.product_id, item.quantity, item.unit_price],
      );
    }
    await client.query("commit");
    res.status(201).json({ data: await loadOrder(id) });
  } catch (err) {
    await client.query("rollback");
    res.status(500).json(error("failed to create order", "INTERNAL_ERROR"));
  } finally {
    client.release();
  }
});

app.get("/api/v1/orders", async (req, res) => {
  const page = Math.max(Number(req.query.page || 1), 1);
  const limit = Math.min(Math.max(Number(req.query.limit || 20), 1), 100);
  const clauses: string[] = [];
  const values: unknown[] = [];
  if (typeof req.query.customer_id === "string") {
    if (!isUUID(req.query.customer_id)) return res.status(400).json(error("invalid customer_id", "INVALID_ID"));
    values.push(req.query.customer_id);
    clauses.push(`customer_id = $${values.length}`);
  }
  if (typeof req.query.status === "string") {
    if (!statuses.includes(req.query.status as Status)) return res.status(400).json(error("invalid status", "VALIDATION_ERROR"));
    values.push(req.query.status);
    clauses.push(`status = $${values.length}`);
  }
  const where = clauses.length ? `where ${clauses.join(" and ")}` : "";
  const totalResult = await pool.query(`select count(*)::int as total from orders ${where}`, values);
  const listValues = [...values, limit, (page - 1) * limit];
  const orders = await pool.query(
    `select id from orders ${where} order by created_at desc limit $${values.length + 1} offset $${values.length + 2}`,
    listValues,
  );
  const data = await Promise.all(orders.rows.map((row) => loadOrder(row.id)));
  res.json({ data, total: totalResult.rows[0].total, page, limit });
});

app.get("/api/v1/orders/:orderID", async (req, res) => {
  if (!isUUID(req.params.orderID)) return res.status(400).json(error("invalid order ID", "INVALID_ID"));
  const order = await loadOrder(req.params.orderID);
  if (!order) return res.status(404).json(error("order not found", "NOT_FOUND"));
  res.json({ data: order });
});

app.patch("/api/v1/orders/:orderID/status", async (req, res) => {
  if (!isUUID(req.params.orderID)) return res.status(400).json(error("invalid order ID", "INVALID_ID"));
  const parsed = updateStatusSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json(error("invalid status", "VALIDATION_ERROR"));
  const order = await loadOrder(req.params.orderID);
  if (!order) return res.status(404).json(error("order not found", "NOT_FOUND"));
  if (!transitions[order.status as Status].includes(parsed.data.status)) {
    return res.status(409).json(error(`invalid status transition: ${order.status} -> ${parsed.data.status}`, "INVALID_TRANSITION"));
  }
  await pool.query("update orders set status = $1, updated_at = now() where id = $2", [parsed.data.status, req.params.orderID]);
  res.json({ data: await loadOrder(req.params.orderID) });
});

app.delete("/api/v1/orders/:orderID", async (req, res) => {
  if (!isUUID(req.params.orderID)) return res.status(400).json(error("invalid order ID", "INVALID_ID"));
  const order = await loadOrder(req.params.orderID);
  if (!order) return res.status(404).json(error("order not found", "NOT_FOUND"));
  if (!["pending", "confirmed"].includes(order.status)) {
    return res.status(409).json(error("only pending or confirmed orders can be cancelled", "INVALID_OPERATION"));
  }
  await pool.query("update orders set status = 'cancelled', updated_at = now() where id = $1", [req.params.orderID]);
  res.status(204).end();
});

const port = Number(process.env.SERVER_PORT || 8080);
app.listen(port, () => console.log(`orders service listening on ${port}`));
