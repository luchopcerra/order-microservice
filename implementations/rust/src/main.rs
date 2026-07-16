use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, patch, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use sqlx::{postgres::PgPoolOptions, PgPool, Row};
use std::{collections::HashMap, env, net::SocketAddr};
use uuid::Uuid;

#[derive(Clone)]
struct AppState {
    pool: PgPool,
}

#[derive(Deserialize)]
struct CreateOrder {
    customer_id: Uuid,
    items: Vec<CreateItem>,
}

#[derive(Deserialize)]
struct CreateItem {
    product_id: Uuid,
    quantity: i32,
    unit_price: f64,
}

#[derive(Deserialize)]
struct UpdateStatus {
    status: String,
}

#[derive(Serialize)]
struct OrderItem {
    id: Uuid,
    order_id: Uuid,
    product_id: Uuid,
    quantity: i32,
    unit_price: f64,
}

#[derive(Serialize)]
struct Order {
    id: Uuid,
    customer_id: Uuid,
    status: String,
    total_amount: f64,
    created_at: chrono::DateTime<chrono::Utc>,
    updated_at: chrono::DateTime<chrono::Utc>,
    items: Vec<OrderItem>,
}

struct ValidatedJson<T>(T);

#[tokio::main]
async fn main() {
    let database_url = env::var("DATABASE_URL").unwrap_or_else(|_| "postgres://postgres:postgres@localhost:5432/orders?sslmode=disable".to_string());
    let pool = PgPoolOptions::new().max_connections(8).connect(&database_url).await.expect("connect database");
    let app = Router::new()
        .route("/health", get(health))
        .route("/api/v1/orders", post(create_order).get(list_orders))
        .route("/api/v1/orders/:order_id", get(get_order).delete(cancel_order))
        .route("/api/v1/orders/:order_id/status", patch(update_status))
        .with_state(AppState { pool });
    let port: u16 = env::var("SERVER_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(8080);
    let listener = tokio::net::TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port))).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

async fn health() -> Json<serde_json::Value> {
    Json(json!({ "status": "ok" }))
}

async fn create_order(State(state): State<AppState>, Json(raw): Json<serde_json::Value>) -> Response {
    let payload: CreateOrder = match serde_json::from_value(raw) {
        Ok(value) => value,
        Err(_) => return api_error(StatusCode::BAD_REQUEST, "invalid order payload", "VALIDATION_ERROR"),
    };
    if payload.items.is_empty() || payload.items.iter().any(|i| i.quantity <= 0 || i.unit_price < 0.0) {
        return api_error(StatusCode::BAD_REQUEST, "invalid order payload", "VALIDATION_ERROR");
    }
    let id = Uuid::new_v4();
    let total: f64 = payload.items.iter().map(|i| i.quantity as f64 * i.unit_price).sum();
    let mut tx = match state.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => return api_error(StatusCode::INTERNAL_SERVER_ERROR, "failed to create order", "INTERNAL_ERROR"),
    };
    if sqlx::query("insert into orders (id, customer_id, status, total_amount) values ($1, $2, 'pending', $3)")
        .bind(id)
        .bind(payload.customer_id)
        .bind(total)
        .execute(&mut *tx)
        .await
        .is_err()
    {
        return api_error(StatusCode::INTERNAL_SERVER_ERROR, "failed to create order", "INTERNAL_ERROR");
    }
    for item in payload.items {
        if sqlx::query("insert into order_items (id, order_id, product_id, quantity, unit_price) values ($1, $2, $3, $4, $5)")
            .bind(Uuid::new_v4())
            .bind(id)
            .bind(item.product_id)
            .bind(item.quantity)
            .bind(item.unit_price)
            .execute(&mut *tx)
            .await
            .is_err()
        {
            return api_error(StatusCode::INTERNAL_SERVER_ERROR, "failed to create order", "INTERNAL_ERROR");
        }
    }
    if tx.commit().await.is_err() {
        return api_error(StatusCode::INTERNAL_SERVER_ERROR, "failed to create order", "INTERNAL_ERROR");
    }
    match load_order(&state.pool, id).await {
        Ok(Some(order)) => (StatusCode::CREATED, Json(json!({ "data": order }))).into_response(),
        _ => api_error(StatusCode::INTERNAL_SERVER_ERROR, "failed to create order", "INTERNAL_ERROR"),
    }
}

