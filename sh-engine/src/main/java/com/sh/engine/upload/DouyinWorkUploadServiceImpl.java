package com.sh.engine.upload;

import com.sh.config.model.video.LocalVideo;
import com.sh.engine.model.upload.BaseUploadTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 05 19 19 07
 **/
@Slf4j
@Component
public class DouyinWorkUploadServiceImpl extends AbstractWorkUploadService {
    @Override
    public String getName() {
        return "DOU_YIN";
    }

    @Override
    public boolean upload(List<LocalVideo> localVideos, BaseUploadTask task) throws Exception {
        return false;
    }


    public static void main(String[] args) {
        DouyinWorkUploadServiceImpl service = new DouyinWorkUploadServiceImpl();
    }
}
