package http

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/example/orders-service/internal/application"
	"github.com/example/orders-service/internal/domain"
)

type OrderHandler struct {
	createOrder       *application.CreateOrderUseCase
	getOrder          *application.GetOrderUseCase
	listOrders        *application.ListOrdersUseCase
	updateOrderStatus *application.UpdateOrderStatusUseCase
}

func NewOrderHandler(
	createOrder *application.CreateOrderUseCase,
	getOrder *application.GetOrderUseCase,
	listOrders *application.ListOrdersUseCase,
	updateOrderStatus *application.UpdateOrderStatusUseCase,
) *OrderHandler {
	return &OrderHandler{
		createOrder:       createOrder,
		getOrder:          getOrder,
		listOrders:        listOrders,
		updateOrderStatus: updateOrderStatus,
	}
}

func (h *OrderHandler) Create(w http.ResponseWriter, r *http.Request) {
	var req application.CreateOrderRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		respondError(w, http.StatusBadRequest, "invalid request body", "INVALID_BODY")
		return
	}

	order, err := h.createOrder.Execute(r.Context(), req)
	if err != nil {
		switch {
		case errors.Is(err, domain.ErrInvalidCustomerID),
			errors.Is(err, domain.ErrEmptyOrder),
			errors.Is(err, domain.ErrInvalidQuantity),
			errors.Is(err, domain.ErrInvalidPrice):
			respondError(w, http.StatusBadRequest, err.Error(), "VALIDATION_ERROR")
		default:
			respondError(w, http.StatusInternalServerError, "failed to create order", "INTERNAL_ERROR")
		}
		return
	}

	respondJSON(w, http.StatusCreated, SuccessResponse{Data: order})
}

func (h *OrderHandler) Get(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(chi.URLParam(r, "orderID"))
	if err != nil {
		respondError(w, http.StatusBadRequest, "invalid order ID", "INVALID_ID")
		return
	}

	order, err := h.getOrder.Execute(r.Context(), id)
	if err != nil {
		if errors.Is(err, domain.ErrOrderNotFound) {
			respondError(w, http.StatusNotFound, "order not found", "NOT_FOUND")
			return
		}
		respondError(w, http.StatusInternalServerError, "failed to get order", "INTERNAL_ERROR")
		return
	}

	respondJSON(w, http.StatusOK, SuccessResponse{Data: order})
}

func (h *OrderHandler) List(w http.ResponseWriter, r *http.Request) {
	filter := domain.OrderFilter{
		Page:  parseQueryInt(r, "page", 1),
		Limit: parseQueryInt(r, "limit", 20),
	}

	if customerIDStr := r.URL.Query().Get("customer_id"); customerIDStr != "" {
		id, err := uuid.Parse(customerIDStr)
		if err != nil {
			respondError(w, http.StatusBadRequest, "invalid customer_id", "INVALID_ID")
			return
		}
		filter.CustomerID = &id
	}

	if statusStr := r.URL.Query().Get("status"); statusStr != "" {
		status := domain.OrderStatus(statusStr)
		filter.Status = &status
	}

	result, err := h.listOrders.Execute(r.Context(), filter)
	if err != nil {
		respondError(w, http.StatusInternalServerError, "failed to list orders", "INTERNAL_ERROR")
		return
	}

	respondJSON(w, http.StatusOK, PaginatedResponse{
		Data:  result.Orders,
		Total: result.Total,
		Page:  result.Page,
		Limit: result.Limit,
	})
}

func (h *OrderHandler) UpdateStatus(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(chi.URLParam(r, "orderID"))
	if err != nil {
		respondError(w, http.StatusBadRequest, "invalid order ID", "INVALID_ID")
		return
	}

	var req application.UpdateOrderStatusRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		respondError(w, http.StatusBadRequest, "invalid request body", "INVALID_BODY")
		return
	}

	order, err := h.updateOrderStatus.Execute(r.Context(), id, req.Status)
	if err != nil {
		switch {
		case errors.Is(err, domain.ErrOrderNotFound):
			respondError(w, http.StatusNotFound, "order not found", "NOT_FOUND")
		case errors.Is(err, domain.ErrInvalidStatusTransition):
			respondError(w, http.StatusConflict, err.Error(), "INVALID_TRANSITION")
		default:
			respondError(w, http.StatusInternalServerError, "failed to update order status", "INTERNAL_ERROR")
		}
		return
	}

	respondJSON(w, http.StatusOK, SuccessResponse{Data: order})
}

func (h *OrderHandler) Delete(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(chi.URLParam(r, "orderID"))
	if err != nil {
		respondError(w, http.StatusBadRequest, "invalid order ID", "INVALID_ID")
		return
	}

	order, err := h.getOrder.Execute(r.Context(), id)
	if err != nil {
		if errors.Is(err, domain.ErrOrderNotFound) {
			respondError(w, http.StatusNotFound, "order not found", "NOT_FOUND")
			return
		}
		respondError(w, http.StatusInternalServerError, "failed to get order", "INTERNAL_ERROR")
		return
	}

	if order.Status != domain.StatusPending && order.Status != domain.StatusConfirmed {
		respondError(w, http.StatusConflict, "only pending or confirmed orders can be cancelled", "INVALID_OPERATION")
		return
	}

	if _, err := h.updateOrderStatus.Execute(r.Context(), id, domain.StatusCancelled); err != nil {
		if errors.Is(err, domain.ErrInvalidStatusTransition) {
			respondError(w, http.StatusConflict, err.Error(), "INVALID_TRANSITION")
			return
		}
		respondError(w, http.StatusInternalServerError, "failed to cancel order", "INTERNAL_ERROR")
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func parseQueryInt(r *http.Request, key string, defaultValue int) int {
	valueStr := r.URL.Query().Get(key)
	if valueStr == "" {
		return defaultValue
	}
	value, err := strconv.Atoi(valueStr)
	if err != nil {
		return defaultValue
	}
	return value
}
