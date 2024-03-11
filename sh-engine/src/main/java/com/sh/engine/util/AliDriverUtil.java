package com.sh.engine.util;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 阿里云工具类
 */
public class AliDriverUtil {
    private static final byte[] hexChar = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String APP_ID = "5dde4e1bdf9e4966b387ba58f4b3fdc3";
    private static OkHttpClient okHttpClient;
    private static final Object okHttpClientLock = new Object();

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

    public static byte[] getByRange(String downloadUrl, long start, long end) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
        connection.addRequestProperty("Referer", "https://www.aliyundrive.com/");
        connection.addRequestProperty("Range", "bytes=" + start + "-" + end);
        return IOUtils.toByteArray(connection.getInputStream());
    }

    public static byte[] getByRangeInOkHttp(String downloadUrl, long start, long end) throws Exception {
        if (okHttpClient == null) {
            synchronized (okHttpClientLock) {
                if (okHttpClient == null) {
                    okHttpClient = new OkHttpClient.Builder().build();
                }
            }
        }
        Request.Builder request = new Request.Builder();
        request.url(downloadUrl);
        request.addHeader("Referer", "https://www.aliyundrive.com/");
        request.addHeader("Range", "bytes=" + start + "-" + end);
        Response response = okHttpClient.newCall(request.build()).execute();
        return response.body().bytes();
    }

    public static String genSignature(String deviceId, String userId) {
        String nonce = "6";

        // Generate private key
        SecureRandom secureRandom = new SecureRandom();
        BigInteger privateKey = new BigInteger(256, secureRandom);

        // Create ECDSA signer
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
        ECPrivateKeyParameters privateKeyParameters = new ECPrivateKeyParameters(privateKey, domain);
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, privateKeyParameters);

        // Calculate signature
        byte[] message = new byte[0];
        try {
            message = (APP_ID + ":" + deviceId + ":" + userId + ":" + nonce).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        BigInteger[] signature = signer.generateSignature(message);

        // Convert to DER format
        byte[] derSignature = new byte[64];
        byte[] rBytes = signature[0].toByteArray();
        byte[] sBytes = signature[1].toByteArray();
        int rOffset = rBytes.length > 32 ? 0 : 32 - rBytes.length;
        int sOffset = sBytes.length > 32 ? 0 : 32 - sBytes.length;
        System.arraycopy(rBytes, 0, derSignature, rOffset, Math.min(rBytes.length, 32));
        System.arraycopy(sBytes, 0, derSignature, 32 + sOffset, Math.min(sBytes.length, 32));

        // Print signature
        return bytesToHex(derSignature) + "01";
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }


    public static void main(String[] args) {
        String s = genSignature("Im5gHoG6blMCAQAAAAAZ/Oy/", "e4163fc4e00541c7a9ffebd539735258");
//        String s = genSignature("53db046c-9289-4e75-9dd9-efce615959bf", "e4163fc4e00541c7a9ffebd539735258");
        System.out.println(s);
    }

}
