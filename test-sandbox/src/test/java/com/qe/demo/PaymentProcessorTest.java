package com.qe.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PaymentProcessorTest {

    @InjectMocks
    private PaymentProcessor paymentProcessor;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private NotificationService notificationService;

    @Test
    void executeTransaction_HappyPath() {
        // Arrange
        String customerEmail = "customer@example.com";
        String accountId = "12345";
        double amount = 100.0;
        when(paymentGateway.processPayment(accountId, amount)).thenReturn(true);

        // Act
        boolean result = paymentProcessor.executeTransaction(customerEmail, accountId, amount);

        // Assert
        assertTrue(result);
        verify(paymentGateway).processPayment(accountId, amount);
        verify(notificationService).sendReceipt(customerEmail, amount);
    }

    @Test
    void executeTransaction_InvalidAmount() {
        // Arrange
        String customerEmail = "customer@example.com";
        String accountId = "12345";
        double amount = 0.0;

        // Act and Assert
        assertThrows(IllegalArgumentException.class, () -> paymentProcessor.executeTransaction(customerEmail, accountId, amount));
    }

    @Test
    void executeTransaction_PaymentGatewayFailure() {
        // Arrange
        String customerEmail = "customer@example.com";
        String accountId = "12345";
        double amount = 100.0;
        when(paymentGateway.processPayment(accountId, amount)).thenReturn(false);

        // Act
        boolean result = paymentProcessor.executeTransaction(customerEmail, accountId, amount);

        // Assert
        assertFalse(result);
        verify(paymentGateway).processPayment(accountId, amount);
    }
}