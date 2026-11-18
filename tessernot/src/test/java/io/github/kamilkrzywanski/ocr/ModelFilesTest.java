package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelFilesTest {
    @Test
    void validWeightDropsALeftoverPartFile() throws Exception {
        Path dir = Files.createTempDirectory("ocr-part");
        Path dest = dir.resolve("weight.bin");
        Path part = dir.resolve("weight.bin.part");
        try {
            Files.write(dest, new byte[]{1, 2, 3});
            Files.write(part, new byte[]{9});
            assertTrue(ModelFiles.alreadyValid(dest, ModelFiles.sha256(dest)));
            assertFalse(Files.exists(part));
            assertEquals(3, Files.size(dest));

            Files.write(part, new byte[]{9});
            assertFalse(ModelFiles.alreadyValid(dest, "00"));
            assertTrue(Files.exists(part));
            assertEquals(3, Files.size(dest));

            assertFalse(ModelFiles.alreadyValid(dir.resolve("missing.bin"), ModelFiles.sha256(dest)));
            assertTrue(Files.exists(part));
        } finally {
            Files.deleteIfExists(part);
            Files.deleteIfExists(dest);
            Files.deleteIfExists(dir);
        }
    }
}
