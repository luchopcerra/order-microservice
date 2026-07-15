const BASE_URL = process.env.BASE_URL || "http://127.0.0.1:8080";
const UUID_A = "550e8400-e29b-41d4-a716-446655440000";
const UUID_B = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function request(path, options = {}) {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(options.headers || {}),
    },
  });
  const text = await response.text();
  const body = text ? JSON.parse(text) : null;
  return { response, body };
}

async function createOrder(overrides = {}) {
  const payload = {
    customer_id: UUID_A,
    items: [{ product_id: UUID_B, quantity: 2, unit_price: 12.5 }],
    ...overrides,
  };
  return request("/api/v1/orders", { method: "POST", body: JSON.stringify(payload) });
}

const health = await request("/health");
assert(health.response.status === 200, "health status");
assert(health.body.status === "ok", "health body");

const created = await createOrder();
assert(created.response.status === 201, "create status");
assert(created.body.data.id, "created id");
assert(created.body.data.status === "pending", "created status");
assert(Number(created.body.data.total_amount) === 25, "created total");
const orderID = created.body.data.id;

for (const bad of [
  { items: [] },
  { customer_id: "not-a-uuid" },
  { items: [{ product_id: UUID_B, quantity: 0, unit_price: 1 }] },
  { items: [{ product_id: UUID_B, quantity: 1, unit_price: -1 }] },
]) {
  const result = await createOrder(bad);
  assert(result.response.status === 400, `invalid create should be 400: ${JSON.stringify(bad)}`);
}

const list = await request(`/api/v1/orders?customer_id=${UUID_A}&status=pending&page=1&limit=10`);
assert(list.response.status === 200, "list status");
assert(Array.isArray(list.body.data), "list data");
assert(Number.isInteger(list.body.total), "list total");

const invalidGet = await request("/api/v1/orders/not-a-uuid");
assert(invalidGet.response.status === 400, "invalid get id");

const missing = await request("/api/v1/orders/00000000-0000-0000-0000-000000000000");
assert(missing.response.status === 404, "missing get");

const confirm = await request(`/api/v1/orders/${orderID}/status`, {
  method: "PATCH",
  body: JSON.stringify({ status: "confirmed" }),
});
assert(confirm.response.status === 200, "confirm status");

const invalidTransition = await request(`/api/v1/orders/${orderID}/status`, {
  method: "PATCH",
  body: JSON.stringify({ status: "delivered" }),
});
assert(invalidTransition.response.status === 409, "invalid transition status");

const cancel = await request(`/api/v1/orders/${orderID}`, { method: "DELETE" });
assert(cancel.response.status === 204, "cancel status");

const cancelled = await request(`/api/v1/orders/${orderID}`);
assert(cancelled.response.status === 200, "cancelled retrievable");
assert(cancelled.body.data.status === "cancelled", "cancelled status");

const cancelAgain = await request(`/api/v1/orders/${orderID}`, { method: "DELETE" });
assert(cancelAgain.response.status === 409, "cancel terminal status");

console.log("contract tests passed");
