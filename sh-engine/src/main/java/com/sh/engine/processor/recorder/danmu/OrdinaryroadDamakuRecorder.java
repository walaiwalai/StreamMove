package com.sh.engine.processor.recorder.danmu;

import com.alibaba.fastjson.JSONWriter;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.StreamChannelTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import tech.ordinaryroad.live.chat.client.bilibili.client.BilibiliLiveChatClient;
import tech.ordinaryroad.live.chat.client.bilibili.config.BilibiliLiveChatClientConfig;
import tech.ordinaryroad.live.chat.client.bilibili.listener.IBilibiliMsgListener;
import tech.ordinaryroad.live.chat.client.bilibili.netty.handler.BilibiliBinaryFrameHandler;
import tech.ordinaryroad.live.chat.client.codec.bilibili.msg.DanmuMsgMsg;
import tech.ordinaryroad.live.chat.client.codec.douyin.constant.DouyinGiftCountCalculationTimeEnum;
import tech.ordinaryroad.live.chat.client.codec.douyin.msg.DouyinDanmuMsg;
import tech.ordinaryroad.live.chat.client.codec.douyu.msg.ChatmsgMsg;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.MessageNoticeMsg;
import tech.ordinaryroad.live.chat.client.commons.client.BaseLiveChatClient;
import tech.ordinaryroad.live.chat.client.commons.client.enums.ClientStatusEnums;
import tech.ordinaryroad.live.chat.client.douyin.client.DouyinLiveChatClient;
import tech.ordinaryroad.live.chat.client.douyin.config.DouyinLiveChatClientConfig;
import tech.ordinaryroad.live.chat.client.douyin.listener.IDouyinMsgListener;
import tech.ordinaryroad.live.chat.client.douyin.netty.handler.DouyinBinaryFrameHandler;
import tech.ordinaryroad.live.chat.client.douyu.client.DouyuLiveChatClient;
import tech.ordinaryroad.live.chat.client.douyu.config.DouyuLiveChatClientConfig;
import tech.ordinaryroad.live.chat.client.douyu.listener.IDouyuMsgListener;
import tech.ordinaryroad.live.chat.client.douyu.netty.handler.DouyuBinaryFrameHandler;
import tech.ordinaryroad.live.chat.client.huya.client.HuyaLiveChatClient;
import tech.ordinaryroad.live.chat.client.huya.config.HuyaLiveChatClientConfig;
import tech.ordinaryroad.live.chat.client.huya.listener.IHuyaMsgListener;
import tech.ordinaryroad.live.chat.client.huya.netty.handler.HuyaBinaryFrameHandler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于ordinaryroad-live-chat-client的弹幕录制器
 * https://github.com/OrdinaryRoad-Project/ordinaryroad-live-chat-client
 *
 * @Author caiwen
 * @Date 2025 08 29 23 14
 **/
@Slf4j
public class OrdinaryroadDamakuRecorder implements DanmakuRecorder {
//    private static final Boolean PROXY_ENABLE = EnvUtil.getEnvBoolean("proxy.server.enable");
//    private static final String PROXY_HOST = EnvUtil.getEnvValue("proxy.server.host");
//    private static final Integer PROXY_PORT = EnvUtil.getEnvInt("proxy.server.port");
//    private static final String PROXY_USER_NAME = EnvUtil.getEnvValue("proxy.server.username");
//    private static final String PROXY_USER_PASSWORD = EnvUtil.getEnvValue("proxy.server.password");

    private final StreamerConfig config;

    /**
     * 当前周期开始时间（用于计算周期内相对时间）
     */
    private long currentPeriodStartTime;

    /**
     * 弹幕客户端
     */
    private BaseLiveChatClient<?, ?, ?> client;

    /**
     * 线程安全锁（切换AssWriter时使用）
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 保存文件
     */
    private File saveFile;

    /**
     * 总弹幕数量
     */
    private int totalDamakuCnt = 0;

    /**
     * 当前弹幕写入器
     */
    private JSONWriter currentDanmuWriter;
    
    /**
     * 待写入的弹幕缓存列表
     */
    private List<SimpleDanmaku> pendingDanmakus = new ArrayList<>();
    
    /**
     * 批量写入大小
     */
    private static final int BATCH_SIZE = 100;


