package com.sh.engine.upload;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.video.LocalVideo;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.alidriver.AliDiverStoreClient;
import com.sh.engine.model.alidriver.AliFileDTO;
import com.sh.engine.model.alidriver.AliStoreBucket;
import com.sh.engine.model.upload.BaseUploadTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 03 09 18 23
 **/
@Slf4j
@Component
public class AliDriverUploadServiceImpl extends AbstractWorkUploadService {
    @Override
    public String getName() {
        return "ALI_DRIVER";
    }

    @Override
    public boolean upload(List<LocalVideo> localVideos, BaseUploadTask task) throws Exception {
        String refreshToken = ConfigFetcher.getInitConfig().getRefreshToken();
        String targetFileId = ConfigFetcher.getInitConfig().getTargetFileId();

        AliDiverStoreClient client = new AliDiverStoreClient(new AliStoreBucket(refreshToken));
        String rootId = client.getRootId();

        for (LocalVideo localVideo : localVideos) {
            if (!StringUtils.equals("highlight", localVideo.getTitle())) {
                continue;
            }
            String fileName = StreamerInfoHolder.getCurStreamerName() + "_" + localVideo.getTitle() + "_" + System.currentTimeMillis() + ".mp4";
            JSONObject res = client.putBigFile(targetFileId, fileName, new File(localVideo.getLocalFileFullPath()));
        }
        return true;
    }


    public static void main(String[] args) throws Exception {
        AliDriverUploadServiceImpl service = new AliDriverUploadServiceImpl();
        service.upload(Lists.newArrayList(LocalVideo.builder()
                .localFileFullPath("/Users/caiwen/Desktop/download/TheShy/2024-01-31-03-31-43/seg-1.ts")
                .title("highlight")
                .build()), BaseUploadTask.builder().build());
    }
}
