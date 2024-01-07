package com.sh.engine.model.store;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import com.sh.config.manager.ConfigFetcher;

/**
 * @Author caiwen
 * @Date 2024 01 07 10 57
 **/
public class CosClientFactory {
    public static COSClient create() {
        String secretId = ConfigFetcher.getInitConfig().getTencentCosConfig().getSecretId();
        String secretKey = ConfigFetcher.getInitConfig().getTencentCosConfig().getSecretKey();
        String region = ConfigFetcher.getInitConfig().getTencentCosConfig().getRegion();

        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        COSClient cosClient = new COSClient(cred, clientConfig);
        return cosClient;
    }
}
