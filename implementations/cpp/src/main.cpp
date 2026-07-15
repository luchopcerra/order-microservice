#include <drogon/drogon.h>
#include <drogon/orm/DbClient.h>

#include <cstdlib>
#include <map>
#include <numeric>
#include <string>
#include <vector>

using namespace drogon;

const std::map<std::string, std::vector<std::string>> transitions = {
    {"pending", {"confirmed", "cancelled"}},
    {"confirmed", {"shipped", "cancelled"}},
    {"shipped", {"delivered"}},
    {"delivered", {}},
    {"cancelled", {}},
};

Json::Value apiError(const std::string &message, const std::string &code) {
    Json::Value value;
    value["error"] = message;
    value["code"] = code;
    return value;
}

bool contains(const std::vector<std::string> &items, const std::string &value) {
    return std::find(items.begin(), items.end(), value) != items.end();
}

bool validUuid(const std::string &value) {
    static const std::regex uuidRegex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    return std::regex_match(value, uuidRegex);
}

std::string databaseUrl() {
    const char *raw = std::getenv("DATABASE_URL");
    return raw ? raw : "postgres://postgres:postgres@localhost:5432/orders?sslmode=disable";
}

orm::DbClientPtr db() {
    static auto client = orm::DbClient::newPgClient(databaseUrl(), 4);
    return client;
}

Json::Value loadOrder(const std::string &id) {
    auto orderRows = db()->execSqlSync("select id::text, customer_id::text, status, total_amount::float8, created_at::text, updated_at::text from orders where id = $1::uuid", id);
    if (orderRows.empty()) {
        return Json::Value();
    }
    const auto &row = orderRows[0];
    Json::Value order;
    order["id"] = row[0].as<std::string>();
    order["customer_id"] = row[1].as<std::string>();
    order["status"] = row[2].as<std::string>();
    order["total_amount"] = row[3].as<double>();
    order["created_at"] = row[4].as<std::string>();
    order["updated_at"] = row[5].as<std::string>();
    order["items"] = Json::arrayValue;
    auto itemRows = db()->execSqlSync("select id::text, order_id::text, product_id::text, quantity, unit_price::float8 from order_items where order_id = $1::uuid order by created_at, id", id);
    for (const auto &itemRow : itemRows) {
        Json::Value item;
        item["id"] = itemRow[0].as<std::string>();
        item["order_id"] = itemRow[1].as<std::string>();
        item["product_id"] = itemRow[2].as<std::string>();
        item["quantity"] = itemRow[3].as<int>();
        item["unit_price"] = itemRow[4].as<double>();
        order["items"].append(item);
    }
    return order;
}

void sendJson(const HttpResponsePtr &resp, const std::function<void(const HttpResponsePtr &)> &callback) {
    callback(resp);
}

