package application

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
	"github.com/example/orders-service/internal/domain"
)

type ListOrdersResult struct {
	Orders []*domain.Order `json:"orders"`
	Total  int             `json:"total"`
	Page   int             `json:"page"`
	Limit  int             `json:"limit"`
}

type ListOrdersUseCase struct {
	repo domain.OrderRepository
	log  *slog.Logger
}

func NewListOrdersUseCase(repo domain.OrderRepository, log *slog.Logger) *ListOrdersUseCase {
	return &ListOrdersUseCase{repo: repo, log: log}
}

func (uc *ListOrdersUseCase) Execute(ctx context.Context, filter domain.OrderFilter) (*ListOrdersResult, error) {
	if filter.Page < 1 {
		filter.Page = 1
	}
	if filter.Limit < 1 || filter.Limit > 100 {
		filter.Limit = 20
	}

	orders, total, err := uc.repo.List(ctx, filter)
	if err != nil {
		uc.log.Error("failed to list orders", "error", err)
		return nil, fmt.Errorf("list orders: %w", err)
	}

	return &ListOrdersResult{
		Orders: orders,
		Total:  total,
		Page:   filter.Page,
		Limit:  filter.Limit,
	}, nil
}

func parseOptionalUUID(s string) *uuid.UUID {
	if s == "" {
		return nil
	}
	id, err := uuid.Parse(s)
	if err != nil {
		return nil
	}
	return &id
}

func parseOptionalStatus(s string) *domain.OrderStatus {
	if s == "" {
		return nil
	}
	status := domain.OrderStatus(s)
	return &status
}
