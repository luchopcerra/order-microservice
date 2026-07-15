package postgres

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/google/uuid"
	"github.com/jmoiron/sqlx"

	"github.com/example/orders-service/internal/domain"
)

type OrderRepository struct {
	db *sqlx.DB
}

func NewOrderRepository(db *sqlx.DB) *OrderRepository {
	return &OrderRepository{db: db}
}

func (r *OrderRepository) Create(ctx context.Context, order *domain.Order) error {
	tx, err := r.db.BeginTxx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin transaction: %w", err)
	}
	defer tx.Rollback()

	query := `INSERT INTO orders (id, customer_id, status, total_amount, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6)`
	_, err = tx.ExecContext(ctx, query,
		order.ID, order.CustomerID, order.Status,
		order.TotalAmount, order.CreatedAt, order.UpdatedAt,
	)
	if err != nil {
		return fmt.Errorf("insert order: %w", err)
	}

	itemQuery := `INSERT INTO order_items (id, order_id, product_id, quantity, unit_price)
		VALUES ($1, $2, $3, $4, $5)`
	for _, item := range order.Items {
		_, err = tx.ExecContext(ctx, itemQuery,
			item.ID, item.OrderID, item.ProductID,
			item.Quantity, item.UnitPrice,
		)
		if err != nil {
			return fmt.Errorf("insert order item: %w", err)
		}
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit transaction: %w", err)
	}

	return nil
}

func (r *OrderRepository) GetByID(ctx context.Context, id uuid.UUID) (*domain.Order, error) {
	var order domain.Order
	query := `SELECT id, customer_id, status, total_amount, created_at, updated_at
		FROM orders WHERE id = $1`
	err := r.db.GetContext(ctx, &order, query, id)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, domain.ErrOrderNotFound
		}
		return nil, fmt.Errorf("get order: %w", err)
	}

	items, err := r.getItemsByOrderID(ctx, id)
	if err != nil {
		return nil, err
	}
	order.Items = items

	return &order, nil
}

func (r *OrderRepository) List(ctx context.Context, filter domain.OrderFilter) ([]*domain.Order, int, error) {
	where := "WHERE 1=1"
	args := []interface{}{}
	argIdx := 1

	if filter.CustomerID != nil {
		where += fmt.Sprintf(" AND customer_id = $%d", argIdx)
		args = append(args, *filter.CustomerID)
		argIdx++
	}
	if filter.Status != nil {
		where += fmt.Sprintf(" AND status = $%d", argIdx)
		args = append(args, *filter.Status)
		argIdx++
	}

	countQuery := fmt.Sprintf("SELECT COUNT(*) FROM orders %s", where)
	var total int
	if err := r.db.GetContext(ctx, &total, countQuery, args...); err != nil {
		return nil, 0, fmt.Errorf("count orders: %w", err)
	}

	offset := (filter.Page - 1) * filter.Limit
	query := fmt.Sprintf(`SELECT id, customer_id, status, total_amount, created_at, updated_at
		FROM orders %s ORDER BY created_at DESC LIMIT $%d OFFSET $%d`,
		where, argIdx, argIdx+1)
	args = append(args, filter.Limit, offset)

	var orders []*domain.Order
	if err := r.db.SelectContext(ctx, &orders, query, args...); err != nil {
		return nil, 0, fmt.Errorf("list orders: %w", err)
	}

	for _, order := range orders {
		items, err := r.getItemsByOrderID(ctx, order.ID)
		if err != nil {
			return nil, 0, err
		}
		order.Items = items
	}

	return orders, total, nil
}

func (r *OrderRepository) UpdateStatus(ctx context.Context, id uuid.UUID, status domain.OrderStatus) error {
	query := `UPDATE orders SET status = $1, updated_at = NOW() WHERE id = $2`
	result, err := r.db.ExecContext(ctx, query, status, id)
	if err != nil {
		return fmt.Errorf("update order status: %w", err)
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("get rows affected: %w", err)
	}
	if rows == 0 {
		return domain.ErrOrderNotFound
	}
	return nil
}

func (r *OrderRepository) Delete(ctx context.Context, id uuid.UUID) error {
	tx, err := r.db.BeginTxx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin transaction: %w", err)
	}
	defer tx.Rollback()

	_, err = tx.ExecContext(ctx, `DELETE FROM order_items WHERE order_id = $1`, id)
	if err != nil {
		return fmt.Errorf("delete order items: %w", err)
	}

	result, err := tx.ExecContext(ctx, `DELETE FROM orders WHERE id = $1`, id)
	if err != nil {
		return fmt.Errorf("delete order: %w", err)
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("get rows affected: %w", err)
	}
	if rows == 0 {
		return domain.ErrOrderNotFound
	}

	return tx.Commit()
}

func (r *OrderRepository) getItemsByOrderID(ctx context.Context, orderID uuid.UUID) ([]domain.OrderItem, error) {
	var items []domain.OrderItem
	query := `SELECT id, order_id, product_id, quantity, unit_price
		FROM order_items WHERE order_id = $1`
	if err := r.db.SelectContext(ctx, &items, query, orderID); err != nil {
		return nil, fmt.Errorf("get order items: %w", err)
	}
	return items, nil
}
