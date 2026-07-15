package domain

import (
	"context"

	"github.com/google/uuid"
)

type OrderFilter struct {
	CustomerID *uuid.UUID
	Status     *OrderStatus
	Page       int
	Limit      int
}

type OrderRepository interface {
	Create(ctx context.Context, order *Order) error
	GetByID(ctx context.Context, id uuid.UUID) (*Order, error)
	List(ctx context.Context, filter OrderFilter) ([]*Order, int, error)
	UpdateStatus(ctx context.Context, id uuid.UUID, status OrderStatus) error
	Delete(ctx context.Context, id uuid.UUID) error
}
