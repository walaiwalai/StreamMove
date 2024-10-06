package com.sh.engine.service.msgProcess;

import com.sh.config.manager.CacheManager;
import com.sh.engine.processor.uploader.DouyinUploader;
import com.sh.engine.processor.uploader.MeituanUploader;
import com.sh.message.service.MessageProcessHandler;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * @Author caiwen
 * @Date 2024 10 06 21 36
 **/
@Component

public class MeituanMsgProcessHandler implements MessageProcessHandler {
    @Resource
    private CacheManager cacheManager;

    @Override
    public void process(String message) {
        cacheManager.set(MeituanUploader.AUTH_CODE_KEY, message, 60, TimeUnit.SECONDS);
    }

    @Override
    public String getType() {
        return "mt_v_code";    }
}
