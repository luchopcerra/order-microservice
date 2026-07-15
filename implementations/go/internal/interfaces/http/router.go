package http

import (
	"log/slog"
	"net/http"

	"github.com/go-chi/chi/v5"
	chiMiddleware "github.com/go-chi/chi/v5/middleware"

	"github.com/example/orders-service/internal/application"
)

func NewRouter(
	createOrder *application.CreateOrderUseCase,
	getOrder *application.GetOrderUseCase,
	listOrders *application.ListOrdersUseCase,
	updateOrderStatus *application.UpdateOrderStatusUseCase,
	log *slog.Logger,
) *chi.Mux {
	r := chi.NewRouter()

	r.Use(chiMiddleware.RealIP)
	r.Use(RequestID)
	r.Use(Logger(log))
	r.Use(Recovery)
	r.Use(CORS)
	r.Use(chiMiddleware.Compress(5))

	handler := NewOrderHandler(createOrder, getOrder, listOrders, updateOrderStatus)

	r.Route("/api/v1", func(r chi.Router) {
		r.Route("/orders", func(r chi.Router) {
			r.Post("/", handler.Create)
			r.Get("/", handler.List)
			r.Get("/{orderID}", handler.Get)
			r.Patch("/{orderID}/status", handler.UpdateStatus)
			r.Delete("/{orderID}", handler.Delete)
		})
	})

	r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
		respondJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	return r
}
