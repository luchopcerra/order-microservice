package application

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
	"github.com/example/orders-service/internal/domain"
)

type UpdateOrderStatusRequest struct {
	Status domain.OrderStatus `json:"status"`
}

type UpdateOrderStatusUseCase struct {
	repo domain.OrderRepository
	log  *slog.Logger
}

func NewUpdateOrderStatusUseCase(repo domain.OrderRepository, log *slog.Logger) *UpdateOrderStatusUseCase {
	return &UpdateOrderStatusUseCase{repo: repo, log: log}
}

func (uc *UpdateOrderStatusUseCase) Execute(ctx context.Context, id uuid.UUID, newStatus domain.OrderStatus) (*domain.Order, error) {
	order, err := uc.repo.GetByID(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("update order status: %w", err)
	}

	if !order.CanTransitionTo(newStatus) {
		uc.log.Warn("invalid status transition",
			"order_id", id,
			"from", order.Status,
			"to", newStatus,
		)
		return nil, fmt.Errorf("%w: %s -> %s", domain.ErrInvalidStatusTransition, order.Status, newStatus)
	}

	if err := uc.repo.UpdateStatus(ctx, id, newStatus); err != nil {
		uc.log.Error("failed to update order status",
			"order_id", id,
			"error", err,
		)
		return nil, fmt.Errorf("update order status: %w", err)
	}

	order.Status = newStatus
	uc.log.Info("order status updated",
		"order_id", id,
		"new_status", newStatus,
	)

	return order, nil
}
