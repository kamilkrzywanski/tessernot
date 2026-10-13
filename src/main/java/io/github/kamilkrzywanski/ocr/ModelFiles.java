package io.github.kamilkrzywanski.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Official RapidOCR weights (Apache-2.0).
 * Detection and the angle classifier are PP-OCRv5 mobile.
 * Recognition is PP-OCRv6 small, a multilingual CTC model that includes Polish.
 * Downloaded only when missing or when the SHA-256 does not match.
 */
public final class ModelFiles {
    public static final String DET = "ch_PP-OCRv5_det_mobile.onnx";
    public static final String REC = "PP-OCRv6_rec_small.onnx";
    public static final String CLS = "ch_PP-LCNet_x0_25_textline_ori_cls_mobile.onnx";
    public static final String DICT = "ppocrv6_dict.txt";

    private static final String BASE =
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/";

    private static final Asset[] ASSETS = {
            new Asset(DET, BASE + "onnx/PP-OCRv5/det/" + DET,
                    "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"),
            new Asset(REC, BASE + "onnx/PP-OCRv6/rec/" + REC,
                    "6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884"),
            new Asset(CLS, BASE + "onnx/PP-OCRv5/cls/" + CLS,
                    "54379ae5174d026780215fc748a7f31910dee36818e63d49e17dc598ecc82df7"),
            new Asset(DICT, BASE + "paddle/PP-OCRv6/rec/PP-OCRv6_rec_small/" + DICT,
                    "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d")
    };

    private ModelFiles() {
    }

    public static Path ensure(Path directory) {
        if (directory == null) {
            throw new OcrException("model directory is null");
        }
        requireDirectory(directory);
        try {
            Files.createDirectories(directory);
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            for (Asset asset : ASSETS) {
                Path dest = directory.resolve(asset.name);
                if (alreadyValid(dest, asset.sha256)) {
                    continue;
                }
                Path tmp = directory.resolve(asset.name + ".part");
                HttpRequest request = HttpRequest.newBuilder(URI.create(asset.url))
                        .timeout(Duration.ofMinutes(10))
                        .header("User-Agent", "ocr-pro/1.0")
                        .GET()
                        .build();
                HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
                if (response.statusCode() / 100 != 2) {
                    Files.deleteIfExists(tmp);
                    throw new OcrException("download failed HTTP " + response.statusCode() + " for " + asset.url);
                }
                String hash = sha256(tmp);
                if (!asset.sha256.equals(hash)) {
                    Files.deleteIfExists(tmp);
                    throw new OcrException("SHA-256 mismatch for " + asset.name + ": " + hash);
                }
                Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return directory;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new OcrException("cannot prepare models in " + directory + ": " + e.getMessage(), e);
        }
    }

    /**
     * Checks the files {@code ocr} and {@code bench} need before the image is decoded.
     * Detection, recognition, and the classifier follow the CLI switches.
     */
    static void requirePresent(Path directory, boolean detection, boolean recognition, boolean classifier) {
        requireDirectory(directory);
        if (detection) {
            requireFile(directory.resolve(DET), "detection model");
        }
        if (recognition) {
            requireFile(directory.resolve(REC), "recognition model");
            requireFile(directory.resolve(DICT), "recognition dict");
        }
        if (classifier) {
            requireFile(directory.resolve(CLS), "classifier model");
        }
    }

    /** True when {@code dest} already has the expected SHA-256. A leftover {@code .part} is removed. */
    static boolean alreadyValid(Path dest, String expected) throws IOException {
        if (dest == null || expected == null || !Files.exists(dest) || !expected.equals(sha256(dest))) {
            return false;
        }
        Path part = dest.resolveSibling(dest.getFileName().toString() + ".part");
        Files.deleteIfExists(part);
        return true;
    }

    static void requireFile(Path path, String label) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new OcrException("missing " + label + (path == null ? "" : ": " + path) + " (ocr download)");
        }
        try {
            if (Files.size(path) == 0L) {
                throw new OcrException(label + " is empty: " + path + " (ocr download)");
            }
        } catch (IOException e) {
            throw new OcrException("missing " + label + ": " + path + " (ocr download)", e);
        }
    }

    /**
     * A missing path is allowed. A blank path is the working directory, so it is rejected.
     * An existing file is not a model directory.
     */
    static void requireDirectory(Path directory) {
        if (directory.toString().isBlank()) {
            throw new OcrException("model directory is empty");
        }
        if (Files.exists(directory) && !Files.isDirectory(directory)) {
            throw new OcrException("model directory is not a directory: " + directory);
        }
    }

    static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new OcrException("cannot hash " + path + ": " + e.getMessage(), e);
        }
    }

    private record Asset(String name, String url, String sha256) {
    }
}
