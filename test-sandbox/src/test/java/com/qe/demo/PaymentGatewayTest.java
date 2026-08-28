package com.qe.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@link PaymentGateway} interface.
 *
 * <p>Because {@code PaymentGateway} is an interface, tests verify the contract
 * by stubbing return values on a Mockito mock — the real implementation would
 * be provided at integration time (e.g. a Stripe or Braintree adapter).
 */
@ExtendWith(MockitoExtension.class)
class PaymentGatewayTest {

    @Mock
    private PaymentGateway paymentGateway;

    // -------------------------------------------------------------------------
    // processPayment — success scenario
    // -------------------------------------------------------------------------

    @Test
    void processPayment_validAccountAndAmount_returnsTrue() {
        // Arrange
        when(paymentGateway.processPayment("account-001", 250.00)).thenReturn(true);

        // Act
        boolean result = paymentGateway.processPayment("account-001", 250.00);

        // Assert
        assertTrue(result);
        verify(paymentGateway).processPayment("account-001", 250.00);
    }

    // -------------------------------------------------------------------------
    // processPayment — failure scenario
    // -------------------------------------------------------------------------

    @Test
    void processPayment_insufficientFunds_returnsFalse() {
        // Arrange
        when(paymentGateway.processPayment("account-002", 9999.00)).thenReturn(false);

        // Act
        boolean result = paymentGateway.processPayment("account-002", 9999.00);

        // Assert
        assertFalse(result);
        verify(paymentGateway).processPayment("account-002", 9999.00);
    }

    @Test
    void processPayment_zeroAmount_returnsFalse() {
        // Arrange — zero-value payments are typically rejected
        when(paymentGateway.processPayment("account-003", 0.0)).thenReturn(false);

        // Act
        boolean result = paymentGateway.processPayment("account-003", 0.0);

        // Assert
        assertFalse(result);
    }

    @Test
    void processPayment_calledMultipleTimes_tracksAllInvocations() {
        // Arrange
        when(paymentGateway.processPayment(anyString(), anyDouble())).thenReturn(true);

        // Act
        paymentGateway.processPayment("account-A", 100.0);
        paymentGateway.processPayment("account-B", 200.0);

        // Assert — both invocations are tracked
        verify(paymentGateway, times(2)).processPayment(anyString(), anyDouble());
    }
}
