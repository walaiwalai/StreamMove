package com.sh.engine.model.store;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.StorageClass;
import com.sh.config.manager.ConfigFetcher;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * @Author caiwen
 * @Date 2024 01 07 11 15
 **/
@Component
public class CosStoreHandlerImpl implements CosStoreHandler {
    @Override
    public void upload(File file, String cosPath) {
        COSClient cosClient = CosClientFactory.create();

        String bucketName = ConfigFetcher.getInitConfig().getTencentCosConfig().getBucketName();
        PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, cosPath, file);
        // 低频存储
        putObjectRequest.setStorageClass(StorageClass.Standard_IA);

        PutObjectResult result = cosClient.putObject(putObjectRequest);
//        if (result.getCiUploadResult().get)
    }

    @Override
    public void delete(String bucketName, String cosObjectName) {

    }
}
