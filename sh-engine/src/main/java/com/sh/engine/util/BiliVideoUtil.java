//package com.sh.engine.util;
//
//import java.io.UnsupportedEncodingException;
//import java.net.URLEncoder;
//import java.nio.charset.StandardCharsets;
//import java.security.MessageDigest;
//import java.time.Instant;
//import java.util.*;
//import java.util.regex.Pattern;
//
///**
// * @Author caiwen
// * @Date 2025 08 03 14 20
// **/
//public class BiliVideoUtil {
//    private static final String dm_img_str_cache = Base64.getEncoder().encodeToString(
//            getRandomString(new Random().nextInt(49) + 16).getBytes(StandardCharsets.UTF_8)
//    ).substring(0, Base64.getEncoder().encodeToString(
//            getRandomString(new Random().nextInt(49) + 16).getBytes(StandardCharsets.UTF_8)
//    ).length() - 2);
//
//    private static final String dm_cover_img_str_cache = Base64.getEncoder().encodeToString(
//            getRandomString(new Random().nextInt(97) + 32).getBytes(StandardCharsets.UTF_8)
//    ).substring(0, Base64.getEncoder().encodeToString(
//            getRandomString(new Random().nextInt(97) + 32).getBytes(StandardCharsets.UTF_8)
//    ).length() - 2);
//
//    private static String getRandomString(int length) {
//        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ ";
//        StringBuilder sb = new StringBuilder();
//        Random rnd = new Random();
//        for (int i = 0; i < length; i++) {
//            sb.append(chars.charAt(rnd.nextInt(chars.length())));
//        }
//        return sb.toString();
//    }
//
//    private static String getMixinKey(String str) {
//        int[] charIndices = {
//                46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5,
//                49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55,
//                40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57,
//                62, 11, 36, 20, 34, 44, 52,
//        };
//        StringBuilder sb = new StringBuilder();
//        for (int i = 0; i < 32; i++) {
//            sb.append(str.charAt(charIndices[i]));
//        }
//        return sb.toString();
//    }
//
//    public static Map<String, Object> encodeWbi(Map<String, Object> params, String imgKey, String subKey) {
//        Pattern illegalCharRemover = Pattern.compile("[!'()*]");
//        String mixinKey = getMixinKey(imgKey + subKey);
//        long timeStamp = Instant.now().getEpochSecond();
//
//        Map<String, Object> paramsWithWts = new HashMap<>(params);
//        paramsWithWts.put("wts", timeStamp);
//
//        Map<String, Object> paramsWithDm = new HashMap<>(paramsWithWts);
//        paramsWithDm.put("dm_img_list", "[]");
//        paramsWithDm.put("dm_img_str", dm_img_str_cache);
//        paramsWithDm.put("dm_cover_img_str", dm_cover_img_str_cache);
//
//        // 排序并去除非法字符
//        List<String> sortedKeys = new ArrayList<>(paramsWithDm.keySet());
//        Collections.sort(sortedKeys);
//
//        StringBuilder urlEncodedParams = new StringBuilder();
//        try {
//            for (String key : sortedKeys) {
//                String value = String.valueOf(paramsWithDm.get(key));
//                value = illegalCharRemover.matcher(value).replaceAll("");
//                if (urlEncodedParams.length() > 0) urlEncodedParams.append("&");
//                urlEncodedParams.append(URLEncoder.encode(key, "UTF-8"))
//                        .append("=")
//                        .append(URLEncoder.encode(value, "UTF-8"));
//            }
//        } catch (UnsupportedEncodingException e) {
//            throw new RuntimeException(e);
//        }
//
//        String wRid = md5(urlEncodedParams.toString() + mixinKey);
//
//        Map<String, Object> allParams = new HashMap<>(paramsWithDm);
//        allParams.put("w_rid", wRid);
//        return allParams;
//    }
//
//    private static String md5(String input) {
//        try {
//            MessageDigest md = MessageDigest.getInstance("MD5");
//            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
//            StringBuilder sb = new StringBuilder();
//            for (byte b : digest) {
//                sb.append(String.format("%02x", b & 0xff));
//            }
//            return sb.toString();
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        }
//    }
//}
