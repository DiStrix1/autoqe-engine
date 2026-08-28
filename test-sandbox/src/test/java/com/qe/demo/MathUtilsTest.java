package com.qe.demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MathUtilsTest {

    @Test
    void testFactorial() {
        MathUtils mathUtils = new MathUtils();
        assertEquals(1, mathUtils.factorial(0));
        assertEquals(1, mathUtils.factorial(1));
        assertEquals(2, mathUtils.factorial(2));
        assertEquals(6, mathUtils.factorial(3));
        assertEquals(24, mathUtils.factorial(4));
        assertEquals(120, mathUtils.factorial(5));
        assertThrows(IllegalArgumentException.class, () -> mathUtils.factorial(-1));
    }

    @Test
    void testIsPrime() {
        MathUtils mathUtils = new MathUtils();
        assertTrue(mathUtils.isPrime(2));
        assertFalse(mathUtils.isPrime(0));
        assertFalse(mathUtils.isPrime(1));
        assertTrue(mathUtils.isPrime(3));
        assertFalse(mathUtils.isPrime(4));
        assertTrue(mathUtils.isPrime(5));
    }

    @Test
    void testDivide() {
        MathUtils mathUtils = new MathUtils();
        assertEquals(2.0, mathUtils.divide(4.0, 2.0), 0.01);
        assertThrows(ArithmeticException.class, () -> mathUtils.divide(4.0, 0.0));
    }
}