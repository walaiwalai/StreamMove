package com.sh.engine.util;


import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * @Author caiwen
 * @Date 2023 08 27 13 00
 **/
public class WebsiteStreamUtil {
    public static String getHuyaCurStreamUrl(String origin, long timeStamp) {
        String hexTimeStamp = Long.toHexString(timeStamp).toUpperCase();
        String tmp = "clientQueryPcdnSchedule" + hexTimeStamp + "1211huyalive";
        String wsSecret = JavaScriptUtil.execJsByFileName("huya-stream.js", "Ee", tmp);
        String newStreamUrl = origin.replaceAll("(wsSecret=)[^&]+", "$1" + wsSecret)
                .replaceAll("(wsTime=)[^&]+", "$1" + hexTimeStamp);
        return newStreamUrl;
    }

    public static String getHuyaAntiCode(String oldAntiCode, String streamName) {
        // SDK版本等常量
        int paramsT = 100;
        long sdkVersion = 2403051612L;

        // 获取当前时间戳（毫秒级）
        long t13 = System.currentTimeMillis() / 1000 * 1000;
        long sdkSid = t13;

        // 计算uuid和uid参数值
        long initUuid = (long) ((t13 % Math.pow(10, 10)) * 1000 + (1000 * new Random().nextDouble())) & 4294967295L;
        Random random = new Random();
        long uid = random.longs(1400000000000L, 1400009999999L).findFirst().getAsLong(); // 或使用initUuid代替
        long seqId = uid + sdkSid;

        // 计算ws_time参数值
        long targetUnixTime = (t13 + 110624) / 1000;
        String wsTime = Long.toHexString(targetUnixTime).toLowerCase();

        // 解析旧的anti_code以获取url_query参数
        Map<String, String[]> urlQuery = parseUrlEncoded(oldAntiCode);
        String wsSecretPf = new String(Base64.getDecoder().decode(urlQuery.get("fm")[0].getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8).split("_")[0];
        String wsSecretHash = md5Hex(String.format("%s|%s|%d", seqId, urlQuery.get("ctype")[0], paramsT));

        // 计算ws_secret完整字符串并计算其MD5摘要
        String wsSecret = String.format("%s_%d_%s_%s_%s", wsSecretPf, uid, streamName, wsSecretHash, wsTime);
        String wsSecretMd5 = md5Hex(wsSecret);

        // 构建新的anti_code字符串
        String antiCode = String.format(
                "wsSecret=%s&wsTime=%s&seqid=%d&ctype=%s&ver=1&fs=%s&uuid=%d&u=%d&t=%d&sv=%d"
                        + "&sdk_sid=%d&codec=264",
                wsSecretMd5, wsTime, seqId, urlQuery.get("ctype")[0], urlQuery.get("fs")[0],
                initUuid, uid, paramsT, sdkVersion, sdkSid
        );
        return antiCode;
    }

    private static Map<String, String[]> parseUrlEncoded(String urlEncodedStr) {
        Map<String, String[]> resultMap = new LinkedHashMap<>();

        try {
            // 解码URL编码的字符串
            String decodedStr = URLDecoder.decode(urlEncodedStr, "UTF-8");

            // 分割查询参数
            StringTokenizer st = new StringTokenizer(decodedStr, "&");
            while (st.hasMoreTokens()) {
                String token = st.nextToken();
                int eqIndex = token.indexOf('=');
                if (eqIndex > 0) {
                    String key = token.substring(0, eqIndex);
                    String value = token.substring(eqIndex + 1);

                    // 如果键已存在，则将值添加到数组中；否则创建新的数组
                    String[] existingValues = resultMap.get(key);
                    if (existingValues == null) {
                        resultMap.put(key, new String[]{value});
                    } else {
                        String[] newValues = new String[existingValues.length + 1];
                        System.arraycopy(existingValues, 0, newValues, 0, existingValues.length);
                        newValues[existingValues.length] = value;
                        resultMap.put(key, newValues);
                    }
                }
            }

            return resultMap;

        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Failed to decode URL-encoded string", e);
        }
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(messageDigest);
        } catch (Exception e) {
            throw new RuntimeException("MD5加密失败", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
