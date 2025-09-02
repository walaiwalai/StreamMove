package com.sh.engine.processor.recorder.danmu;

import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.buffer.GenericCsvBuffer;
import org.apache.commons.lang3.StringUtils;
import tech.ordinaryroad.live.chat.client.bilibili.client.BilibiliLiveChatClient;
import tech.ordinaryroad.live.chat.client.bilibili.config.BilibiliLiveChatClientConfig;
import tech.ordinaryroad.live.chat.client.bilibili.listener.IBilibiliMsgListener;
import tech.ordinaryroad.live.chat.client.bilibili.netty.handler.BilibiliBinaryFrameHandler;
import tech.ordinaryroad.live.chat.client.codec.bilibili.msg.DanmuMsgMsg;
import tech.ordinaryroad.live.chat.client.codec.douyin.msg.DouyinDanmuMsg;
import tech.ordinaryroad.live.chat.client.commons.client.BaseLiveChatClient;
import tech.ordinaryroad.live.chat.client.douyin.client.DouyinLiveChatClient;
import tech.ordinaryroad.live.chat.client.douyin.config.DouyinLiveChatClientConfig;
import tech.ordinaryroad.live.chat.client.douyin.listener.IDouyinMsgListener;
import tech.ordinaryroad.live.chat.client.douyin.netty.handler.DouyinBinaryFrameHandler;

import java.io.File;

/**
 * 基于ordinaryroad-live-chat-client的弹幕录制器
 * https://github.com/OrdinaryRoad-Project/ordinaryroad-live-chat-client
 *
 * @Author caiwen
 * @Date 2025 08 29 23 14
 **/
public class OrdinaryroadDamakuRecorder extends DanmakuRecorder {
    /**
     * 弹幕客户端
     */
    private BaseLiveChatClient client;

    /**
     * 弹幕缓冲
     */
    private GenericCsvBuffer<DanmakuItem> buffer;

    public OrdinaryroadDamakuRecorder(String roomUrl) {
        super(roomUrl);
    }

    @Override
    public void init(String savePath) {
        this.buffer = new GenericCsvBuffer<>(new File(savePath, "danmu.csv").getAbsolutePath(), 100);
        this.client = getReceiver(roomUrl);

        if (this.client != null) {
            this.buffer.init();
            this.client.connect();
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.destroy();
        }

        if (this.buffer != null) {
            this.buffer.close();
        }
    }


    private BaseLiveChatClient getReceiver(String url) {
        StreamChannelTypeEnum channelEnum = StreamChannelTypeEnum.findChannelByUrl(url);
        if (channelEnum == StreamChannelTypeEnum.BILI) {
            String[] split = StringUtils.split(url, "/");
            String roomId = split[split.length - 1];
            return getBiliReceiver(roomId);
        } else if (channelEnum == StreamChannelTypeEnum.DOU_YIN) {
            String[] split = StringUtils.split(url, "/");
            String roomId = split[split.length - 1];
            return getDouyinReceiver(roomId);
        }
        return null;
    }

    private BaseLiveChatClient getBiliReceiver(String roomId) {
        BilibiliLiveChatClientConfig config = BilibiliLiveChatClientConfig.builder()
                .roomId(Long.valueOf(roomId))
                .build();
        BilibiliLiveChatClient bilibiliLiveChatClient = new BilibiliLiveChatClient(config, new IBilibiliMsgListener() {
            @Override
            public void onDanmuMsg(BilibiliBinaryFrameHandler binaryFrameHandler, DanmuMsgMsg msg) {
                IBilibiliMsgListener.super.onDanmuMsg(binaryFrameHandler, msg);

                String content = msg.getContent();
                String uid = msg.getUid();
                String username = msg.getUsername();
                Long ts = System.currentTimeMillis();
                buffer.add(new DanmakuItem(ts, uid, username, content));
            }
        });
        return bilibiliLiveChatClient;
    }

    private BaseLiveChatClient getDouyinReceiver(String roomId) {
        DouyinLiveChatClientConfig config = DouyinLiveChatClientConfig.builder()
                .roomId(Long.valueOf(roomId))
                .build();
        DouyinLiveChatClient douyinLiveChatClient = new DouyinLiveChatClient(config, new IDouyinMsgListener() {
            @Override
            public void onDanmuMsg(DouyinBinaryFrameHandler douyinBinaryFrameHandler, DouyinDanmuMsg douyinDanmuMsg) {
                IDouyinMsgListener.super.onDanmuMsg(douyinBinaryFrameHandler, douyinDanmuMsg);
                buffer.add(new DanmakuItem(
                        System.currentTimeMillis(),
                        douyinDanmuMsg.getUid(),
                        douyinDanmuMsg.getUsername(),
                        douyinDanmuMsg.getContent()
                ));
            }
        });
        return douyinLiveChatClient;
    }

    public static void main(String[] args) {
        OrdinaryroadDamakuRecorder recorder = new OrdinaryroadDamakuRecorder("https://live.douyin.com/70281056705");
        BaseLiveChatClient client1 = recorder.getDouyinReceiver("715443889201");
        client1.connect();
    }
}
