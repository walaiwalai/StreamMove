package com.sh.config.utils;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 09 29 12 37
 **/
@Slf4j
public class PictureFileUtil {

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

        // 设置背景透明度 80% (alpha = 0.2 表示 80% 透明)
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
        g2d.setColor(Color.GRAY);
        g2d.fillRect(0, 0, width, height);

        // 重置透明度为完全不透明，用于绘制文本
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

        // 设置字体和大小
        Font font = new Font("SimSun", Font.PLAIN, fontSize);
        g2d.setFont(font);

        // 计算多行文本位置
        FontMetrics fontMetrics = g2d.getFontMetrics();
        int lineHeight = fontMetrics.getHeight();
        String[] lines = text.split("\n");

        int totalTextHeight = lines.length * lineHeight;
        int yStart = (height - totalTextHeight) / 2;

        // 绘制多行文本，横向居中
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int textWidth = fontMetrics.stringWidth(line);
            int x = (width - textWidth) / 2;
            int y = yStart + i * lineHeight + fontMetrics.getAscent();
            if (i % 2 == 0) {
                g2d.setColor(Color.BLUE);
            } else {
                g2d.setColor(Color.WHITE);
            }
            g2d.drawString(line, x, y);
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
