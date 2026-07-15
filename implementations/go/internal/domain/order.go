package domain

import (
	"fmt"
	"time"

	"github.com/google/uuid"
)

type OrderStatus string

const (
	StatusPending   OrderStatus = "pending"
	StatusConfirmed OrderStatus = "confirmed"
	StatusShipped   OrderStatus = "shipped"
	StatusDelivered OrderStatus = "delivered"
	StatusCancelled OrderStatus = "cancelled"
)

var validTransitions = map[OrderStatus][]OrderStatus{
	StatusPending:   {StatusConfirmed, StatusCancelled},
	StatusConfirmed: {StatusShipped, StatusCancelled},
	StatusShipped:   {StatusDelivered},
	StatusDelivered: {},
	StatusCancelled: {},
}

type Order struct {
	ID          uuid.UUID   `json:"id" db:"id"`
	CustomerID  uuid.UUID   `json:"customer_id" db:"customer_id"`
	Status      OrderStatus `json:"status" db:"status"`
	Items       []OrderItem `json:"items"`
	TotalAmount float64     `json:"total_amount" db:"total_amount"`
	CreatedAt   time.Time   `json:"created_at" db:"created_at"`
	UpdatedAt   time.Time   `json:"updated_at" db:"updated_at"`
}

func NewOrder(customerID uuid.UUID, items []OrderItem) (*Order, error) {
	if customerID == uuid.Nil {
		return nil, ErrInvalidCustomerID
	}
	if len(items) == 0 {
		return nil, ErrEmptyOrder
	}

	var total float64
	for i := range items {
		if items[i].Quantity <= 0 {
			return nil, ErrInvalidQuantity
		}
		if items[i].UnitPrice < 0 {
			return nil, ErrInvalidPrice
		}
		total += items[i].UnitPrice * float64(items[i].Quantity)
	}

	now := time.Now().UTC()
	order := &Order{
		ID:          uuid.New(),
		CustomerID:  customerID,
		Status:      StatusPending,
		Items:       items,
		TotalAmount: total,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	for i := range order.Items {
		order.Items[i].ID = uuid.New()
		order.Items[i].OrderID = order.ID
	}

	return order, nil
}

func (o *Order) CanTransitionTo(newStatus OrderStatus) bool {
	allowed, ok := validTransitions[o.Status]
	if !ok {
		return false
	}
	for _, s := range allowed {
		if s == newStatus {
			return true
		}
	}
	return false
}

func (o *Order) TransitionTo(newStatus OrderStatus) error {
	if !o.CanTransitionTo(newStatus) {
		return fmt.Errorf("%w: %s -> %s", ErrInvalidStatusTransition, o.Status, newStatus)
	}
	o.Status = newStatus
	o.UpdatedAt = time.Now().UTC()
	return nil
}
