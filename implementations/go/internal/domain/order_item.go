package domain

import "github.com/google/uuid"

type OrderItem struct {
	ID        uuid.UUID `json:"id" db:"id"`
	OrderID   uuid.UUID `json:"order_id" db:"order_id"`
	ProductID uuid.UUID `json:"product_id" db:"product_id"`
	Quantity  int       `json:"quantity" db:"quantity"`
	UnitPrice float64   `json:"unit_price" db:"unit_price"`
}

func NewOrderItem(productID uuid.UUID, quantity int, unitPrice float64) OrderItem {
	return OrderItem{
		ID:        uuid.New(),
		ProductID: productID,
		Quantity:  quantity,
		UnitPrice: unitPrice,
	}
}
