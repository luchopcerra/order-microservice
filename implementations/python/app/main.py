import os
import uuid
from decimal import Decimal
from typing import Literal

import psycopg
from fastapi import FastAPI, HTTPException, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
import uvicorn

DATABASE_URL = os.getenv("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/orders?sslmode=disable")
Status = Literal["pending", "confirmed", "shipped", "delivered", "cancelled"]
TRANSITIONS: dict[str, set[str]] = {
    "pending": {"confirmed", "cancelled"},
    "confirmed": {"shipped", "cancelled"},
    "shipped": {"delivered"},
    "delivered": set(),
    "cancelled": set(),
}

app = FastAPI()


class CreateItem(BaseModel):
    product_id: uuid.UUID
    quantity: int = Field(gt=0)
    unit_price: Decimal = Field(ge=0)


class CreateOrder(BaseModel):
    customer_id: uuid.UUID
    items: list[CreateItem] = Field(min_length=1)


class UpdateStatus(BaseModel):
    status: Status


def fail(status: int, message: str, code: str) -> HTTPException:
    return HTTPException(status_code=status, detail={"error": message, "code": code})


@app.exception_handler(HTTPException)
async def http_exception_handler(_request, exc: HTTPException):
    if isinstance(exc.detail, dict) and "error" in exc.detail:
        return JSONResponse(status_code=exc.status_code, content=exc.detail)
    return JSONResponse(status_code=exc.status_code, content={"error": str(exc.detail), "code": "ERROR"})


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_request, _exc: RequestValidationError):
    return JSONResponse(status_code=400, content={"error": "validation error", "code": "VALIDATION_ERROR"})


def connection():
    return psycopg.connect(DATABASE_URL)


def load_order(order_id: uuid.UUID):
    with connection() as conn:
        row = conn.execute(
            "select id, customer_id, status, total_amount::float, created_at, updated_at from orders where id = %s",
            (order_id,),
        ).fetchone()
        if row is None:
            return None
        items = conn.execute(
            "select id, order_id, product_id, quantity, unit_price::float from order_items where order_id = %s order by created_at, id",
            (order_id,),
        ).fetchall()
    return {
        "id": str(row[0]),
        "customer_id": str(row[1]),
        "status": row[2],
        "total_amount": row[3],
        "created_at": row[4].isoformat(),
        "updated_at": row[5].isoformat(),
        "items": [
            {
                "id": str(item[0]),
                "order_id": str(item[1]),
                "product_id": str(item[2]),
                "quantity": item[3],
                "unit_price": item[4],
            }
            for item in items
        ],
    }


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/api/v1/orders", status_code=201)
def create_order(payload: CreateOrder):
    order_id = uuid.uuid4()
    total = sum(item.quantity * item.unit_price for item in payload.items)
    with connection() as conn:
        with conn.transaction():
            conn.execute(
                "insert into orders (id, customer_id, status, total_amount) values (%s, %s, 'pending', %s)",
                (order_id, payload.customer_id, total),
            )
            for item in payload.items:
                conn.execute(
                    "insert into order_items (id, order_id, product_id, quantity, unit_price) values (%s, %s, %s, %s, %s)",
                    (uuid.uuid4(), order_id, item.product_id, item.quantity, item.unit_price),
                )
    return {"data": load_order(order_id)}


@app.get("/api/v1/orders")
def list_orders(customer_id: uuid.UUID | None = None, status: Status | None = None, page: int = 1, limit: int = 20):
    page = max(page, 1)
    limit = min(max(limit, 1), 100)
    clauses = []
    values = []
    if customer_id:
        clauses.append("customer_id = %s")
        values.append(customer_id)
    if status:
        clauses.append("status = %s")
        values.append(status)
    where = "where " + " and ".join(clauses) if clauses else ""
    with connection() as conn:
        total = conn.execute(f"select count(*) from orders {where}", values).fetchone()[0]
        rows = conn.execute(
            f"select id from orders {where} order by created_at desc limit %s offset %s",
            [*values, limit, (page - 1) * limit],
        ).fetchall()
    return {"data": [load_order(row[0]) for row in rows], "total": total, "page": page, "limit": limit}


@app.get("/api/v1/orders/{order_id}")
def get_order(order_id: uuid.UUID):
    order = load_order(order_id)
    if order is None:
        raise fail(404, "order not found", "NOT_FOUND")
    return {"data": order}


@app.patch("/api/v1/orders/{order_id}/status")
def update_status(order_id: uuid.UUID, payload: UpdateStatus):
    order = load_order(order_id)
    if order is None:
        raise fail(404, "order not found", "NOT_FOUND")
    if payload.status not in TRANSITIONS[order["status"]]:
        raise fail(409, f"invalid status transition: {order['status']} -> {payload.status}", "INVALID_TRANSITION")
    with connection() as conn:
        conn.execute("update orders set status = %s, updated_at = now() where id = %s", (payload.status, order_id))
    return {"data": load_order(order_id)}


@app.delete("/api/v1/orders/{order_id}", status_code=204)
def cancel_order(order_id: uuid.UUID):
    order = load_order(order_id)
    if order is None:
        raise fail(404, "order not found", "NOT_FOUND")
    if order["status"] not in {"pending", "confirmed"}:
        raise fail(409, "only pending or confirmed orders can be cancelled", "INVALID_OPERATION")
    with connection() as conn:
        conn.execute("update orders set status = 'cancelled', updated_at = now() where id = %s", (order_id,))
    return Response(status_code=204)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("SERVER_PORT", "8080")))
