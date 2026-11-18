package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrHocrTest {
    /** Parsed as XML, without fetching the DTD the document names. */
    private static Document parse(String hocr) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(hocr.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Element> ofClass(Document document, String name) {
        List<Element> found = new ArrayList<>();
        for (String tag : new String[] {"div", "span"}) {
            NodeList nodes = document.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                if (name.equals(element.getAttribute("class"))) {
                    found.add(element);
                }
            }
        }
        return found;
    }

    @Test
    void aPageHasItsBoxItsLinesAndTheirWords() throws Exception {
        TextLine first = new TextLine("Zażółć <gęślą> & \"jaźń\"", 0.9f, 0.8f, new float[] {10, 20, 310, 20, 310, 60, 10, 60}, null,
                List.of(new TextWord("Zażółć", new float[] {10, 20, 100, 20, 100, 60, 10, 60}),
                        new TextWord("<gęślą>", new float[] {110.4f, 20, 199.6f, 20, 199.6f, 60, 110.4f, 60}),
                        new TextWord("&", new float[] {210, 20, 225, 20, 225, 60, 210, 60}),
                        new TextWord("\"jaźń\"", new float[] {235, 20, 310, 20, 310, 60, 235, 60})));
        // A line that runs down the page, with no word boxes, and a control character in its text.
        TextLine second = new TextLine("w dół\u0001", 0.9f, 0.8f, new float[] {400, 50, 400, 250, 370, 250, 370, 50}, null);
        OcrResult page = new OcrResult("", List.of(first, second), List.of(), 0, 0, 0, 0, 600, 800);
        String hocr = OcrHocr.page(page);
        Document document = parse(hocr);

        List<Element> pages = ofClass(document, "ocr_page");
        assertEquals(1, pages.size());
        assertEquals("bbox 0 0 600 800; ppageno 0", pages.get(0).getAttribute("title"));
        List<Element> lines = ofClass(document, "ocr_line");
        assertEquals(2, lines.size());
        assertEquals("bbox 10 20 310 60", lines.get(0).getAttribute("title"));
        // Down the page is a quarter turn clockwise: minus 90 degrees counter-clockwise.
        assertEquals("bbox 370 50 400 250; textangle -90.0", lines.get(1).getAttribute("title"));
        assertEquals("w dół", lines.get(1).getTextContent());
        List<Element> words = ofClass(document, "ocrx_word");
        assertEquals(4, words.size());
        assertEquals("Zażółć", words.get(0).getTextContent());
        assertEquals("<gęślą>", words.get(1).getTextContent());
        // The box is rounded outward.
        assertEquals("bbox 110 20 200 60", words.get(1).getAttribute("title"));
        assertEquals("&", words.get(2).getTextContent());
        assertEquals("\"jaźń\"", words.get(3).getTextContent());
        assertEquals("Zażółć <gęślą> & \"jaźń\"", lines.get(0).getTextContent());
        assertEquals("line_1_1", lines.get(0).getAttribute("id"));
        assertEquals("word_1_1_2", words.get(1).getAttribute("id"));
        assertTrue(hocr.contains("name=\"ocr-capabilities\" content=\"ocr_page ocr_line ocrx_word\""), hocr);
        assertTrue(hocr.endsWith("</html>\n"));
        assertFalse(hocr.contains("\u0001"));
    }

    @Test
    void aDocumentNumbersItsPagesAndAnEmptyOneStillParses() throws Exception {
        OcrResult one = new OcrResult("a", List.of(new TextLine("a", 1f, 1f, new float[] {1, 2, 3, 2, 3, 4, 1, 4}, null)),
                List.of(), 0, 0, 0, 90, 100, 200);
        OcrResult two = new OcrResult("", List.of(), List.of(), 0, 0, 0);
        Document document = parse(OcrHocr.document(List.of(one, two)));
        List<Element> pages = ofClass(document, "ocr_page");
        assertEquals(2, pages.size());
        assertEquals("page_2", pages.get(1).getAttribute("id"));
        assertEquals("bbox 0 0 0 0; ppageno 1", pages.get(1).getAttribute("title"));
        assertEquals("line_1_1", ofClass(document, "ocr_line").get(0).getAttribute("id"));
        assertEquals(0, ofClass(parse(OcrHocr.document(List.of())), "ocr_page").size());
        assertThrows(OcrException.class, () -> OcrHocr.page(null));
        assertThrows(OcrException.class, () -> OcrHocr.document(null));
        assertThrows(OcrException.class, () -> OcrHocr.document(java.util.Arrays.asList(one, null)));
        assertThrows(OcrException.class, () -> new OcrResult("", List.of(), List.of(), 0, 0, 0, 0, -1, 5));
    }
}
