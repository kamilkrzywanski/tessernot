package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParenSpaceTest {
    @Test
    void dropsOneSpaceBeforeASingleLetterInsideParentheses() {
        assertEquals("Wystawił(a):", ParenSpace.apply("Wystawił( a):"));
        assertEquals("Odebrał(a):", ParenSpace.apply("Odebrał(a):"));
        assertEquals("Wystawił(A):", ParenSpace.apply("Wystawił( A):"));
        assertEquals("( a )", ParenSpace.apply("( a )"));
        assertEquals("( ab)", ParenSpace.apply("( ab)"));
        assertEquals("A(b) B(c)", ParenSpace.apply("A( b) B( c)"));
        assertEquals("(22)", ParenSpace.apply("(22)"));
        assertEquals("", ParenSpace.apply(""));
        assertEquals(null, ParenSpace.apply(null));
    }
}
