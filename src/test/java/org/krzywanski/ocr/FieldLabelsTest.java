package org.krzywanski.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldLabelsTest {
    @Test
    void replacesOneUniqueLabelAndLeavesEverythingElse() {
        assertEquals("Miejsce wystawienia:", FieldLabels.apply("Mieisce wystawienia:"));
        assertEquals("Słownie:", FieldLabels.apply("Stownie:"));
        assertEquals("Miejsce wystawienia:", FieldLabels.apply("Miejsce wystawienia:"));
        assertEquals("Zapłacono przelewem:", FieldLabels.apply("Zapłacono przelewem:"));
        assertEquals("Data dostawy/wykonania usługi", FieldLabels.apply("Data dostawy/wykonania usługi"));
        assertEquals("NIP: 534-000-67-48", FieldLabels.apply("NIP: 534-000-67-48"));
        assertEquals("90-031 Łódź", FieldLabels.apply("90-031 Łódź"));
        assertEquals("Sincerely,", FieldLabels.apply("Sincerely,"));
        assertEquals("Polędw kos 100g", FieldLabels.apply("Polędw kos 100g"));
        assertEquals("aaac", FieldLabels.apply("aaac", new String[]{"aaaa", "aaab"}));
        assertEquals("aaaa:", FieldLabels.apply("aaac:", new String[]{"aaaa"}));
    }
}