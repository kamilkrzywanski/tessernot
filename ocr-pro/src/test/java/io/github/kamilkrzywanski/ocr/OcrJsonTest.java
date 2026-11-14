package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrJsonTest {
    @Test
    void pageAndDocumentKeepAFixedSchema() {
        TextLine first = new TextLine(
                "\b\f\n\r\t\u0000\u001fŁódź\\",
                0.5f,
                Float.NaN,
                new float[] {0f, 1f, 2.5f, -3.25f, -0.0f, 1.0E20f, Float.POSITIVE_INFINITY, 0.125f},
                new Rectangle(10, 20, 30, 40),
                List.of(new TextWord("Łódź", new float[] {1f, 2f, 3f, 4f, 5f, 6f, 7f, 8.5f})));
        TextLine second = new TextLine(
                "",
                Float.NEGATIVE_INFINITY,
                1f,
                new float[] {Float.NaN, 0f, 0f, 0f, 0f, 0f, 0f, 0f},
                new Rectangle());
        TextTable table = new TextTable(
                List.of(Arrays.asList("zł", null), List.of("a\\b", "c\"d")),
                new Rectangle(1, 2, 100, 50));
        OcrResult page = new OcrResult(
                "Spółka \"X\"\nŁódź",
                List.of(first, second),
                List.of(table),
                123456789L,
                123456789L,
                123456789L);
        String u = "\\" + "u00";
        String body = "{\"text\":\"Spółka \\\"X\\\"\\nŁódź\",\"lines\":["
                + "{\"text\":\"\\b\\f\\n\\r\\t" + u + "00" + u + "1fŁódź\\\\\",\"score\":0.5,\"detScore\":null,"
                + "\"quad\":[0.0,1.0,2.5,-3.25,-0.0,1.0E20,null,0.125],"
                + "\"bounds\":{\"x\":10,\"y\":20,\"width\":30,\"height\":40},"
                + "\"words\":[{\"text\":\"Łódź\",\"quad\":[1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.5]}]},"
                + "{\"text\":\"\",\"score\":null,\"detScore\":1.0,"
                + "\"quad\":[null,0.0,0.0,0.0,0.0,0.0,0.0,0.0],"
                + "\"bounds\":{\"x\":0,\"y\":0,\"width\":0,\"height\":0},\"words\":[]}],"
                + "\"tables\":[{\"bounds\":{\"x\":1,\"y\":2,\"width\":100,\"height\":50},\"ruled\":true,"
                + "\"cells\":[[\"zł\",\"\"],[\"a\\\\b\",\"c\\\"d\"]]}],\"rotation\":0}";
        String json = OcrJson.page(page);
        assertEquals(body + "\n", json);
        assertFalse(json.contains("123456789"), json);
        assertFalse(json.contains("NaN"), json);
        assertFalse(json.contains("Infinity"), json);

        OcrResult other = new OcrResult("druga", null, null, 1, 2, 3);
        assertEquals("{\"pages\":[" + body + ",{\"text\":\"druga\",\"lines\":[],\"tables\":[],\"rotation\":0}]}\n",
                OcrJson.document(List.of(page, other)));
        assertEquals("{\"pages\":[]}\n", OcrJson.document(List.of()));
    }

    @Test
    void nullInputsAreRejected() {
        OcrException result = assertThrows(OcrException.class, () -> OcrJson.page(null));
        assertTrue(result.getMessage().contains("result is null"), result.getMessage());
        OcrException pages = assertThrows(OcrException.class, () -> OcrJson.document(null));
        assertTrue(pages.getMessage().contains("pages is null"), pages.getMessage());
        OcrResult page = new OcrResult("a", List.of(), List.of(), 0, 0, 0);
        OcrException missing = assertThrows(OcrException.class,
                () -> OcrJson.document(Arrays.asList(page, null)));
        assertTrue(missing.getMessage().contains("page is null"), missing.getMessage());
    }
}
