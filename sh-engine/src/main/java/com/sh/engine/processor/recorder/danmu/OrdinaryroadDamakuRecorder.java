package com.sh.engine.processor.recorder.danmu;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.EnvUtil;
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

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.sh.engine.constant.RecordConstant.DAMAKU_TXT_ALL_FILE;

/**
 * 基于ordinaryroad-live-chat-client的弹幕录制器
 * https://github.com/OrdinaryRoad-Project/ordinaryroad-live-chat-client
 *
 * @Author caiwen
 * @Date 2025 08 29 23 14
 **/
@Slf4j
public class OrdinaryroadDamakuRecorder implements DanmakuRecorder {
    private static final String videoSavePath = EnvUtil.getEnvValue("sh.video-save.path");
    private final StreamerConfig config;
    private final Date recordAt;

    /**
     * 当前周期开始时间（用于计算周期内相对时间）
     */
    private final long currentPeriodStartTime;

    /**
     * 线程安全锁（切换Writer时使用）
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 总弹幕数量
     */
    private int totalDamakuCnt = 0;

    /**
     * 当前弹幕写入器
     */
    private BufferedWriter currentDanmuWriter;
    
    /**
     * 待写入的弹幕缓存列表
     */
    private List<SimpleDanmaku> pendingDanmakus = new ArrayList<>();
    
    /**
     * 批量写入大小
     */
    private static final int BATCH_SIZE = 50;

    /**
     * 弹幕客户端
     */
    private BaseLiveChatClient<?, ?, ?> client;

    /**
     * 定时任务调度器（用于周期性生成文件）
     */
    private ScheduledExecutorService scheduler;

    /**
     * 保存文件
     */
    private File saveFile;


    public OrdinaryroadDamakuRecorder( StreamerConfig config, Date recordAt) {
        Preconditions.checkNotNull(config, "config is null");
        Preconditions.checkNotNull(recordAt, "recordAt is null");

        this.config = config;
        this.recordAt = recordAt;
        this.currentPeriodStartTime = this.recordAt.getTime();
    }


    @Override
    public void init() {
        // 获取客户端
        this.client = getReceiver(config.getOriginalRoomUrl());
        Preconditions.checkNotNull(this.client, "client is null");

        // 初始化定时任务
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "damaku-monitor");
            t.setDaemon(true);
            return t;
        });

        // 生成保存文件
        this.saveFile = genDamakuFile();
    }


    @Override
    public void start() {
        createNewDanmuWriter();

        // 连接弹幕客户端
        this.client.connect(null, throwable -> {
            log.error("danmu client connect failed", throwable);
        });

        // 打印一下录制细节
        this.scheduler.scheduleAtFixedRate(this::showRecordDetail ,30, 30, TimeUnit.SECONDS);
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

        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                // 等待任务终止（最多等1秒，避免阻塞）
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("close danmu scheduler failed");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
            log.info("close danmu scheduler success");
        }
    }

    private void showRecordDetail() {
        if (this.client == null) {
            log.warn("no client for danmu");
            return;
        }

        ClientStatusEnums status = this.client.getStatus();
        if (status == ClientStatusEnums.CONNECT_FAILED || status == ClientStatusEnums.DISCONNECTED || status == ClientStatusEnums.DESTROYED) {
            log.warn("client are not connect, status: {}, try to re connect", status.name());
            this.client.connect(null, throwable -> {
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
                currentDanmuWriter.write(danmaku.toLine());
                currentDanmuWriter.newLine();
            }
            currentDanmuWriter.flush();
            pendingDanmakus.clear();
        } catch (IOException e) {
            log.error("flush danmakus failed", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 添加普通弹幕（线程安全）
     */
    public void addDanmaku(long createTime, String content) {
        lock.lock();
        try {
            totalDamakuCnt++;
            // 创建弹幕对象并加入待写入列表
            SimpleDanmaku danmaku = new SimpleDanmaku((createTime - currentPeriodStartTime) / 1000.0f, createTime / 1000, content, "ffffff");
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
            this.currentDanmuWriter = new BufferedWriter(osw);
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
            if (currentDanmuWriter != null) {
                currentDanmuWriter.close();
                log.info("close danmu file success");
            }
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
        DouyinLiveChatClientConfig config = builder.build();

        return new DouyinLiveChatClient(config, new IDouyinMsgListener() {
            @Override
            public void onDanmuMsg(DouyinBinaryFrameHandler handler, DouyinDanmuMsg msg) {
                addDanmaku(msg.getMsg().getEventTime() * 1000, msg.getContent());
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
                addDanmaku(msg.getInfo().get(0).get(4).asLong(), msg.getContent());
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
                addDanmaku(System.currentTimeMillis(), msg.getContent());
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
                addDanmaku(System.currentTimeMillis(), msg.getContent());
            }
        });
    }



    private File genDamakuFile() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String timeV = dateFormat.format(this.recordAt);

        File recordDir = new File(new File(videoSavePath, this.config.getName()), timeV);
        if (!recordDir.exists()) {
            recordDir.mkdirs();
        }
        return new File(recordDir, DAMAKU_TXT_ALL_FILE);
    }


    public static void main(String[] args) {
        OrdinaryroadDamakuRecorder recorder = new OrdinaryroadDamakuRecorder(
                StreamerConfig.builder()
                        .originalRoomUrl("https://www.huya.com/chuhe")
                        .build(),
                new Date()
        );

        try {
            recorder.init();
            recorder.start();
            Thread.sleep(1000 * 60 * 5);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            recorder.close();
        }
    }
}