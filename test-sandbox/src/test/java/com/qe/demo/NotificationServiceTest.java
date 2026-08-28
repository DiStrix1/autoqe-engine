package com.qe.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@link NotificationService} interface.
 *
 * <p>Because {@code NotificationService} is an interface (not a concrete class),
 * all tests verify the contract via a Mockito mock — the real implementation
 * would be provided at integration time.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationService notificationService;

    @Test
    void notificationService_isMocked() {
        assertNotNull(notificationService);
    }

    // -------------------------------------------------------------------------
    // sendReceipt — happy path
    // -------------------------------------------------------------------------

    @Test
    void sendReceipt_validEmailAndPositiveAmount_invokesWithCorrectArguments() {
        // Arrange — void method is a no-op on Mockito mock by default
        String email = "user@example.com";
        double amount = 99.99;

        // Act
        notificationService.sendReceipt(email, amount);

        // Assert — the method was called exactly once with the expected arguments
        verify(notificationService, times(1)).sendReceipt("user@example.com", 99.99);
    }

    @Test
    void sendReceipt_zeroAmount_invokesCorrectly() {
        // Arrange
        String email = "zero@example.com";
        double amount = 0.0;

        // Act
        notificationService.sendReceipt(email, amount);

        // Assert
        verify(notificationService).sendReceipt("zero@example.com", 0.0);
    }

    @Test
    void sendReceipt_largeAmount_invokesCorrectly() {
        // Arrange
        String email = "big@example.com";
        double amount = 1_000_000.00;

        // Act
        notificationService.sendReceipt(email, amount);

        // Assert
        verify(notificationService).sendReceipt("big@example.com", 1_000_000.00);
    }

    @Test
    void sendReceipt_isNeverCalledUnlessExplicitlyInvoked() {
        // Verify that no spurious calls occur when the method is not triggered
        verify(notificationService, never()).sendReceipt(anyString(), anyDouble());
    }
}
