package com.sh.engine.service.impl.oss;

import com.sh.engine.service.OssUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * No-op implementation of OssUploadService.
 * <p>
 * This implementation throws an exception when called and is used when
 * OSS upload is disabled or no OSS provider is configured.
 *
 * @Author : caiwen
 * @Date: 2026/2/21
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "oss.provider", havingValue = "none", matchIfMissing = true)
public class OssUploadServiceNoopImpl implements OssUploadService {

    @Override
    public String uploadAndGetUrl(File file, String key) {
        log.debug("No-op OSS upload service called for file: {}", file.getAbsolutePath());
        throw new UnsupportedOperationException(
                "OSS upload is not configured. Please configure oss.provider to enable OSS upload.");
    }

    @Override
    public void delete(String key) {
        log.debug("No-op OSS delete service called for key: {}", key);
        // No-op, nothing to delete
    }
}
