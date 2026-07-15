package postgres

import (
	"fmt"
	"log/slog"
	"time"

	"github.com/jmoiron/sqlx"
	_ "github.com/lib/pq"

	"github.com/example/orders-service/internal/infrastructure/config"
)

func NewConnection(cfg config.DatabaseConfig, log *slog.Logger) (*sqlx.DB, error) {
	db, err := sqlx.Open("postgres", cfg.DSN())
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}

	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(5 * time.Minute)

	log.Info("connecting to database",
		"host", cfg.Host,
		"port", cfg.Port,
		"database", cfg.Name,
	)

	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("ping database: %w", err)
	}

	log.Info("database connection established")
	return db, nil
}
