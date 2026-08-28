package com.qe.demo;

public class PaymentProcessor {
    private final PaymentGateway paymentGateway;
    private final NotificationService notificationService;

    public PaymentProcessor(PaymentGateway paymentGateway, NotificationService notificationService) {
        this.paymentGateway = paymentGateway;
        this.notificationService = notificationService;
    }

    public boolean executeTransaction(String customerEmail, String accountId, double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Invalid transaction amount");
        }

        boolean success = paymentGateway.processPayment(accountId, amount);
        if (success) {
            notificationService.sendReceipt(customerEmail, amount);
            return true;
        }
        return false;
    }
}