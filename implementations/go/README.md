# Orders Service

Orders Service is a backend service for managing customer orders from creation through fulfillment. It provides a single place to record what a customer ordered, calculate the order total, track the current status, and retrieve order history.

## What It Does

The service manages the core order lifecycle for a commerce or fulfillment system. It supports:

- Creating orders for a customer with one or more products.
- Calculating the total order amount from item quantities and prices.
- Retrieving a specific order by its identifier.
- Listing orders with optional filtering by customer or status.
- Updating an order as it moves through fulfillment.
- Canceling eligible orders before they are too far along in the process.
- Reporting service health for operational monitoring.

## Order Lifecycle

An order starts as `pending` when it is created. From there, it can move through the following business states:

- `pending`: The order has been created but not yet confirmed.
- `confirmed`: The order has been accepted for fulfillment.
- `shipped`: The order has left the fulfillment process and is on its way to the customer.
- `delivered`: The order has been completed successfully.
- `cancelled`: The order is no longer active.

The service enforces valid status changes so orders follow a predictable lifecycle. Completed or cancelled orders cannot continue moving through the fulfillment process.

## Core Concepts

An order belongs to a customer and contains one or more order items. Each item represents a product, quantity, and unit price. The service uses these item details to determine the order total and keeps timestamps for when the order was created and last updated.

## API Surface

The service exposes high-level order operations:

- Create an order.
- List orders.
- Get an order by ID.
- Update an order status.
- Cancel an order.
- Check service health.

The API contract is documented in the repository root at `../../api-spec.yaml`.

## Service Boundaries

This service owns order records and order status management. It does not describe customer profiles, product catalog details, payment processing, shipping provider integration, or inventory management. Those concerns are expected to live in separate services or systems that can coordinate with Orders Service.
