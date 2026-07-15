package application

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
	"github.com/example/orders-service/internal/domain"
)

type CreateOrderRequest struct {
	CustomerID uuid.UUID              `json:"customer_id"`
	Items      []CreateOrderItemRequest `json:"items"`
}

type CreateOrderItemRequest struct {
	ProductID uuid.UUID `json:"product_id"`
	Quantity  int       `json:"quantity"`
	UnitPrice float64   `json:"unit_price"`
}

type CreateOrderUseCase struct {
	repo domain.OrderRepository
	log  *slog.Logger
}

func NewCreateOrderUseCase(repo domain.OrderRepository, log *slog.Logger) *CreateOrderUseCase {
	return &CreateOrderUseCase{repo: repo, log: log}
}

func (uc *CreateOrderUseCase) Execute(ctx context.Context, req CreateOrderRequest) (*domain.Order, error) {
	items := make([]domain.OrderItem, len(req.Items))
	for i, item := range req.Items {
		items[i] = domain.NewOrderItem(item.ProductID, item.Quantity, item.UnitPrice)
	}

	order, err := domain.NewOrder(req.CustomerID, items)
	if err != nil {
		uc.log.Warn("invalid order creation request",
			"customer_id", req.CustomerID,
			"error", err,
		)
		return nil, fmt.Errorf("create order: %w", err)
	}

	if err := uc.repo.Create(ctx, order); err != nil {
		uc.log.Error("failed to create order",
			"order_id", order.ID,
			"error", err,
		)
		return nil, fmt.Errorf("create order: %w", err)
	}

	uc.log.Info("order created successfully",
		"order_id", order.ID,
		"customer_id", order.CustomerID,
		"total", order.TotalAmount,
	)

	return order, nil
}
