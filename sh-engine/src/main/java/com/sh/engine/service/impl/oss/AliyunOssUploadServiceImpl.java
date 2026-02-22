package com.sh.engine.service.impl.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.sh.engine.service.OssUploadService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.util.Date;
import java.util.UUID;

/**
 * Alibaba Cloud OSS implementation of OssUploadService.
 * <p>
 * Configuration properties:
 * <ul>
 *   <li>aliyun.oss.endpoint - OSS endpoint (e.g., oss-cn-hangzhou.aliyuncs.com)</li>
 *   <li>aliyun.oss.bucket - OSS bucket name</li>
 *   <li>aliyun.oss.access-key-id - Access Key ID</li>
 *   <li>aliyun.oss.access-key-secret - Access Key Secret</li>
 *   <li>aliyun.oss.url-expiration-minutes - URL expiration time in minutes (default: 60)</li>
 * </ul>
 *
 * @Author : caiwen
 * @Date: 2026/2/19
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "oss.provider", havingValue = "aliyun")
public class AliyunOssUploadServiceImpl implements OssUploadService {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.bucket}")
    private String bucketName;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    public static final int URL_EXPIRATION_MINUTES = 60;

    private OSS ossClient;

    @PostConstruct
    public void init() {
        this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        log.info("Aliyun OSS client initialized, endpoint: {}, bucket: {}", endpoint, bucketName);
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("Aliyun OSS client shut down");
        }
    }

    @Override
    public String uploadAndGetUrl(File file, String key) {
        if (ossClient == null) {
            throw new IllegalStateException("OSS client not initialized, check configuration");
        }

        try {
            // Upload file
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(getContentType(file.getName()));
            ossClient.putObject(bucketName, key, file, metadata);

            // Generate signed URL
            Date expiredDate = DateUtils.addMinutes(new Date(), URL_EXPIRATION_MINUTES);
            return ossClient.generatePresignedUrl(bucketName, key, expiredDate).toString();
        } catch (Exception e) {
            log.error("Failed to upload file to OSS: {}", file.getAbsolutePath(), e);
            throw new RuntimeException("OSS upload failed", e);
        }
    }

    @Override
    public void delete(String key) {
        if (ossClient == null) {
            log.warn("OSS client not initialized, cannot delete: {}", key);
            return;
        }

        try {
            ossClient.deleteObject(bucketName, key);
        } catch (Exception e) {
            log.warn("Failed to delete object from OSS: {}", key, e);
        }
    }

    private String getContentType(String fileName) {
        if (fileName.endsWith(".wav")) {
            return "audio/wav";
        } else if (fileName.endsWith(".mp3")) {
            return "audio/mpeg";
        } else if (fileName.endsWith(".mp4")) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }
}
