package com.qe.demo;

public interface InventoryClient {
    boolean isInStock(String productId, int quantity);
    void deductStock(String productId, int quantity);
    void restoreStock(String productId, int quantity);
}
