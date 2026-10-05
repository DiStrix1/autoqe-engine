package com.qe.demo;
import java.util.*;
public class Main {

    /**
     * Solves the DSA problem for a given input string.
     * @param s input string
     * @return 1 if condition met, -1 otherwise
     */
    public int solve(String s) {
        if (s == null) {
            return -1;
        }
        int n = s.length();
        if (n == 0 || n == 1) {
            return -1;
        }
        int count = 0;
        char[] ch = s.toCharArray();
        char[] re = new char[n];
        for (int i = 0; i < n - 1; i++) {
            if (ch[i] == ch[i + 1]) {
                count++;
            }    
        }
        if (count == n - 1) {
            return -1;
        }

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                char temp = ch[i];
                ch[i] = ch[j];
                ch[j] = temp;
                int idx = 0;
                for (int k = n - 1; k >= 0; k--) {
                    re[idx] = ch[k];
                    idx++;
                }
                String s1 = String.valueOf(re);
                if (s1.compareTo(s) < 0) {
                    return 1;
                }
                ch[j] = ch[i];
                ch[i] = temp;
            }
        }
        return -1;
    }

    public static void main(String[] args) {
        try (Scanner sc = new Scanner(System.in)) {
            if (sc.hasNext()) {
                String s = sc.next();
                System.out.println(new Main().solve(s));
            }
        }
    }
}
