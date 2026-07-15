//go:build integration
// +build integration

package integration

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jmoiron/sqlx"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/stretchr/testify/suite"

	"github.com/example/orders-service/internal/domain"
	"github.com/example/orders-service/internal/infrastructure/postgres"
)

type OrderRepositoryTestSuite struct {
	suite.Suite
	db   *sqlx.DB
	repo *postgres.OrderRepository
	ctx  context.Context
}

func (s *OrderRepositoryTestSuite) SetupSuite() {
	dbURL := os.Getenv("TEST_DATABASE_URL")
	if dbURL == "" {
		dbURL = "postgres://postgres:postgres@localhost:5432/orders_test?sslmode=disable"
	}

	var err error
	s.db, err = sqlx.Open("postgres", dbURL)
	require.NoError(s.T(), err)

	s.db.SetMaxOpenConns(5)
	require.NoError(s.T(), s.db.Ping())

	s.repo = postgres.NewOrderRepository(s.db)
	s.ctx = context.Background()

	s.migrate()
}

func (s *OrderRepositoryTestSuite) TearDownSuite() {
	s.migrateDown()
	s.db.Close()
}

func (s *OrderRepositoryTestSuite) SetupTest() {
	s.db.Exec("DELETE FROM order_items")
	s.db.Exec("DELETE FROM orders")
}

func (s *OrderRepositoryTestSuite) migrate() {
	s.db.Exec(`
		CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
		CREATE TABLE IF NOT EXISTS orders (
			id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
			customer_id UUID NOT NULL,
			status VARCHAR(20) NOT NULL DEFAULT 'pending',
			total_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
			created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
			updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
		);
		CREATE TABLE IF NOT EXISTS order_items (
			id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
			order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
			product_id UUID NOT NULL,
			quantity INTEGER NOT NULL CHECK (quantity > 0),
			unit_price DECIMAL(10, 2) NOT NULL CHECK (unit_price >= 0),
			created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
		);
	`)
}

func (s *OrderRepositoryTestSuite) migrateDown() {
	s.db.Exec("DROP TABLE IF EXISTS order_items")
	s.db.Exec("DROP TABLE IF EXISTS orders")
}

func (s *OrderRepositoryTestSuite) createTestOrder() *domain.Order {
	customerID := uuid.New()
	items := []domain.OrderItem{
		domain.NewOrderItem(uuid.New(), 2, 29.99),
		domain.NewOrderItem(uuid.New(), 1, 49.99),
	}
	order, err := domain.NewOrder(customerID, items)
	require.NoError(s.T(), err)
	return order
}

func (s *OrderRepositoryTestSuite) TestCreateAndGetOrder() {
	order := s.createTestOrder()

	err := s.repo.Create(s.ctx, order)
	require.NoError(s.T(), err)

	fetched, err := s.repo.GetByID(s.ctx, order.ID)
	require.NoError(s.T(), err)

	assert.Equal(s.T(), order.ID, fetched.ID)
	assert.Equal(s.T(), order.CustomerID, fetched.CustomerID)
	assert.Equal(s.T(), domain.StatusPending, fetched.Status)
	assert.Len(s.T(), fetched.Items, 2)
	assert.InDelta(s.T(), order.TotalAmount, fetched.TotalAmount, 0.01)
}

func (s *OrderRepositoryTestSuite) TestGetOrderByIDNotFound() {
	_, err := s.repo.GetByID(s.ctx, uuid.New())
	assert.ErrorIs(s.T(), err, domain.ErrOrderNotFound)
}

func (s *OrderRepositoryTestSuite) TestListOrders() {
	for i := 0; i < 5; i++ {
		order := s.createTestOrder()
		require.NoError(s.T(), s.repo.Create(s.ctx, order))
	}

	filter := domain.OrderFilter{Page: 1, Limit: 10}
	orders, total, err := s.repo.List(s.ctx, filter)
	require.NoError(s.T(), err)

	assert.Equal(s.T(), 5, total)
	assert.Len(s.T(), orders, 5)
}

func (s *OrderRepositoryTestSuite) TestListOrdersWithStatusFilter() {
	order1 := s.createTestOrder()
	require.NoError(s.T(), s.repo.Create(s.ctx, order1))

	order2 := s.createTestOrder()
	require.NoError(s.T(), s.repo.Create(s.ctx, order2))
	require.NoError(s.T(), s.repo.UpdateStatus(s.ctx, order2.ID, domain.StatusConfirmed))

	status := domain.StatusPending
	filter := domain.OrderFilter{Page: 1, Limit: 10, Status: &status}
	orders, total, err := s.repo.List(s.ctx, filter)
	require.NoError(s.T(), err)

	assert.Equal(s.T(), 1, total)
	assert.Len(s.T(), orders, 1)
}

func (s *OrderRepositoryTestSuite) TestUpdateStatus() {
	order := s.createTestOrder()
	require.NoError(s.T(), s.repo.Create(s.ctx, order))

	err := s.repo.UpdateStatus(s.ctx, order.ID, domain.StatusConfirmed)
	require.NoError(s.T(), err)

	fetched, err := s.repo.GetByID(s.ctx, order.ID)
	require.NoError(s.T(), err)
	assert.Equal(s.T(), domain.StatusConfirmed, fetched.Status)
}

func (s *OrderRepositoryTestSuite) TestDeleteOrder() {
	order := s.createTestOrder()
	require.NoError(s.T(), s.repo.Create(s.ctx, order))

	err := s.repo.Delete(s.ctx, order.ID)
	require.NoError(s.T(), err)

	_, err = s.repo.GetByID(s.ctx, order.ID)
	assert.ErrorIs(s.T(), err, domain.ErrOrderNotFound)
}

func (s *OrderRepositoryTestSuite) TestDeleteOrderNotFound() {
	err := s.repo.Delete(s.ctx, uuid.New())
	assert.ErrorIs(s.T(), err, domain.ErrOrderNotFound)
}

func TestOrderRepository(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test")
	}
	suite.Run(t, new(OrderRepositoryTestSuite))
}

func init() {
	_ = time.Now()
}
