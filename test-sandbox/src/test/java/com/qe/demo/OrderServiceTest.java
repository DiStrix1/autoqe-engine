package com.qe.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private PaymentProcessor paymentProcessor;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(inventoryClient, paymentProcessor);
    }

    @Test
    @DisplayName("placeOrder succeeds when inventory and payment are confirmed")
    void testPlaceOrderSuccess() {
        String orderId = "ORD-101";
        String email = "buyer@example.com";
        String accountId = "ACC-99";
        String productId = "PROD-A";
        int quantity = 2;
        double unitPrice = 100.0;
        double discountRate = 0.10; // 10% off -> subtotal $200, final $180

        when(inventoryClient.isInStock(productId, quantity)).thenReturn(true);
        when(paymentProcessor.executeTransaction(email, accountId, 180.0)).thenReturn(true);

        boolean result = orderService.placeOrder(orderId, email, accountId, productId, quantity, unitPrice, discountRate);

        assertTrue(result);
        verify(inventoryClient, times(1)).deductStock(productId, quantity);
        verify(paymentProcessor, times(1)).executeTransaction(email, accountId, 180.0);
    }

    @Test
    @DisplayName("placeOrder fails and does not deduct stock when item is out of stock")
    void testPlaceOrderOutOfStock() {
        when(inventoryClient.isInStock("PROD-B", 5)).thenReturn(false);

        boolean result = orderService.placeOrder("ORD-102", "buyer@example.com", "ACC-99", "PROD-B", 5, 50.0, 0.0);

        assertFalse(result);
        verify(paymentProcessor, never()).executeTransaction(anyString(), anyString(), anyDouble());
        verify(inventoryClient, never()).deductStock(anyString(), anyInt());
    }

    @Test
    @DisplayName("placeOrder fails when payment is rejected")
    void testPlaceOrderPaymentFailed() {
        when(inventoryClient.isInStock("PROD-C", 1)).thenReturn(true);
        when(paymentProcessor.executeTransaction("buyer@example.com", "ACC-99", 200.0)).thenReturn(false);

        boolean result = orderService.placeOrder("ORD-103", "buyer@example.com", "ACC-99", "PROD-C", 1, 200.0, 0.0);

        assertFalse(result);
        verify(inventoryClient, never()).deductStock(anyString(), anyInt());
    }

    @Test
    @DisplayName("placeOrder throws IllegalArgumentException for negative quantity or bad discount")
    void testPlaceOrderValidationExceptions() {
        assertThrows(IllegalArgumentException.class, () ->
            orderService.placeOrder("ORD-104", "buyer@example.com", "ACC-99", "PROD-D", 0, 100.0, 0.0));

        assertThrows(IllegalArgumentException.class, () ->
            orderService.placeOrder("ORD-105", "buyer@example.com", "ACC-99", "PROD-E", 1, -10.0, 0.0));

        assertThrows(IllegalArgumentException.class, () ->
            orderService.placeOrder("ORD-106", "buyer@example.com", "ACC-99", "PROD-F", 1, 100.0, 1.5));
    }

    @Test
    @DisplayName("calculateTotalWithTax correctly computes subtotal plus tax")
    void testCalculateTotalWithTax() {
        // $100 subtotal + 10% tax = $110.00
        double result = orderService.calculateTotalWithTax(100.0, 0.10);
        assertEquals(110.0, result, 0.001);

        // $49.99 subtotal + 8% tax = $53.99
        double taxedResult = orderService.calculateTotalWithTax(49.99, 0.08);
        assertEquals(53.99, taxedResult, 0.001);
    }
}
