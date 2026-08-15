package com.sh.config.utils;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 09 29 12 37
 **/
@Slf4j
public class PictureFileUtil {
    private static final String TITLE_FONT_NAME = "SimSun";
    private static final int MIN_TITLE_FONT_SIZE = 20;
    private static final int TITLE_HORIZONTAL_PADDING_PERCENT = 6;
    private static final int TITLE_VERTICAL_PADDING_PERCENT = 6;

    public static void saveBase64Image(String base64Image, File targetQrFile) {
        try {
            // 去掉 Base64 字符串的前缀（如果有，如 "data:image/png;base64,"）
            if (base64Image.startsWith("data:image")) {
                base64Image = base64Image.substring(base64Image.indexOf(",") + 1);
            }

            byte[] imageBytes = Base64.getDecoder().decode(base64Image);
            InputStream inputStream = new ByteArrayInputStream(imageBytes);
            BufferedImage bufferedImage = ImageIO.read(inputStream);
            ImageIO.write(bufferedImage, "png", targetQrFile);
        } catch (IOException e) {
            log.error("Failed to save image: {}", targetQrFile.getAbsolutePath(), e);
        }
    }

    public static String fileToBase64(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }

            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    public static String calculateFileMD5(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[1024];
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }
            byte[] digest = md.digest();
            BigInteger bigInt = new BigInteger(1, digest);
            return String.format("%032x", bigInt);
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * 生成带透明背景、指定文本的图片
     *
     * @param text   要绘制的文本，支持换行
     * @param toFile 输出图片
     */
    public static void createTextWithVeil(String text, int width, int height, int fontSize, File toFile) {
        // 创建空白图片，并开启透明度 (TYPE_INT_ARGB 支持透明度)
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 设置背景透明度 80% (alpha = 0.2 表示 80% 透明)
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
        g2d.setColor(Color.GRAY);
        g2d.fillRect(0, 0, width, height);

        // 重置透明度为完全不透明，用于绘制文本
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

        TextLayout layout = createTextLayout(g2d, text, width, height, fontSize);
        g2d.setFont(layout.font);

        FontMetrics fontMetrics = layout.fontMetrics;
        int lineHeight = fontMetrics.getHeight();
        int totalTextHeight = layout.lines.size() * lineHeight;
        int yStart = Math.max(layout.verticalPadding, (height - totalTextHeight) / 2);

        // 绘制多行文本，横向居中
        for (int i = 0; i < layout.lines.size(); i++) {
            TextLine line = layout.lines.get(i);
            int textWidth = fontMetrics.stringWidth(line.text);
            int x = Math.max(layout.horizontalPadding, (width - textWidth) / 2);
            int y = yStart + i * lineHeight + fontMetrics.getAscent();
            if (line.sourceLineIndex % 2 == 0) {
                g2d.setColor(Color.BLUE);
            } else {
                g2d.setColor(Color.WHITE);
            }
            g2d.drawString(line.text, x, y);
        }

        // 释放 Graphics2D 对象
        g2d.dispose();

        // 保存图片
        try {
            ImageIO.write(image, "png", toFile);
        } catch (IOException e) {
            log.error("Error saving textOverlayImage: {}", toFile.getAbsolutePath());
        }
    }

    /**
     * 标题布局优先缩小字号以保留调用方传入的换行；达到可读字号下限后仍过宽时，
     * 再按字符换行。布局同时受横向和纵向安全区约束。
     */
    static TextLayout createTextLayout(Graphics2D g2d, String text, int width, int height, int maxFontSize) {
        int horizontalPadding = Math.max(1, width * TITLE_HORIZONTAL_PADDING_PERCENT / 100);
        int verticalPadding = Math.max(1, height * TITLE_VERTICAL_PADDING_PERCENT / 100);
        int maxTextWidth = Math.max(1, width - 2 * horizontalPadding);
        int maxTextHeight = Math.max(1, height - 2 * verticalPadding);
        int requestedFontSize = Math.max(1, maxFontSize);
        int preferredMinFontSize = Math.min(requestedFontSize, MIN_TITLE_FONT_SIZE);
        List<TextLine> sourceLines = splitSourceLines(text);

        for (int candidate = requestedFontSize; candidate >= preferredMinFontSize; candidate--) {
            TextLayout layout = newTextLayout(g2d, sourceLines, candidate,
                    horizontalPadding, verticalPadding);
            if (fits(layout, maxTextWidth, maxTextHeight)) {
                return layout;
            }
        }

        for (int candidate = preferredMinFontSize; candidate >= 1; candidate--) {
            Font font = new Font(TITLE_FONT_NAME, Font.PLAIN, candidate);
            g2d.setFont(font);
            FontMetrics fontMetrics = g2d.getFontMetrics(font);
            List<TextLine> wrappedLines = wrapLines(sourceLines, fontMetrics, maxTextWidth);
            TextLayout layout = new TextLayout(font, fontMetrics, wrappedLines,
                    horizontalPadding, verticalPadding);
            if (fits(layout, maxTextWidth, maxTextHeight) || candidate == 1) {
                return layout;
            }
        }

        throw new IllegalStateException("unable to create title text layout");
    }

