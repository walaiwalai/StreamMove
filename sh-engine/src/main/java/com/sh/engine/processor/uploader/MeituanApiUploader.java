package com.sh.engine.processor.uploader;

import com.sh.engine.constant.UploadPlatformEnum;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 美团api上传上方
 *
 * @Author caiwen
 * @Date 2024 12 22 10 11
 **/
@Component
public class MeituanApiUploader extends Uploader {
    private static final String BASE_URL = "https://contents.meituan.com/api/author/";
    private static final String S3_BASE_URL = "https://s3.meituan.net/";
    private static final int CHUNK_SIZE = 104857600; // 100 MB
    public static final String TOKEN = "AgGIIYfscVU1UxX04EWXzOl2JsCtQr7k-9N47r9ZKu04UI9g3sxT6nmywsRLD6yNKchuCy3D4tNdqwAAAABrJQAArJ0ld0JWH5U7Ypsz4VOIIKA5BE63egC-6Pf74ONJEyDfa8gZ5TdcWgQag3rnYl71";
    public static final String MT_USER_ID = "170010810";
    public static final String COOKIES = "_ga=GA1.1.2012587051.1705832023; _ga_FSX5S86483=GS1.1.1705834059.2.0.1705834059.0.0.0; _ga_LYVVHCWVNG=GS1.1.1705834059.2.0.1705834059.0.0.0; _lxsdk_cuid=19261b5e0c0c8-026f8420b9bb69-4c657b58-384000-19261b5e0c1c8; iuuid=70CFB64001F185DCCB0A13AE0C0A1CA442F6DC4A83AB6A6757020DBC6A8405B9; uuid=87a4097517a8408581ce.1728219295.1.0.0; WEBDFPID=5wwvz6u4vzy35u5vy6u6xv74www1z3w280738v4v8189795823u0xv36-2043579308639-1728216162475MAGQWKCfd79fef3d01d5e9aadc18ccd4d0c95071723; lt=AgGIIYfscVU1UxX04EWXzOl2JsCtQr7k-9N47r9ZKu04UI9g3sxT6nmywsRLD6yNKchuCy3D4tNdqwAAAABrJQAArJ0ld0JWH5U7Ypsz4VOIIKA5BE63egC-6Pf74ONJEyDfa8gZ5TdcWgQag3rnYl71; u=170010810; n=%E5%B0%8F%E7%8E%8B%E7%9B%B4%E6%92%AD%E7%B2%BE%E5%BD%A9%E7%89%87%E6%AE%B5; _lxsdk_s=193ec066f5d-24-fb4-8a2%7C%7C24";

    @Override

    public String getType() {
        return UploadPlatformEnum.MEI_TUAN_API_VIDEO.getType();
    }

    @Override
    public void setUp() {

    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        return false;
    }

    public static void main(String[] args) throws IOException {
        File file = new File("G:\\stream_record\\download\\highlight.mp4");
//        String fileName = MeituanApiUploader.getFileName(file.getName());
//        System.out.println(fileName);
        String fileName = "tmp_20241222102619_CTKZetn_WeMoJc_PzJV62Oc_ruMxspa45_iPaUqtoDY=_video.highlight.mp4";
        String signedUrl = getSignedUrl(fileName);
        System.out.println(signedUrl);
    }

    public static void uploadVideo(File file) throws Exception {
        // Step 1: 获取上传文件名
        String fileName = getFileName(file.getName());
        if (fileName == null) {
            System.err.println("Failed to get file name.");
            return;
        }

        // Step 2: 获取签名 URL
        String signedUrl = getSignedUrl(fileName);
        if (signedUrl == null) {
            System.err.println("Failed to get signed URL.");
            return;
        }

        // Step 3: 分片上传
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            int partNumber = 1;

            while ((bytesRead = fis.read(buffer)) > 0) {
                uploadChunk(signedUrl, buffer, bytesRead, partNumber++);
            }
        }

        // Step 4: 完成上传
        completeUpload(signedUrl);
    }

    private static String getFileName(String extension) throws IOException {
        String url = "https://contents.meituan.com/api/author/videoUploadV2/filename?yodaReady=h5&csecplatform=4&csecversion=3.0.0";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("accept", "application/json, text/plain, */*");
        conn.setRequestProperty("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6,ja;q=0.5");
        conn.setRequestProperty("authorsource", "mt");
        conn.setRequestProperty("mtuserid", MT_USER_ID); // 替换为真实的 mtuserid
        conn.setRequestProperty("token", TOKEN); // 替换为真实的 token
        conn.setDoOutput(true);

        // 请求体
        String payload = "{\"extension\":\"" + extension + "\"}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes());
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                // 返回文件名
                return reader.readLine();
            }
        } else {
            System.err.println("Failed to get file name. HTTP code: " + responseCode);
        }
        return null;
    }


    private static String getSignedUrl(String fileName) throws IOException {
        // 请求 URL，包含必要的查询参数
        String url = "https://contents.meituan.com/api/author/videoNormalUploadV2/signature"
                + "?yodaReady=h5&csecplatform=4&csecversion=3.0.0";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("accept", "*/*");
        conn.setRequestProperty("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6,ja;q=0.5");
        conn.setRequestProperty("authorsource", "mt");
        conn.setRequestProperty("mtuserid", MT_USER_ID); // 替换为实际 mtuserid
        conn.setRequestProperty("token", TOKEN); // 替换为实际 token
        conn.setRequestProperty("cookie", COOKIES); // 替换为实际 cookie
        conn.setRequestProperty("Referer", "https://czz.meituan.com/new/uploadVideo");
        conn.setRequestProperty("Referrer-Policy", "strict-origin-when-cross-origin");
        conn.setDoOutput(true);

        // 空的请求体
        String payload = "{}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes());
        }

        // 处理响应
        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                // 返回签名 URL（假设为响应内容）
                return reader.readLine();
            }
        } else {
            System.err.println("Failed to get signed URL. HTTP code: " + responseCode);
        }
        return null;
    }


    private static void uploadChunk(String signedUrl, byte[] chunk, int length, int partNumber) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(signedUrl).openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("x-part-number", String.valueOf(partNumber));
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(chunk, 0, length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            System.out.println("Part " + partNumber + " uploaded successfully.");
        } else {
            System.err.println("Failed to upload part " + partNumber);
        }
    }

    private static void completeUpload(String signedUrl) throws IOException {
        String url = signedUrl + "/complete";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer your-token");

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            System.out.println("Upload completed successfully.");
        } else {
            System.err.println("Failed to complete upload.");
        }
    }
}
