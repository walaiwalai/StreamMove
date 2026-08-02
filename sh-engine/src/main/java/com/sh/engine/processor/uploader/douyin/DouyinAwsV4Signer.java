package com.sh.engine.processor.uploader.douyin;

import lombok.Value;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AWS Signature V4 implementation used by ByteDance VOD and ImageX. */
public final class DouyinAwsV4Signer {
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    public SignedAwsRequest sign(String method,
                                 String endpoint,
                                 Map<String, String> query,
                                 byte[] body,
                                 Credentials credentials,
                                 String region,
                                 String service) {
        return sign(method, endpoint, query, body, credentials, region, service, Instant.now());
    }

    SignedAwsRequest sign(String method,
                          String endpoint,
                          Map<String, String> query,
                          byte[] body,
                          Credentials credentials,
                          String region,
                          String service,
                          Instant now) {
        byte[] requestBody = body == null ? new byte[0] : body;
        String amzDate = DATE_TIME_FORMATTER.format(now);
        String date = DATE_FORMATTER.format(now);
        String payloadHash = sha256Hex(requestBody);
        String canonicalQuery = canonicalQuery(query);

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        if (!"GET".equalsIgnoreCase(method)) {
            headers.put("x-amz-content-sha256", payloadHash);
        }
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-security-token", credentials.getSessionToken());

        List<String> signedHeaderNames = new ArrayList<>(headers.keySet());
        Collections.sort(signedHeaderNames);
        String signedHeaders = String.join(";", signedHeaderNames);
        StringBuilder canonicalHeaders = new StringBuilder();
        for (String name : signedHeaderNames) {
            canonicalHeaders.append(name).append(':').append(headers.get(name).trim()).append('\n');
        }

        String canonicalRequest = method.toUpperCase() + "\n/\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] dateKey = hmac(("AWS4" + credentials.getSecretAccessKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] regionKey = hmac(dateKey, region);
        byte[] serviceKey = hmac(regionKey, service);
        byte[] signingKey = hmac(serviceKey, "aws4_request");
        String signature = hex(hmac(signingKey, stringToSign));

        String authorization = ALGORITHM + " Credential=" + credentials.getAccessKeyId() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        headers.put("Authorization", authorization);
        String url = endpoint + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        return new SignedAwsRequest(url, headers);
    }

    private static String canonicalQuery(Map<String, String> query) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            entries.add(percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue()));
        }
        Collections.sort(entries);
        return String.join("&", entries);
    }

    private static String percentEncode(String input) {
        byte[] bytes = input == null ? new byte[0] : input.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 3);
        for (byte value : bytes) {
            int c = value & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((c >>> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private static String sha256Hex(byte[] input) {
        try {
            return hex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            value.append(String.format("%02x", b));
        }
        return value.toString();
    }

    @Value
    public static class Credentials {
        String accessKeyId;
        String secretAccessKey;
        String sessionToken;
    }

    @Value
    public static class SignedAwsRequest {
        String url;
        Map<String, String> headers;
    }
}
