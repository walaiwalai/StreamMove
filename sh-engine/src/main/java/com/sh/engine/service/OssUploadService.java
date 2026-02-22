package com.sh.engine.service;

import java.io.File;

/**
 * OSS (Object Storage Service) Upload Service interface.
 * <p>
 * Provides methods to upload files to cloud storage and obtain public URLs.
 * Required for ASR services that need public HTTP URLs for audio files.
 *
 * @Author : caiwen
 * @Date: 2026/2/19
 */
public interface OssUploadService {
    /**
     * Upload a file with a specific key/name to OSS.
     *
     * @param file the file to upload
     * @param key  the object key (path in bucket)
     * @return the public HTTP URL of the uploaded file
     */
    String uploadAndGetUrl(File file, String key);

    /**
     * Delete a file from OSS by its key.
     *
     * @param key the object key to delete
     */
    void delete(String key);
}
