package com.qe.demo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ShoppingCartService - Manages e-commerce cart operations, discount rules, and checkouts.
 */
public class ShoppingCartService {

    public record CartItem(String itemId, String itemName, double unitPrice, int quantity) {
        public CartItem {
            Objects.requireNonNull(itemId, "itemId cannot be null");
            Objects.requireNonNull(itemName, "itemName cannot be null");
            if (unitPrice < 0) {
                throw new IllegalArgumentException("unitPrice cannot be negative");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }

        public double getTotalPrice() {
            return unitPrice * quantity;
        }
    }

    private final List<CartItem> items = new ArrayList<>();
    private final PaymentGateway paymentGateway;
    private final NotificationService notificationService;
    private double discountPercentage = 0.0;

    public ShoppingCartService(PaymentGateway paymentGateway, NotificationService notificationService) {
        this.paymentGateway = Objects.requireNonNull(paymentGateway, "paymentGateway cannot be null");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService cannot be null");
    }

    public void addItem(CartItem item) {
        Objects.requireNonNull(item, "item cannot be null");
        items.add(item);
    }

    public boolean removeItem(String itemId) {
        Objects.requireNonNull(itemId, "itemId cannot be null");
        return items.removeIf(item -> item.itemId().equals(itemId));
    }

    public List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public int getItemCount() {
        return items.stream().mapToInt(CartItem::quantity).sum();
    }

    public double calculateSubtotal() {
        return items.stream().mapToDouble(CartItem::getTotalPrice).sum();
    }

    public void applyCoupon(String couponCode) {
        Objects.requireNonNull(couponCode, "couponCode cannot be null");
        if (couponCode.equalsIgnoreCase("SAVE10")) {
            this.discountPercentage = 0.10;
        } else if (couponCode.equalsIgnoreCase("SAVE20")) {
            this.discountPercentage = 0.20;
        } else if (couponCode.equalsIgnoreCase("VIP50")) {
            this.discountPercentage = 0.50;
        } else {
            throw new IllegalArgumentException("Invalid coupon code: " + couponCode);
        }
    }

    public double calculateTotal() {
        double subtotal = calculateSubtotal();
        double discount = subtotal * discountPercentage;
        double tax = (subtotal - discount) * 0.08; // 8% sales tax
        return (subtotal - discount) + tax;
    }

    public boolean checkout(String customerEmail, String accountId) {
        Objects.requireNonNull(customerEmail, "customerEmail cannot be null");
        Objects.requireNonNull(accountId, "accountId cannot be null");

        if (items.isEmpty()) {
            throw new IllegalStateException("Cannot checkout an empty shopping cart");
        }

        double totalAmount = calculateTotal();
        boolean paymentSuccess = paymentGateway.processPayment(accountId, totalAmount);

        if (paymentSuccess) {
            notificationService.sendReceipt(customerEmail, totalAmount);
            items.clear();
            discountPercentage = 0.0;
            return true;
        }

        return false;
    }

    public void clearCart() {
        items.clear();
        discountPercentage = 0.0;
    }
}
