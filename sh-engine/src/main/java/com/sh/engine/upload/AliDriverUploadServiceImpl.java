package com.sh.engine.upload;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.video.LocalVideo;
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
        String diverId = ConfigFetcher.getInitConfig().getDiverId();
        String refreshToken = ConfigFetcher.getInitConfig().getRefreshToken();

        AliDiverStoreClient client = new AliDiverStoreClient(new AliStoreBucket(refreshToken, diverId));
        String rootId = client.getRootId();
        List<AliFileDTO> aliFileDTOS = client.listAllObject(rootId);


        for (LocalVideo localVideo : localVideos) {
            if (!StringUtils.equals("highlight", localVideo.getTitle())) {
                continue;
            }
            JSONObject res = client.putFile("642ae96aff4988c15a254418ad88883b21b9a5ca", "test1.mp4", FileUtils.readFileToByteArray(new File(localVideo.getLocalFileFullPath())));
        }
        return true;
    }


    public static void main(String[] args) throws Exception {
        AliDriverUploadServiceImpl service = new AliDriverUploadServiceImpl();
        service.upload(Lists.newArrayList(LocalVideo.builder()
                .localFileFullPath("F:\\video\\download\\TheShy\\2024-01-31-03-31-43\\highlight.mp4")
                .title("highlight")
                .build()), BaseUploadTask.builder().build());
    }
}