async fn list_orders(State(state): State<AppState>, Query(params): Query<HashMap<String, String>>) -> Response {
    let page = params.get("page").and_then(|v| v.parse::<i64>().ok()).unwrap_or(1).max(1);
    let limit = params.get("limit").and_then(|v| v.parse::<i64>().ok()).unwrap_or(20).clamp(1, 100);
    let customer_id = match params.get("customer_id") {
        Some(value) => match Uuid::parse_str(value) {
            Ok(id) => Some(id),
            Err(_) => return api_error(StatusCode::BAD_REQUEST, "invalid customer_id", "INVALID_ID"),
        },
        None => None,
    };
    let status = params.get("status").cloned();
    if let Some(s) = &status {
        if !transitions().contains_key(s.as_str()) {
            return api_error(StatusCode::BAD_REQUEST, "invalid status", "VALIDATION_ERROR");
        }
    }
    let (ids, total) = match (customer_id, status.as_deref()) {
        (Some(customer), Some(status)) => {
            let total = sqlx::query("select count(*)::int8 as total from orders where customer_id = $1 and status = $2").bind(customer).bind(status).fetch_one(&state.pool).await;
            let ids = sqlx::query("select id from orders where customer_id = $1 and status = $2 order by created_at desc limit $3 offset $4").bind(customer).bind(status).bind(limit).bind((page - 1) * limit).fetch_all(&state.pool).await;
            (ids, total)
        }
        (Some(customer), None) => {
            let total = sqlx::query("select count(*)::int8 as total from orders where customer_id = $1").bind(customer).fetch_one(&state.pool).await;
            let ids = sqlx::query("select id from orders where customer_id = $1 order by created_at desc limit $2 offset $3").bind(customer).bind(limit).bind((page - 1) * limit).fetch_all(&state.pool).await;
            (ids, total)
        }
        (None, Some(status)) => {
            let total = sqlx::query("select count(*)::int8 as total from orders where status = $1").bind(status).fetch_one(&state.pool).await;
            let ids = sqlx::query("select id from orders where status = $1 order by created_at desc limit $2 offset $3").bind(status).bind(limit).bind((page - 1) * limit).fetch_all(&state.pool).await;
            (ids, total)
        }
        (None, None) => {
            let total = sqlx::query("select count(*)::int8 as total from orders").fetch_one(&state.pool).await;
            let ids = sqlx::query("select id from orders order by created_at desc limit $1 offset $2").bind(limit).bind((page - 1) * limit).fetch_all(&state.pool).await;
            (ids, total)
        }
    };
    let mut data = Vec::new();
    for row in ids.unwrap_or_default() {
        if let Ok(Some(order)) = load_order(&state.pool, row.get("id")).await {
            data.push(order);
        }
    }
    let total = total.map(|row| row.get::<i64, _>("total")).unwrap_or(0);
    Json(json!({ "data": data, "total": total, "page": page, "limit": limit })).into_response()
}

async fn get_order(State(state): State<AppState>, Path(order_id): Path<String>) -> Response {
    let id = match Uuid::parse_str(&order_id) {
        Ok(id) => id,
        Err(_) => return api_error(StatusCode::BAD_REQUEST, "invalid order ID", "INVALID_ID"),
    };
    match load_order(&state.pool, id).await {
        Ok(Some(order)) => Json(json!({ "data": order })).into_response(),
        Ok(None) => api_error(StatusCode::NOT_FOUND, "order not found", "NOT_FOUND"),
        Err(_) => api_error(StatusCode::INTERNAL_SERVER_ERROR, "failed to get order", "INTERNAL_ERROR"),
    }
}

