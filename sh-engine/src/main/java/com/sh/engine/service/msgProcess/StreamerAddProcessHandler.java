package com.sh.engine.service.msgProcess;

import com.alibaba.fastjson.JSON;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.repo.StreamerRepoService;
import com.sh.message.service.MessageProcessHandler;
import com.sh.message.service.MsgSendService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @Author : caiwen
 * @Date: 2025/2/2
 */
@Component
public class StreamerAddProcessHandler implements MessageProcessHandler {
    @Resource
    private StreamerRepoService streamerRepoService;
    @Resource
    private MsgSendService msgSendService;

    @Override
    public void process( String message ) {
        StreamerConfig streamerConfig = JSON.parseObject(message, StreamerConfig.class);
        streamerRepoService.insert(streamerConfig, "o2");
        msgSendService.sendText(streamerConfig.getName() + "添加成功！");
    }

    @Override
    public String getType() {
        return "streamerAdd";
    }
}
