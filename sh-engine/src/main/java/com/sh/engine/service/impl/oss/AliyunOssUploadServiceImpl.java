package com.sh.engine.service.impl.oss;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.service.OssUploadService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.net.URLConnection;
import java.util.Base64;
import java.util.Date;

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
public class AliyunOssUploadServiceImpl implements OssUploadService {

    @Value("${aliyun.oss.endpoint:}")
    private String endpoint;

    @Value("${aliyun.oss.bucket:}")
    private String bucketName;

    @Value("${aliyun.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret:}")
    private String accessKeySecret;

    public static final int URL_EXPIRATION_MINUTES = 60;

    private OSS ossClient;

    @PostConstruct
    public void init() {
        if (StringUtils.isAnyBlank(endpoint, bucketName, accessKeyId, accessKeySecret)) {
            log.info("Aliyun OSS is not configured");
            return;
        }
        try {
            this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            log.info("Aliyun OSS client initialized, endpoint: {}, bucket: {}", endpoint, bucketName);
        } catch (RuntimeException e) {
            log.warn("Aliyun OSS client initialization failed, errorType: {}", e.getClass().getName());
        }
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
        OSS client = getClient();
        // Upload file
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(getContentType(file.getName()));
        client.putObject(bucketName, key, file, metadata);

        // Generate signed URL
        Date expiredDate = DateUtils.addMinutes(new Date(), URL_EXPIRATION_MINUTES);
        return client.generatePresignedUrl(bucketName, key, expiredDate).toString();
    }

    @Override
    public String getImageDataUrl(String key) {
        if (StringUtils.isBlank(key)) {
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM, "OSS image object key is blank");
        }
        try (OSSObject image = getClient().getObject(bucketName, key)) {
            ObjectMetadata metadata = image.getObjectMetadata();
            String contentType = metadata == null ? null : metadata.getContentType();
            if (StringUtils.isBlank(contentType) || "application/octet-stream".equals(contentType)) {
                contentType = URLConnection.guessContentTypeFromName(key);
            }
            if (!StringUtils.startsWith(contentType, "image/")) {
                throw new StreamerRecordException(ErrorEnum.INVALID_PARAM,
                        "OSS object is not a supported image, key=" + key);
            }
            byte[] imageBytes = IOUtils.toByteArray(image.getObjectContent());
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        } catch (IOException e) {
            throw new StreamerRecordException(ErrorEnum.HTTP_REQUEST_ERROR,
                    "Failed to read OSS image, key=" + key, e);
        } catch (OSSException | ClientException e) {
            throw new StreamerRecordException(ErrorEnum.HTTP_REQUEST_ERROR,
                    "Failed to download OSS image, key=" + key, e);
        }
    }

    @Override
    public void delete(String key) {
        getClient().deleteObject(bucketName, key);
    }

    private OSS getClient() {
        if (ossClient == null) {
            throw new UnsupportedOperationException("OSS is not available: check aliyun.oss configuration");
        }
        return ossClient;
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
