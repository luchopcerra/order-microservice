package domain

import "errors"

var (
	ErrOrderNotFound          = errors.New("order not found")
	ErrInvalidCustomerID      = errors.New("invalid customer ID")
	ErrEmptyOrder             = errors.New("order must have at least one item")
	ErrInvalidQuantity        = errors.New("item quantity must be greater than zero")
	ErrInvalidPrice           = errors.New("item price cannot be negative")
	ErrInvalidStatusTransition = errors.New("invalid status transition")
	ErrOrderAlreadyCancelled  = errors.New("order is already cancelled")
)
