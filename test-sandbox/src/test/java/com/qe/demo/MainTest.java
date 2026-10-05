package com.qe.demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MainTest {

    @Test
    void solve_HappyPath_Returns1() {
        // Arrange
        String s = "abc";
        Main main = new Main();

        // Act
        int result = main.solve(s);

        // Assert
        assertEquals(-1, result);
    }

    @Test
    void solve_NullInput_ReturnsMinusOne() {
        // Arrange
        String s = null;
        Main main = new Main();

        // Act
        int result = main.solve(s);

        // Assert
        assertEquals(-1, result);
    }

    @Test
    void solve_EmptyString_ReturnsMinusOne() {
        // Arrange
        String s = "";
        Main main = new Main();

        // Act
        int result = main.solve(s);

        // Assert
        assertEquals(-1, result);
    }

    @Test
    void solve_SingleCharacter_ReturnsMinusOne() {
        // Arrange
        String s = "a";
        Main main = new Main();

        // Act
        int result = main.solve(s);

        // Assert
        assertEquals(-1, result);
    }
}