package com.sh.engine.model.store;

import java.io.File;

/**
 * @Author caiwen
 * @Date 2024 01 07 10 52
 **/
public interface CosStoreHandler {
    void upload(File file, String cosPath);

    void delete(String bucketName, String cosObjectName);
}