int main() {
    app().registerHandler("/health", [](const HttpRequestPtr &, std::function<void(const HttpResponsePtr &)> &&callback) {
        Json::Value body;
        body["status"] = "ok";
        callback(HttpResponse::newHttpJsonResponse(body));
    });

    app().registerHandler("/api/v1/orders", [](const HttpRequestPtr &req, std::function<void(const HttpResponsePtr &)> &&callback) {
        if (req->method() == Post) {
            auto body = req->getJsonObject();
            if (!body || !body->isMember("customer_id") || !validUuid((*body)["customer_id"].asString()) || !(*body)["items"].isArray() || (*body)["items"].empty()) {
                auto resp = HttpResponse::newHttpJsonResponse(apiError("invalid order payload", "VALIDATION_ERROR"));
                resp->setStatusCode(k400BadRequest);
                callback(resp);
                return;
            }
            double total = 0;
            for (const auto &item : (*body)["items"]) {
                if (!item.isMember("product_id") || !validUuid(item["product_id"].asString()) || item["quantity"].asInt() <= 0 || item["unit_price"].asDouble() < 0) {
                    auto resp = HttpResponse::newHttpJsonResponse(apiError("invalid order payload", "VALIDATION_ERROR"));
                    resp->setStatusCode(k400BadRequest);
                    callback(resp);
                    return;
                }
                total += item["quantity"].asInt() * item["unit_price"].asDouble();
            }
            auto id = utils::getUuid();
            auto tx = db()->newTransaction();
            tx->execSqlSync("insert into orders (id, customer_id, status, total_amount) values ($1::uuid, $2::uuid, 'pending', $3)", id, (*body)["customer_id"].asString(), total);
            for (const auto &item : (*body)["items"]) {
                tx->execSqlSync("insert into order_items (id, order_id, product_id, quantity, unit_price) values ($1::uuid, $2::uuid, $3::uuid, $4, $5)", utils::getUuid(), id, item["product_id"].asString(), item["quantity"].asInt(), item["unit_price"].asDouble());
            }
            Json::Value out;
            out["data"] = loadOrder(id);
            auto resp = HttpResponse::newHttpJsonResponse(out);
            resp->setStatusCode(k201Created);
            callback(resp);
            return;
        }
        int page = std::max(1, std::atoi(req->getParameter("page").empty() ? "1" : req->getParameter("page").c_str()));
        int limit = std::min(100, std::max(1, std::atoi(req->getParameter("limit").empty() ? "20" : req->getParameter("limit").c_str())));
        std::string sql = "select id::text from orders where 1=1";
        std::vector<std::string> values;
        auto customer = req->getParameter("customer_id");
        if (!customer.empty()) {
            if (!validUuid(customer)) {
                auto resp = HttpResponse::newHttpJsonResponse(apiError("invalid customer_id", "INVALID_ID"));
                resp->setStatusCode(k400BadRequest);
                callback(resp);
                return;
            }
            sql += " and customer_id = '" + customer + "'::uuid";
        }
        auto status = req->getParameter("status");
        if (!status.empty()) {
            if (!transitions.contains(status)) {
                auto resp = HttpResponse::newHttpJsonResponse(apiError("invalid status", "VALIDATION_ERROR"));
                resp->setStatusCode(k400BadRequest);
                callback(resp);
                return;
            }
            sql += " and status = '" + status + "'";
        }
        sql += " order by created_at desc limit " + std::to_string(limit) + " offset " + std::to_string((page - 1) * limit);
        auto rows = db()->execSqlSync(sql);
        Json::Value data(Json::arrayValue);
        for (const auto &row : rows) data.append(loadOrder(row[0].as<std::string>()));
        Json::Value out;
        out["data"] = data;
        out["total"] = static_cast<int>(data.size());
        out["page"] = page;
        out["limit"] = limit;
        callback(HttpResponse::newHttpJsonResponse(out));
    });

    app().registerHandler("/api/v1/orders/{1}", [](const HttpRequestPtr &req, std::function<void(const HttpResponsePtr &)> &&callback, const std::string &orderId) {
        if (!validUuid(orderId)) {
            auto resp = HttpResponse::newHttpJsonResponse(apiError("invalid order ID", "INVALID_ID"));
            resp->setStatusCode(k400BadRequest);
            callback(resp);
            return;
        }
        auto order = loadOrder(orderId);
        if (order.isNull()) {
            auto resp = HttpResponse::newHttpJsonResponse(apiError("order not found", "NOT_FOUND"));
            resp->setStatusCode(k404NotFound);
            callback(resp);
            return;
        }
        if (req->method() == Delete) {
            auto status = order["status"].asString();
            if (status != "pending" && status != "confirmed") {
                auto resp = HttpResponse::newHttpJsonResponse(apiError("only pending or confirmed orders can be cancelled", "INVALID_OPERATION"));
                resp->setStatusCode(k409Conflict);
                callback(resp);
                return;
            }
            db()->execSqlSync("update orders set status = 'cancelled', updated_at = now() where id = $1::uuid", orderId);
            auto resp = HttpResponse::newHttpResponse();
            resp->setStatusCode(k204NoContent);
            callback(resp);
            return;
        }
        Json::Value out;
        out["data"] = order;
        callback(HttpResponse::newHttpJsonResponse(out));
    });

    app().registerHandler("/api/v1/orders/{1}/status", [](const HttpRequestPtr &req, std::function<void(const HttpResponsePtr &)> &&callback, const std::string &orderId) {
        if (!validUuid(orderId)) {
            auto resp = HttpResponse::newHttpJsonResponse(apiError("invalid order ID", "INVALID_ID"));
            resp->setStatusCode(k400BadRequest);
            callback(resp);
            return;
        }
        auto body = req->getJsonObject();
        if (!body || !body->isMember("status") || !transitions.contains((*body)["status"].asString())) {
            auto resp = HttpResponse::newHttpJsonResponse(apiError("invalid status", "VALIDATION_ERROR"));
            resp->setStatusCode(k400BadRequest);
            callback(resp);
            return;
        }
        auto order = loadOrder(orderId);
        if (order.isNull()) {
            auto resp = HttpResponse::newHttpJsonResponse(apiError("order not found", "NOT_FOUND"));
            resp->setStatusCode(k404NotFound);
            callback(resp);
            return;
        }
        auto current = order["status"].asString();
        auto next = (*body)["status"].asString();
        if (!contains(transitions.at(current), next)) {
            auto resp = HttpResponse::newHttpJsonResponse(apiError("invalid status transition: " + current + " -> " + next, "INVALID_TRANSITION"));
            resp->setStatusCode(k409Conflict);
            callback(resp);
            return;
        }
        db()->execSqlSync("update orders set status = $1, updated_at = now() where id = $2::uuid", next, orderId);
        Json::Value out;
        out["data"] = loadOrder(orderId);
        callback(HttpResponse::newHttpJsonResponse(out));
    }, {Patch});

    auto port = std::atoi(std::getenv("SERVER_PORT") ? std::getenv("SERVER_PORT") : "8080");
    app().addListener("0.0.0.0", port).run();
}
