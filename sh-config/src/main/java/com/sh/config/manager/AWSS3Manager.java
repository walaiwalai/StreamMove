package com.sh.config.manager;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 10 29 23 09
 **/
@Slf4j
public class AWSS3Manager {
    private static AmazonS3 s3Client;
    private static final String BUCKET_NAME = "wx-channel";
    private static final long PART_SIZE = 5 * 1024 * 1024;
    private static String endpoint = "oos-sccd.ctyunapi.cn";


    public static AmazonS3 getClient() {
        if (s3Client == null) {
            synchronized (AWSS3Manager.class) {
                if (s3Client == null) {
                    String accessKeyId = ConfigFetcher.getInitConfig().getOosAccessKeyId();
                    String secretAccessKey = ConfigFetcher.getInitConfig().getOosSecretAccessKey();
                    String endpoint = ConfigFetcher.getInitConfig().getOosEndpoint();
                    String region = ConfigFetcher.getInitConfig().getOosRegion();
                    s3Client = AmazonS3ClientBuilder.standard()
                            .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKeyId, secretAccessKey)))
                            .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, region))
                            .withPathStyleAccessEnabled(false)
                            .withChunkedEncodingDisabled(true)
                            .build();
                }
            }
        }
        return s3Client;
    }

    public static void multipartUpload(String keyName, File file) {
        // 初始化分块上传
        InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(BUCKET_NAME, keyName);
        InitiateMultipartUploadResult initResponse = getClient().initiateMultipartUpload(initRequest);

        List<PartETag> partETags = new ArrayList<>();
        long fileLength = file.length();
        long bytePosition = 0;
        int partNumber = 1;
        try (FileInputStream is = new FileInputStream(file)) {
            while (bytePosition < fileLength) {
                // 计算本次分块大小
                long partSize = Math.min(PART_SIZE, (fileLength - bytePosition));

                // 创建分块请求
                UploadPartRequest uploadRequest = new UploadPartRequest()
                        .withBucketName(BUCKET_NAME)
                        .withKey(keyName)
                        .withUploadId(initResponse.getUploadId())
                        .withPartNumber(partNumber++)
                        .withFileOffset(bytePosition)
                        .withFile(file)
                        .withPartSize(partSize);

                // 上传分块并添加ETag到列表
                UploadPartResult uploadResult = getClient().uploadPart(uploadRequest);
                partETags.add(uploadResult.getPartETag());

                // 更新已上传的字节数
                bytePosition += partSize;
                log.info("upload chunk success, file: {}, progress: {}/{}", file.getAbsolutePath(), bytePosition * 100.0f / fileLength, 100);
            }

            // 完成分块上传
            CompleteMultipartUploadRequest completeRequest = new CompleteMultipartUploadRequest(
                    BUCKET_NAME, keyName, initResponse.getUploadId(), partETags);

            getClient().completeMultipartUpload(completeRequest);
            log.info("upload file complete, file: {}, keyName: {}", file.getAbsolutePath(), keyName);
        } catch (Exception e) {
            getClient().abortMultipartUpload(new AbortMultipartUploadRequest(
                    BUCKET_NAME, keyName, initResponse.getUploadId()));
            log.error("upload file error, file: {}", file.getAbsolutePath(), e);
            throw new StreamerRecordException(ErrorEnum.UPLOAD_CHUNK_ERROR);
        }
    }

    public static String generatePresignedUrl(String objectKey) {
        // 设置链接的过期时间
        Date expiration = new Date();
        expiration.setTime(expiration.getTime() + 60 * 60 * 24 * 7 * 1000L);

        // 创建预签名请求
        GeneratePresignedUrlRequest generatePresignedUrlRequest =
                new GeneratePresignedUrlRequest(BUCKET_NAME, objectKey)
                        .withMethod(com.amazonaws.HttpMethod.GET)
                        .withExpiration(expiration);
        try {
            // 生成预签名URL
            return getClient().generatePresignedUrl(generatePresignedUrlRequest).toString();
        } catch (Exception e) {
            log.error("gen presigned url error, objKey: {}", objectKey);
            return null;
        }
    }

    public static String generateV2PresignedUrl(String objectKey, int days) {
        String url = null;
        try {
            String endpoint2 = String.format("http://%s/%s/%s", endpoint, BUCKET_NAME, URLEncoder.encode(objectKey, "UTF-8"));
            String expires = String.valueOf((System.currentTimeMillis() / 1000) + 60L * 24 * 60 * days);
            String canonicalString = String.format("GET\n\n\n%s\n/%s/%s", expires, BUCKET_NAME, objectKey);
            String signature = sign(canonicalString, ConfigFetcher.getInitConfig().getOosSecretAccessKey());

            url = String.format("%s?AWSAccessKeyId=%s&Expires=%s&Signature=%s", endpoint2, ConfigFetcher.getInitConfig().getOosAccessKeyId(), expires, URLEncoder.encode(signature, "UTF-8"));
        } catch (Exception e) {
            log.error("gen presigned url error, objKey: {}", objectKey);
        }
        return url;
    }

    private static String sign(String data, String secretKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(rawHmac);
    }
}
