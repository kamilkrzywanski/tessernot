package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.image.Bgr;
import io.github.kamilkrzywanski.ocr.internal.image.Images;
import io.github.kamilkrzywanski.ocr.internal.post.DbPost;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageTurnTest {
    private static DbPost.Box box(float x, float y, float w, float h) {
        return new DbPost.Box(new float[] {x, y, x + w, y, x + w, y + h, x, y + h}, 0.9f);
    }

    @Test
    void aPointGoesBackToThePixelItCameFrom() {
        int width = 7;
        int height = 4;
        for (int turns = 1; turns <= 3; turns++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // One marked pixel, turned the way the engine turns a page.
                    byte[] data = new byte[width * height * 3];
                    data[(y * width + x) * 3] = 1;
                    Bgr page = new Bgr(data, width, height);
                    if ((turns & 1) == 1) {
                        page = Images.rot90ccw(page);
                    }
                    if (turns >= 2) {
                        page = Images.rot180(page);
                    }
                    int at = -1;
                    for (int i = 0; i < page.width() * page.height(); i++) {
                        if (page.data()[i * 3] == 1) {
                            at = i;
                        }
                    }
                    float tx = at % page.width() + 0.5f;
                    float ty = at / page.width() + 0.5f;
                    assertArrayEquals(new float[] {x + 0.5f, y + 0.5f}, PageTurn.back(tx, ty, turns, width, height),
                            "turns " + turns + " pixel " + x + "," + y);
                }
            }
        }
        assertArrayEquals(new float[] {3f, 2f}, PageTurn.back(3f, 2f, 0, width, height));
        assertEquals(0, PageTurn.rotation(0));
        assertEquals(270, PageTurn.rotation(1));
        assertEquals(180, PageTurn.rotation(2));
        assertEquals(90, PageTurn.rotation(3));
    }

    @Test
    void aLineKeepsTheOrderOfItsCornersAndATableItsCells() {
        // A line across the top of a page that was read after a quarter turn counter-clockwise;
        // the picture itself is 100 wide and 300 high.
        TextLine turned = new TextLine("abc", 0.9f, 0.8f, new float[] {10, 20, 210, 20, 210, 50, 10, 50}, null);
        TextLine back = PageTurn.back(turned, 1, 100, 300);
        // Top left of the text is now at the right edge, and the text runs downward.
        assertArrayEquals(new float[] {80, 10, 80, 210, 50, 210, 50, 10}, back.quad());
        assertEquals(new Rectangle(50, 10, 30, 200), back.bounds());
        assertEquals("abc", back.text());
        assertEquals(0.8f, back.detScore());

        TextTable table = new TextTable(List.of(List.of("a", "b"), List.of("c", "d")), new Rectangle(10, 20, 200, 30), false);
        TextTable tableBack = PageTurn.back(table, 1, 100, 300);
        assertEquals(new Rectangle(50, 10, 30, 200), tableBack.bounds());
        assertEquals(table.cells(), tableBack.cells());
        assertFalse(tableBack.ruled());
    }

    @Test
    void aPageIsOnItsSideWhenMostOfItsTextStands() {
        List<DbPost.Box> upright = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            upright.add(box(50, 40 * i, 400, 30));
        }
        // A word up the margin and a square stamp do not turn the page.
        upright.add(box(5, 0, 30, 300));
        upright.add(box(500, 0, 80, 80));
        assertFalse(PageTurn.sideways(upright));

        List<DbPost.Box> lying = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lying.add(box(40 * i, 50, 30, 400));
        }
        lying.add(box(0, 0, 300, 30));
        assertTrue(PageTurn.sideways(lying));
        assertFalse(PageTurn.sideways(List.of()));
        assertFalse(PageTurn.sideways(List.of(box(0, 0, 50, 50))));
    }

    @Test
    void aLongLineVotesWithItsMiddle() {
        // 100 by 4: the window is the 32 columns in the middle. Each pixel holds its column.
        byte[] data = new byte[100 * 4 * 3];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 100; x++) {
                data[(y * 100 + x) * 3] = (byte) x;
            }
        }
        Bgr window = PageTurn.window(new Bgr(data, 100, 4));
        assertEquals(32, window.width());
        assertEquals(4, window.height());
        assertEquals(34, window.data()[0]);
        assertEquals(65, window.data()[(3 * 32 + 31) * 3]);
        Bgr small = new Bgr(new byte[20 * 4 * 3], 20, 4);
        assertTrue(small == PageTurn.window(small));
    }

    @Test
    void theLongestLinesVote() {
        List<DbPost.Box> boxes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            boxes.add(box(0, 40 * i, 100 + 10 * i, 30));
        }
        boxes.add(box(0, 2000, 60, 30));
        List<DbPost.Box> voters = PageTurn.voters(boxes);
        assertEquals(PageTurn.VOTERS, voters.size());
        // The longest first; the short word is not among them.
        assertEquals(490f, voters.get(0).quad()[2]);
        assertTrue(voters.get(PageTurn.VOTERS - 1).quad()[2] >= 350f);
        assertEquals(List.of(), PageTurn.voters(List.of(box(0, 0, 60, 30))));
    }
}
