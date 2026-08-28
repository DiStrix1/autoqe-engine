package com.qe.demo;

public class BankAccount {
    private final String accountNumber;
    double balance;
    private boolean active;

    public BankAccount(String accountNumber, double initialBalance) {
        if (initialBalance < 0) {
            throw new IllegalArgumentException("Initial balance cannot be negative");
        }
        this.accountNumber = accountNumber;
        this.balance = initialBalance;
        this.active = true;
    }

    public void deposit(double amount) {
        ensureActive();
        if (amount <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        this.balance += amount;
    }

    public void withdraw(double amount) {
        ensureActive();
        if (amount <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        }
        if (amount > this.balance) {
            throw new IllegalStateException("Insufficient funds");
        }
        this.balance -= amount;
    }

    public void transferTo(BankAccount targetAccount, double amount) {
        if (targetAccount == null) {
            throw new IllegalArgumentException("Target account cannot be null");
        }
        this.withdraw(amount);
        targetAccount.deposit(amount);
    }

    private void ensureActive() {
        if (!this.active) {
            throw new IllegalStateException("Account is closed");
        }
    }

    public double getBalance() { 
        return balance; 
    }

    public String getAccountNumber() { 
        return accountNumber; 
    }

    public boolean isActive() { 
        return active; 
    }

    public void closeAccount() { 
        this.active = false; 
    }

    public void close() { 
        closeAccount(); 
    }
}