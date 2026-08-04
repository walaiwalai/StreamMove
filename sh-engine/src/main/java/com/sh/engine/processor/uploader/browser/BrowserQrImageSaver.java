package com.sh.engine.processor.uploader.browser;

import com.microsoft.playwright.Locator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

/** Saves the QR image resource itself, with an element-only screenshot as a safe fallback. */
@Slf4j
public final class BrowserQrImageSaver {
    private BrowserQrImageSaver() {
    }

    public static void save(Locator qrImage, File targetFile) {
        try {
            saveOriginalImage(qrImage, targetFile);
        } catch (RuntimeException e) {
            log.warn("Failed to read original login QR image; fall back to element screenshot: {}",
                    targetFile.getAbsolutePath(), e);
            qrImage.screenshot(new Locator.ScreenshotOptions()
                    .setPath(Paths.get(targetFile.getAbsolutePath())));
        }
        if (!targetFile.isFile() || targetFile.length() == 0L) {
            throw new IllegalStateException("登录二维码图片未生成: "
                    + targetFile.getAbsolutePath());
        }
    }

    private static void saveOriginalImage(Locator qrImage, File targetFile) {
        Object raw = qrImage.evaluate("async img => {"
                + "const response = await fetch(img.currentSrc || img.src, "
                + "{credentials: 'include'});"
                + "if (!response.ok) throw new Error('HTTP ' + response.status);"
                + "const bytes = new Uint8Array(await response.arrayBuffer());"
                + "let binary = '';"
                + "for (let offset = 0; offset < bytes.length; offset += 8192) {"
                + "binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));"
                + "}"
                + "return btoa(binary);"
                + "}");
        String base64 = raw == null ? null : String.valueOf(raw);
        if (StringUtils.isBlank(base64)) {
            throw new IllegalStateException("登录二维码返回了空图片");
        }
        try {
            Files.write(targetFile.toPath(), Base64.getDecoder().decode(base64));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("保存登录二维码原图失败", e);
        }
    }
}
