package com.qe.demo;

import java.util.Objects;

public class OrderService {

    private final InventoryClient inventoryClient;
    private final PaymentProcessor paymentProcessor;

    public OrderService(InventoryClient inventoryClient, PaymentProcessor paymentProcessor) {
        this.inventoryClient = Objects.requireNonNull(inventoryClient, "InventoryClient cannot be null");
        this.paymentProcessor = Objects.requireNonNull(paymentProcessor, "PaymentProcessor cannot be null");
    }

    public boolean placeOrder(String orderId, String customerEmail, String accountId, String productId, int quantity, double unitPrice, double discountRate) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Order ID cannot be empty");
        }
        if (customerEmail == null || customerEmail.isBlank()) {
            throw new IllegalArgumentException("Customer email cannot be empty");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Account ID cannot be empty");
        }
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("Product ID cannot be empty");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }
        if (unitPrice <= 0.0) {
            throw new IllegalArgumentException("Unit price must be positive");
        }
        if (discountRate < 0.0 || discountRate > 1.0) {
            throw new IllegalArgumentException("Discount rate must be between 0.0 and 1.0");
        }

        if (!inventoryClient.isInStock(productId, quantity)) {
            return false;
        }

        double subtotal = unitPrice * quantity;
        double finalAmount = subtotal * (1.0 - discountRate);

        boolean paymentSuccess = paymentProcessor.executeTransaction(customerEmail, accountId, finalAmount);
        if (!paymentSuccess) {
            return false;
        }

        inventoryClient.deductStock(productId, quantity);
        return true;
    }

    public double calculateTotalWithTax(double subtotal, double taxRate) {
        if (subtotal < 0.0) {
            throw new IllegalArgumentException("Subtotal cannot be negative");
        }
        if (taxRate < 0.0 || taxRate > 0.5) {
            throw new IllegalArgumentException("Tax rate must be between 0.0 and 0.5");
        }
        return Math.round((subtotal + (subtotal * taxRate)) * 100.0) / 100.0;
    }
}
