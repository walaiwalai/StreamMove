package com.sh.engine.processor.recorder;

import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.danmu.DanmakuBuffer;
import com.sh.engine.processor.recorder.danmu.DanmakuItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import tech.ordinaryroad.live.chat.client.bilibili.client.BilibiliLiveChatClient;
import tech.ordinaryroad.live.chat.client.bilibili.config.BilibiliLiveChatClientConfig;
import tech.ordinaryroad.live.chat.client.bilibili.listener.IBilibiliMsgListener;
import tech.ordinaryroad.live.chat.client.bilibili.netty.handler.BilibiliBinaryFrameHandler;
import tech.ordinaryroad.live.chat.client.codec.bilibili.msg.DanmuMsgMsg;
import tech.ordinaryroad.live.chat.client.commons.client.BaseLiveChatClient;

import java.io.File;

/**
 * @Author caiwen
 * @Date 2025 08 03 13 50
 **/
@Slf4j
public class DanmuClient {
    private String savePath;
    private String url;
    private DanmakuBuffer buffer;
    private BaseLiveChatClient danmuClient;

    public DanmuClient(String savePath, String url) {
        this.savePath = savePath;
        this.url = url;
    }

    public void init() {
        if (!isDanmuSupport(url)) {
            return;
        }

        // 弹幕缓存初始化
        this.buffer = new DanmakuBuffer(new File(savePath, "damu.csv").getAbsolutePath());
        this.buffer.init();

        // 初始化弹幕接收器
        this.danmuClient = getDamuReceiver(url);
        this.danmuClient.connect();
    }

    public void close() {
        if (danmuClient != null) {
            this.danmuClient.disconnect();
        }

        if (buffer != null) {
            this.buffer.close();
        }
    }

    private boolean isDanmuSupport(String url) {
        StreamChannelTypeEnum channelEnum = StreamChannelTypeEnum.findChannelByUrl(url);
        return channelEnum != null &&
                channelEnum.equals(StreamChannelTypeEnum.BILI) ||
                channelEnum.equals(StreamChannelTypeEnum.DOU_YIN);
    }

    private BaseLiveChatClient getDamuReceiver(String url) {
        StreamChannelTypeEnum channelEnum = StreamChannelTypeEnum.findChannelByUrl(url);
        if (channelEnum == StreamChannelTypeEnum.BILI) {
            String[] split = StringUtils.split(url, "/");
            String roomId = split[split.length - 1];
            return initBiliReceiver(roomId);
        } else if (channelEnum == StreamChannelTypeEnum.DOU_YIN) {

        }
        return null;
    }

    private BaseLiveChatClient initBiliReceiver(String roomId) {
        BilibiliLiveChatClientConfig config = BilibiliLiveChatClientConfig.builder()
                .roomId(Long.valueOf(roomId))
                .build();
        BilibiliLiveChatClient bilibiliLiveChatClient = new BilibiliLiveChatClient(config, new IBilibiliMsgListener() {
            @Override
            public void onDanmuMsg(BilibiliBinaryFrameHandler binaryFrameHandler, DanmuMsgMsg msg) {
                IBilibiliMsgListener.super.onDanmuMsg(binaryFrameHandler, msg);

                String content = msg.getContent();
                Long ts = System.currentTimeMillis();
                buffer.add(new DanmakuItem(ts, content));
            }
        });
        return bilibiliLiveChatClient;
    }
}