async fn update_status(State(state): State<AppState>, Path(order_id): Path<String>, Json(payload): Json<UpdateStatus>) -> Response {
    let id = match Uuid::parse_str(&order_id) {
        Ok(id) => id,
        Err(_) => return api_error(StatusCode::BAD_REQUEST, "invalid order ID", "INVALID_ID"),
    };
    if !transitions().contains_key(payload.status.as_str()) {
        return api_error(StatusCode::BAD_REQUEST, "invalid status", "VALIDATION_ERROR");
    }
    let order = match load_order(&state.pool, id).await {
        Ok(Some(order)) => order,
        Ok(None) => return api_error(StatusCode::NOT_FOUND, "order not found", "NOT_FOUND"),
        Err(_) => return api_error(StatusCode::INTERNAL_SERVER_ERROR, "failed to get order", "INTERNAL_ERROR"),
    };
    if !transitions().get(order.status.as_str()).unwrap().contains(&payload.status.as_str()) {
        return api_error(StatusCode::CONFLICT, &format!("invalid status transition: {} -> {}", order.status, payload.status), "INVALID_TRANSITION");
    }
    sqlx::query("update orders set status = $1, updated_at = now() where id = $2").bind(&payload.status).bind(id).execute(&state.pool).await.unwrap();
    Json(json!({ "data": load_order(&state.pool, id).await.unwrap() })).into_response()
}

async fn cancel_order(State(state): State<AppState>, Path(order_id): Path<String>) -> Response {
    let id = match Uuid::parse_str(&order_id) {
        Ok(id) => id,
        Err(_) => return api_error(StatusCode::BAD_REQUEST, "invalid order ID", "INVALID_ID"),
    };
    let order = match load_order(&state.pool, id).await {
        Ok(Some(order)) => order,
        Ok(None) => return api_error(StatusCode::NOT_FOUND, "order not found", "NOT_FOUND"),
        Err(_) => return api_error(StatusCode::INTERNAL_SERVER_ERROR, "failed to get order", "INTERNAL_ERROR"),
    };
    if order.status != "pending" && order.status != "confirmed" {
        return api_error(StatusCode::CONFLICT, "only pending or confirmed orders can be cancelled", "INVALID_OPERATION");
    }
    sqlx::query("update orders set status = 'cancelled', updated_at = now() where id = $1").bind(id).execute(&state.pool).await.unwrap();
    StatusCode::NO_CONTENT.into_response()
}

async fn load_order(pool: &PgPool, id: Uuid) -> sqlx::Result<Option<Order>> {
    let row = sqlx::query("select id, customer_id, status, total_amount::float8, created_at, updated_at from orders where id = $1").bind(id).fetch_optional(pool).await?;
    let Some(row) = row else { return Ok(None); };
    let item_rows = sqlx::query("select id, order_id, product_id, quantity, unit_price::float8 from order_items where order_id = $1 order by created_at, id").bind(id).fetch_all(pool).await?;
    let items = item_rows.into_iter().map(|r| OrderItem {
        id: r.get("id"),
        order_id: r.get("order_id"),
        product_id: r.get("product_id"),
        quantity: r.get("quantity"),
        unit_price: r.get("unit_price"),
    }).collect();
    Ok(Some(Order {
        id: row.get("id"),
        customer_id: row.get("customer_id"),
        status: row.get("status"),
        total_amount: row.get("total_amount"),
        created_at: row.get("created_at"),
        updated_at: row.get("updated_at"),
        items,
    }))
}

fn transitions() -> HashMap<&'static str, Vec<&'static str>> {
    HashMap::from([
        ("pending", vec!["confirmed", "cancelled"]),
        ("confirmed", vec!["shipped", "cancelled"]),
        ("shipped", vec!["delivered"]),
        ("delivered", vec![]),
        ("cancelled", vec![]),
    ])
}

fn api_error(status: StatusCode, message: &str, code: &str) -> Response {
    (status, Json(json!({ "error": message, "code": code }))).into_response()
}

#[cfg(test)]
mod tests {
    #[test]
    fn lifecycle() {
        let transitions = super::transitions();
        assert_eq!(transitions["pending"], vec!["confirmed", "cancelled"]);
        assert_eq!(transitions["shipped"], vec!["delivered"]);
    }
}
