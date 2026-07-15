package application

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
	"github.com/example/orders-service/internal/domain"
)

type GetOrderUseCase struct {
	repo domain.OrderRepository
	log  *slog.Logger
}

func NewGetOrderUseCase(repo domain.OrderRepository, log *slog.Logger) *GetOrderUseCase {
	return &GetOrderUseCase{repo: repo, log: log}
}

func (uc *GetOrderUseCase) Execute(ctx context.Context, id uuid.UUID) (*domain.Order, error) {
	order, err := uc.repo.GetByID(ctx, id)
	if err != nil {
		uc.log.Warn("order not found", "order_id", id)
		return nil, fmt.Errorf("get order: %w", err)
	}
	return order, nil
}