    public OrdinaryroadDamakuRecorder(StreamerConfig config) {
        this.config = config;
    }


    @Override
    public void init(File saveFile) {
        this.saveFile = saveFile;

        // 初始化客户端
        this.client = getReceiver(config.getRoomUrl());
    }

    @Override
    public void refresh(File saveFile) {
        this.saveFile = saveFile;
        this.currentPeriodStartTime = System.currentTimeMillis();
        if (currentDanmuWriter != null) {
            closeCurrentDanmuWriter();
        }

        // 创建新的弹幕文件
        if (currentDanmuWriter == null) {
            createNewDanmuWriter();
        }
    }


    @Override
    public void start() {
        if (this.client == null) {
            log.error("no client for danmu record, will skip");
            return;
        }
        if (currentDanmuWriter == null) {
            refresh(saveFile);
        }

        // 连接弹幕客户端
        this.client.connect(() -> {
            log.info("danmu client connected，savePath：{}", saveFile.getAbsolutePath());
        }, throwable -> {
            log.error("danmu client connect failed", throwable);
        });
    }

    @Override
    public void close() {
        // 关闭弹幕客户端，停止接收新弹幕
        if (client != null) {
            client.destroy();
            client = null;
            log.info("close danmu client success");
        }
        // 关闭当前弹幕文件
        if (currentDanmuWriter != null) {
            closeCurrentDanmuWriter();
        }
    }

    @Override
    public void showRecordDetail() {
        if (this.client == null) {
            log.warn("no client for danmu");
            return;
        }

        ClientStatusEnums status = this.client.getStatus();
        if (status == ClientStatusEnums.CONNECT_FAILED || status == ClientStatusEnums.DISCONNECTED || status == ClientStatusEnums.DESTROYED) {
            log.warn("client are not connect, status: {}, try to re connect", status.name());
            this.client.connect(() -> {}, throwable -> {
                log.error("danmu client reconnect failed", throwable);
            });
        } else {
            log.warn("client are normal, status: {}, path: {}, bufferSize: {}, total: {}", status.name(), this.saveFile.getAbsolutePath(), pendingDanmakus.size(), totalDamakuCnt);
        }
    }