    private static TextLayout newTextLayout(Graphics2D g2d, List<TextLine> lines, int fontSize,
                                            int horizontalPadding, int verticalPadding) {
        Font font = new Font(TITLE_FONT_NAME, Font.PLAIN, fontSize);
        g2d.setFont(font);
        return new TextLayout(font, g2d.getFontMetrics(font), lines, horizontalPadding, verticalPadding);
    }

    private static boolean fits(TextLayout layout, int maxTextWidth, int maxTextHeight) {
        if ((long) layout.lines.size() * layout.fontMetrics.getHeight() > maxTextHeight) {
            return false;
        }
        for (TextLine line : layout.lines) {
            if (layout.fontMetrics.stringWidth(line.text) > maxTextWidth) {
                return false;
            }
        }
        return true;
    }

    private static List<TextLine> splitSourceLines(String text) {
        String normalizedText = text == null ? "" : text;
        String[] rawLines = normalizedText.split("\\r?\\n", -1);
        List<TextLine> lines = new ArrayList<>(rawLines.length);
        for (int i = 0; i < rawLines.length; i++) {
            lines.add(new TextLine(rawLines[i], i));
        }
        return lines;
    }

    private static List<TextLine> wrapLines(List<TextLine> sourceLines, FontMetrics fontMetrics,
                                            int maxTextWidth) {
        List<TextLine> wrappedLines = new ArrayList<>();
        for (TextLine sourceLine : sourceLines) {
            if (sourceLine.text.isEmpty()) {
                wrappedLines.add(sourceLine);
                continue;
            }

            StringBuilder currentLine = new StringBuilder();
            for (int offset = 0; offset < sourceLine.text.length(); ) {
                int codePoint = sourceLine.text.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                if (currentLine.length() > 0
                        && fontMetrics.stringWidth(currentLine.toString() + character) > maxTextWidth) {
                    wrappedLines.add(new TextLine(currentLine.toString(), sourceLine.sourceLineIndex));
                    currentLine.setLength(0);
                }
                currentLine.append(character);
                offset += Character.charCount(codePoint);
            }
            if (currentLine.length() > 0) {
                wrappedLines.add(new TextLine(currentLine.toString(), sourceLine.sourceLineIndex));
            }
        }
        return wrappedLines;
    }

    static final class TextLayout {
        final Font font;
        final FontMetrics fontMetrics;
        final List<TextLine> lines;
        final int horizontalPadding;
        final int verticalPadding;

        private TextLayout(Font font, FontMetrics fontMetrics, List<TextLine> lines,
                           int horizontalPadding, int verticalPadding) {
            this.font = font;
            this.fontMetrics = fontMetrics;
            this.lines = lines;
            this.horizontalPadding = horizontalPadding;
            this.verticalPadding = verticalPadding;
        }
    }

    static final class TextLine {
        final String text;
        final int sourceLineIndex;

        private TextLine(String text, int sourceLineIndex) {
            this.text = text;
            this.sourceLineIndex = sourceLineIndex;
        }
    }

    /**
     * 在已有图片上添加标题文本
     *
     * @param bgFile 原始图片文件
     * @param toFile 保存修改后图片的文件
     * @param lines  要添加的标题文本
     */
    public static void createTextOnImage(File bgFile, File toFile, List<String> lines) {
        // 读取原始图片
        BufferedImage image = null;
        try {
            image = ImageIO.read(bgFile);
        } catch (IOException ignored) {
            log.error("Error reading bgFile: {}", bgFile.getAbsolutePath());
            return;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int fontSize = height / 10;

        Graphics2D g2d = image.createGraphics();

        // 设置字体和大小
        Font font = new Font("SimSun", Font.BOLD, fontSize);
        g2d.setFont(font);

        // 计算多行文本位置
        FontMetrics fontMetrics = g2d.getFontMetrics();
        int lineHeight = fontMetrics.getHeight();

        int totalTextHeight = lines.size() * lineHeight;
        int yStart = (height - totalTextHeight) / 2;

        // 绘制多行文本，横向居中
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int textWidth = fontMetrics.stringWidth(line);
            int x = (width - textWidth) / 2;
            int y = yStart + i * lineHeight + fontMetrics.getAscent();
            if (i % 2 == 0) {
                g2d.setColor(Color.BLACK);
            } else {
                g2d.setColor(Color.BLACK);
            }
            g2d.drawString(line, x, y);
        }

        // 释放 Graphics2D 对象
        g2d.dispose();

        // 保存图片
        try {
            ImageIO.write(image, "jpg", toFile);
        } catch (IOException e) {
            log.error("Error adding text to existing image: {}", bgFile.getAbsolutePath());
        }
    }
}
