package com.sh.config.utils;

import com.sh.config.manager.ConfigFetcher;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * @Author caiwen
 * @Date 2024 09 29 12 37
 **/
@Slf4j
public class PictureFileUtil {

    public static File saveBase64Image(String base64Image, String fileName) {
        String accountSavePath = ConfigFetcher.getInitConfig().getAccountSavePath();

        try {
            // 去掉 Base64 字符串的前缀（如果有，如 "data:image/png;base64,"）
            if (base64Image.startsWith("data:image")) {
                base64Image = base64Image.substring(base64Image.indexOf(",") + 1);
            }

            byte[] imageBytes = Base64.getDecoder().decode(base64Image);
            InputStream inputStream = new ByteArrayInputStream(imageBytes);
            BufferedImage bufferedImage = ImageIO.read(inputStream);
            File outputFile = new File(accountSavePath, fileName);
            ImageIO.write(bufferedImage, "png", outputFile);
            return outputFile;
        } catch (IOException e) {
            log.error("Failed to save image: {}", accountSavePath, e);
            return null;
        }
    }

    public static String fileToBase64(File file) {
        // Read the file into a byte array
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
}
