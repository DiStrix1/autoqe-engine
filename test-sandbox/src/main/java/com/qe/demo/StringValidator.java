package com.qe.demo;

import java.util.regex.Pattern;

public class StringValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");

    public boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    public boolean isStrongPassword(String password) {
        if (password == null || password.length() < 8) {
            return false;
        }
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }

        return hasUpper && hasLower && hasDigit && hasSpecial;
    }

    public boolean isAlphanumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return ALPHANUMERIC_PATTERN.matcher(str).matches();
    }

    public String sanitize(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").trim();
    }

    public boolean validateCreditCardLuhn(String cardNumber) {
        if (cardNumber == null) {
            return false;
        }
        String clean = cardNumber.replaceAll("\\s+", "").replaceAll("-", "");
        if (clean.length() < 8 || clean.length() > 19 || !clean.matches("\\d+")) {
            return false;
        }

        int sum = 0;
        boolean alternate = false;
        for (int i = clean.length() - 1; i >= 0; i--) {
            int n = Integer.parseInt(clean.substring(i, i + 1));
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n = (n % 10) + 1;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }
}