    /**
     * 刷新待写入的弹幕到文件
     */
    private void flushPendingDanmakus() {
        if (pendingDanmakus.isEmpty() || currentDanmuWriter == null) {
            return;
        }
        lock.lock();
        try {
            for (SimpleDanmaku danmaku : pendingDanmakus) {
                currentDanmuWriter.writeObject(danmaku);
            }
            log.warn("flushed {} danmakus to file", pendingDanmakus.size());
            pendingDanmakus.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 添加普通弹幕（线程安全）
     */
    public void addDanmaku(String content) {
        lock.lock();
        try {
            totalDamakuCnt++;
            // 创建弹幕对象并加入待写入列表
            SimpleDanmaku danmaku = new SimpleDanmaku((System.currentTimeMillis() - currentPeriodStartTime) / 1000.0f, content, "ffffff");
            pendingDanmakus.add(danmaku);
            
            // 如果待写入列表达到批次大小，则立即刷新
            if (pendingDanmakus.size() >= BATCH_SIZE) {
                flushPendingDanmakus();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 创建新的弹幕文件
     */
    private void createNewDanmuWriter() {
        // 创建新的弹幕写入器
        try {
            OutputStreamWriter osw = new OutputStreamWriter(Files.newOutputStream(saveFile.toPath()), StandardCharsets.UTF_8);
            currentDanmuWriter = new JSONWriter(osw);
            currentDanmuWriter.startArray();
            log.info("create new danmu file, path: {}", saveFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("init danmu writer failed", e);
        }
    }

    /**
     * 关闭当前弹幕文件
     */
    private void closeCurrentDanmuWriter() {
        // 刷新剩余的弹幕
        flushPendingDanmakus();
        try {
            currentDanmuWriter.endArray();
            currentDanmuWriter.close();
            log.info("close danmu file success");
        } catch (IOException e) {
            log.error("close danmu file failed", e);
        }
        currentDanmuWriter = null;
    }

    private BaseLiveChatClient<?, ?, ?> getReceiver(String url) {
        StreamChannelTypeEnum channelEnum = StreamChannelTypeEnum.findChannelByUrl(url);
        if (channelEnum == StreamChannelTypeEnum.BILI) {
            String[] split = StringUtils.split(url, "/");
            String roomId = split[split.length - 1];
            return getBiliReceiver(roomId);
        } else if (channelEnum == StreamChannelTypeEnum.DOU_YIN) {
            String[] split = StringUtils.split(url, "/");
            String roomId = split[split.length - 1];
            return getDouyinReceiver(roomId);
        } else if (channelEnum == StreamChannelTypeEnum.DOUYU) {
            String[] split = StringUtils.split(url, "/");
            String roomId = split[split.length - 1];
            return getDouyuReceiver(roomId);
        } else if (channelEnum == StreamChannelTypeEnum.HUYA) {
            String[] split = StringUtils.split(url, "/");
            String roomId = split[split.length - 1];
            return getHuyaReceiver(roomId);
        }
        return null;
    }


    /**
     * 创建抖音弹幕接收器
     */
    private BaseLiveChatClient<?, ?, ?> getDouyinReceiver(String roomId) {
        DouyinLiveChatClientConfig.DouyinLiveChatClientConfigBuilder<?, ?> builder = DouyinLiveChatClientConfig.builder()
                .roomId(roomId)
                .userAgent(RecordConstant.USER_AGENT)
                .giftCountCalculationTime(DouyinGiftCountCalculationTimeEnum.COMBO_END);
//        if (BooleanUtils.isTrue(PROXY_ENABLE)) {
//            builder.socks5ProxyHost(PROXY_HOST).socks5ProxyPort(PROXY_PORT);
//            if (!"nvl".equals(PROXY_USER_NAME)) {
//                builder.socks5ProxyUsername(PROXY_USER_NAME).socks5ProxyPassword(PROXY_USER_PASSWORD);
//            }
//        }
        DouyinLiveChatClientConfig config = builder.build();

        return new DouyinLiveChatClient(config, new IDouyinMsgListener() {
            @Override
            public void onDanmuMsg(DouyinBinaryFrameHandler handler, DouyinDanmuMsg msg) {
                addDanmaku(msg.getContent());
            }
        });
    }

    private BaseLiveChatClient<?, ?, ?> getBiliReceiver(String roomId) {
        BilibiliLiveChatClientConfig config = BilibiliLiveChatClientConfig.builder()
                .roomId(roomId)
                .build();
        return new BilibiliLiveChatClient(config, new IBilibiliMsgListener() {
            @Override
            public void onDanmuMsg(BilibiliBinaryFrameHandler handler, DanmuMsgMsg msg) {
                addDanmaku(msg.getContent());
            }
        });
    }

    private BaseLiveChatClient<?, ?, ?> getHuyaReceiver(String roomId) {
        HuyaLiveChatClientConfig config = HuyaLiveChatClientConfig.builder()
                .roomId(roomId)
                .build();
        return new HuyaLiveChatClient(config, new IHuyaMsgListener() {
            @Override
            public void onDanmuMsg(HuyaBinaryFrameHandler handler, MessageNoticeMsg msg) {
                log.info("receive huya danmu: {}", msg.getContent());
                addDanmaku(msg.getContent());
            }
        });
    }

    private BaseLiveChatClient<?, ?, ?> getDouyuReceiver(String roomId) {
        DouyuLiveChatClientConfig config = DouyuLiveChatClientConfig.builder()
                .roomId(roomId)
                .build();
        return new DouyuLiveChatClient(config, new IDouyuMsgListener() {
            @Override
            public void onDanmuMsg(DouyuBinaryFrameHandler handler, ChatmsgMsg msg) {
                log.info("receive douyu danmu: {}", msg.getContent());
                addDanmaku(msg.getContent());
            }
        });
    }


    public static void main(String[] args) {
        OrdinaryroadDamakuRecorder recorder = new OrdinaryroadDamakuRecorder(
                StreamerConfig.builder()
                        .roomUrl("https://www.douyu.com/810975")
                        .recordMode("t_150")
                        .build()
        );

        try {
            recorder.init(new File("G:\\stream_record\\download\\mytest-mac\\2025-11-07-22-27-01"));
            recorder.start();
            Thread.sleep(1000 * 60 * 5);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            recorder.close();
        }
    }
}