package com.sh.engine.util;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * 阿里云工具类
 */
public class AliDriverUtil {
    private static final byte[] hexChar = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    public static String byteToHex(byte[] content) {
        byte[] hexByte = new byte[content.length * 2];
        int index = 0;
        for (int i = 0; i < content.length; i++, index += 2) {
            hexByte[index] = hexChar[((content[i] & 0xf0) >> 4)];
            hexByte[index + 1] = hexChar[(content[i] & 0x0f)];
        }

        return new String(hexByte);
    }

    public static String sha1(String content) throws Exception {
        return sha1(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha1(byte[] content) throws Exception {
        MessageDigest messageDigest = MessageDigest.getInstance("sha1");
        byte[] res = messageDigest.digest(content);
        return byteToHex(res);
    }

    public static String sha1(byte[] content, int offset, int length) throws Exception {
        MessageDigest messageDigest = MessageDigest.getInstance("sha1");
        messageDigest.update(content, offset, length);
        byte[] res = messageDigest.digest();
        return byteToHex(res);
    }

    public static String md5(byte[] content) throws Exception {
        MessageDigest messageDigest = MessageDigest.getInstance("md5");
        byte[] res = messageDigest.digest(content);
        return byteToHex(res);
    }

}
