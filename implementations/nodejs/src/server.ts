import express from "express";
import pg from "pg";
import { randomUUID, createHash } from "node:crypto";
import { z } from "zod";

const { Pool } = pg;
const databaseUrl = process.env.DATABASE_URL;
if (!databaseUrl) throw new Error("DATABASE_URL is required");
let parsedDatabaseUrl: URL;
try { parsedDatabaseUrl = new URL(databaseUrl); } catch { throw new Error("DATABASE_URL is malformed"); }
if (!["postgres:", "postgresql:"].includes(parsedDatabaseUrl.protocol) || !parsedDatabaseUrl.hostname) throw new Error("DATABASE_URL is malformed");
const port = Number(process.env.SERVER_PORT ?? 8080);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("SERVER_PORT must be a valid TCP port");
const pool = new Pool({ connectionString: databaseUrl, connectionTimeoutMillis: 5000, idleTimeoutMillis: 30000, max: 20 });
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
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
app.use((req, res, next) => {
  const requestId = req.header("X-Request-ID") || randomUUID();
  res.setHeader("X-Request-ID", requestId);
  const started = Date.now();
  res.on("finish", () => console.log(JSON.stringify({ level: "info", message: "request completed", request_id: requestId, method: req.method, path: req.path, status: res.statusCode, duration_ms: Date.now() - started })));
  next();
});
app.use(express.json());

app.get("/health", async (_req, res) => {
  try { await pool.query({ text: "set local statement_timeout = 2000; select 1" }); res.json({ status: "ok" }); }
  catch { res.status(503).json({ status: "unavailable" }); }
});

app.post("/api/v1/orders", async (req, res) => {
  const parsed = createOrderSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json(error("invalid order payload", "VALIDATION_ERROR"));
  const idempotencyKey = req.header("Idempotency-Key");
  const requestHash = createHash("sha256").update(JSON.stringify(parsed.data)).digest("hex");
  if (idempotencyKey) {
    if (idempotencyKey.length > 255) return res.status(400).json(error("Idempotency-Key is too long", "VALIDATION_ERROR"));
    const prior = await pool.query("select order_id, request_hash from idempotency_keys where key = $1", [idempotencyKey]);
    if (prior.rowCount) {
      if (prior.rows[0].request_hash !== requestHash) return res.status(409).json(error("idempotency key was used with a different request", "IDEMPOTENCY_CONFLICT"));
      const existing = await loadOrder(prior.rows[0].order_id);
      if (existing) return res.status(200).json({ data: existing });
    }
  }

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
    if (idempotencyKey) await client.query("insert into idempotency_keys (key, request_hash, order_id) values ($1, $2, $3)", [idempotencyKey, requestHash, id]);
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
  let cursorClause = "";
  if (typeof req.query.cursor === "string") {
    try { const decoded = JSON.parse(Buffer.from(req.query.cursor, "base64url").toString()); if (!decoded.created_at || !isUUID(decoded.id)) throw new Error(); values.push(decoded.created_at, decoded.id); cursorClause = ` and (created_at, id) < ($${values.length - 1}, $${values.length})`; }
    catch { return res.status(400).json(error("invalid cursor", "INVALID_CURSOR")); }
  }
  const listValues = [...values, limit];
  const pageWhere = where ? `${where}${cursorClause}` : cursorClause.replace(" and ", "where ");
  const orders = await pool.query(
    `select id, created_at from orders ${pageWhere} order by created_at desc, id desc limit $${values.length + 1}`,
    listValues,
  );
  const data = await Promise.all(orders.rows.map((row) => loadOrder(row.id)));
  const last = orders.rows.at(-1);
  const next_cursor = orders.rows.length === limit && last ? Buffer.from(JSON.stringify({ created_at: last.created_at, id: last.id })).toString("base64url") : null;
  res.json({ data, total: totalResult.rows[0].total, limit, next_cursor });
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

const server = app.listen(port, () => console.log(JSON.stringify({ level: "info", message: "server listening", port })));
server.requestTimeout = 15000;
server.headersTimeout = 16000;
server.keepAliveTimeout = 5000;
const shutdown = async (signal: string) => { console.log(JSON.stringify({ level: "info", message: "shutdown started", signal })); server.close(async () => { await pool.end(); process.exit(0); }); setTimeout(() => process.exit(1), 30000).unref(); };
process.once("SIGTERM", () => void shutdown("SIGTERM"));
process.once("SIGINT", () => void shutdown("SIGINT"));
