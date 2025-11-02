package com.sh.engine.processor.recorder.danmu;

import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.model.video.StreamMetaInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import tech.ordinaryroad.live.chat.client.bilibili.client.BilibiliLiveChatClient;
import tech.ordinaryroad.live.chat.client.bilibili.config.BilibiliLiveChatClientConfig;
import tech.ordinaryroad.live.chat.client.bilibili.listener.IBilibiliMsgListener;
import tech.ordinaryroad.live.chat.client.bilibili.netty.handler.BilibiliBinaryFrameHandler;
import tech.ordinaryroad.live.chat.client.codec.bilibili.msg.DanmuMsgMsg;
import tech.ordinaryroad.live.chat.client.codec.douyin.constant.DouyinGiftCountCalculationTimeEnum;
import tech.ordinaryroad.live.chat.client.codec.douyin.msg.DouyinDanmuMsg;
import tech.ordinaryroad.live.chat.client.commons.client.BaseLiveChatClient;
import tech.ordinaryroad.live.chat.client.commons.client.enums.ClientStatusEnums;
import tech.ordinaryroad.live.chat.client.douyin.client.DouyinLiveChatClient;
import tech.ordinaryroad.live.chat.client.douyin.config.DouyinLiveChatClientConfig;
import tech.ordinaryroad.live.chat.client.douyin.listener.IDouyinMsgListener;
import tech.ordinaryroad.live.chat.client.douyin.netty.handler.DouyinBinaryFrameHandler;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于ordinaryroad-live-chat-client的弹幕录制器
 * https://github.com/OrdinaryRoad-Project/ordinaryroad-live-chat-client
 *
 * @Author caiwen
 * @Date 2025 08 29 23 14
 **/
@Slf4j
public class OrdinaryroadDamakuRecorder extends DanmakuRecorder {
    private static final Boolean proxyEnable = EnvUtil.getEnvBoolean("proxy.server.enable");
    private static final String proxyHost = EnvUtil.getEnvValue("proxy.server.host");
    private static final Integer proxyPort = EnvUtil.getEnvInt("proxy.server.port");
    /**
     * 计数器（用于生成文件名：P01.ass、P02.ass...）
     */
    private static final AtomicInteger FILE_COUNT = new AtomicInteger(1);

    /**
     * 全局开始时间（用于计算周期）
     */
    private long globalStartTime;

    /**
     * 当前周期开始时间（用于计算周期内相对时间）
     */
    private long currentPeriodStartTime;

    /**
     * 周期间隔（秒）
     */
    private int intervalSeconds;

    /**
     * 弹幕客户端
     */
    private BaseLiveChatClient client;

    /**
     * 当前活跃的AssWriter实例（每个周期一个）
     */
    private AssWriter currentAssWriter;

    /**
     * 定时任务调度器（用于周期性生成文件）
     */
    private ScheduledExecutorService scheduler;

    /**
     * 线程安全锁（切换AssWriter时使用）
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 保存路径
     */
    private String savePath;

    /**
     * 视频元信息（分辨率等）
     */
    private StreamMetaInfo metaInfo;


    public OrdinaryroadDamakuRecorder(StreamerConfig config) {
        super(config);
    }

    @Override
    public void init(String savePath, StreamMetaInfo metaInfo) {
        this.savePath = savePath;
        this.metaInfo = metaInfo;
        this.intervalSeconds = Integer.parseInt(config.getRecordMode().substring(2));

        // 初始化客户端
        this.client = getReceiver(config.getRoomUrl());
    }


    @Override
    public void start() {
        if (this.client == null) {
            log.error("no client for danmu record, will skip");
            return;
        }

        this.globalStartTime = System.currentTimeMillis();
        this.currentPeriodStartTime = this.globalStartTime;

        // 启动定时任务：周期性生成ASS文件
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "danmaku-file-scheduler");
            t.setDaemon(true);
            return t;
        });

        // 立即执行一次（创建第一个文件），之后每隔intervalSeconds执行
        this.scheduler.scheduleAtFixedRate(
                this::recordDanmaku,
                0,
                intervalSeconds,
                TimeUnit.SECONDS
        );

        // 檢查狀態
        this.scheduler.scheduleAtFixedRate(
                this::checkClientStatus,
                30,
                30,
                TimeUnit.SECONDS
        );

        // 连接弹幕客户端
        this.client.connect(() -> {
            log.info("danmu client connected");
        }, throwable -> {
            log.error("danmu client connect failed", throwable);
        });

        log.info("danmu recorder start，interval：{}s，savePath：{}", intervalSeconds, savePath);
    }

    @Override
    public void close() {
        // 1. 先关闭定时任务调度器，停止文件切换
        if (scheduler != null) {
            scheduler.shutdownNow(); // 立即停止所有任务
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

        // 2. 再关闭弹幕客户端，停止接收新弹幕
        if (client != null) {
            client.destroy();
            client = null;
            log.info("close danmu client success");
        }

        // 3. 最后关闭当前ASS文件，确保所有数据写入
        lock.lock();
        try {
            if (currentAssWriter != null) {
                currentAssWriter.close();
                currentAssWriter = null;
                log.info("close ass writer success");
            }
        } finally {
            lock.unlock();
        }
    }


    /**
     * 切换ASS文件（线程安全）
     */
    private void recordDanmaku() {
        lock.lock();
        try {
            // 关闭上一个周期的ASS文件
            if (currentAssWriter != null) {
                currentAssWriter.close();
                log.info("closed last period ass writer，cost：{}s", (System.currentTimeMillis() - currentPeriodStartTime) / 1000);
            }

            // 更新当前周期开始时间
            currentPeriodStartTime = System.currentTimeMillis();

            // 生成新文件名（P01.ass、P02.ass...）
            int count = FILE_COUNT.getAndIncrement();
            File assFile = new File(savePath, String.format("P%02d.ass", count));

            // 创建新的AssWriter
            currentAssWriter = new AssWriter("直播弹幕", metaInfo.getWidth(), metaInfo.getHeight());

            // 打开新文件
            currentAssWriter.open(assFile.getAbsolutePath());
            log.info("create new ass writer, path: {}，resolution：{}x{}", assFile.getAbsolutePath(), metaInfo.getWidth(), metaInfo.getHeight());
        } catch (IOException e) {
            log.error("switch ass writer failed", e);
        } finally {
            lock.unlock();
        }
    }

    private void checkClientStatus() {
        ClientStatusEnums status = this.client.getStatus();
        if (status == ClientStatusEnums.CONNECT_FAILED || status == ClientStatusEnums.DISCONNECTED || status == ClientStatusEnums.DESTROYED) {
            log.warn("client are not connect, status: {}, try to re connect", status.name());
            this.client.connect(() -> {
                log.info("danmu client reconnect success");
            }, throwable -> {
                log.error("danmu client reconnect failed", throwable);
            });
        } else {
            log.info("client are normal, status: {}", status.name());
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
        return new BilibiliLiveChatClient(config, new IBilibiliMsgListener() {
            @Override
            public void onDanmuMsg(BilibiliBinaryFrameHandler handler, DanmuMsgMsg msg) {
                addDanmaku(msg.getContent());
            }
        });
    }

    /**
     * 创建抖音弹幕接收器
     */
    private BaseLiveChatClient getDouyinReceiver(String roomId) {
        DouyinLiveChatClientConfig.DouyinLiveChatClientConfigBuilder<?, ?> builder = DouyinLiveChatClientConfig.builder()
                .roomId(Long.valueOf(roomId))
                .giftCountCalculationTime(DouyinGiftCountCalculationTimeEnum.COMBO_END);
        if (BooleanUtils.isTrue(proxyEnable)) {
            builder.socks5ProxyHost(proxyHost).socks5ProxyPort(proxyPort);
        }
        DouyinLiveChatClientConfig config = builder.build();

        return new DouyinLiveChatClient(config, new IDouyinMsgListener() {
            @Override
            public void onDanmuMsg(DouyinBinaryFrameHandler handler, DouyinDanmuMsg msg) {
                addDanmaku(msg.getContent());
            }
        });
    }

    /**
     * 添加普通弹幕（线程安全）
     */
    private void addDanmaku(String content) {
        if (currentAssWriter == null) {
            log.error("in active ass writer，skip danmu：{}", content);
            return;
        }

        lock.lock();
        try {
            // 计算当前周期内的相对时间（秒）
            float time = (float) ((System.currentTimeMillis() - currentPeriodStartTime) / 1000.0);
            currentAssWriter.add(new SimpleDanmaku(time, content, "ffffff"));
        } finally {
            lock.unlock();
        }
    }


    public static void main(String[] args) {
        OrdinaryroadDamakuRecorder recorder = new OrdinaryroadDamakuRecorder(
                StreamerConfig.builder()
                        .roomUrl("https://live.douyin.com/962565925628")
                        .recordMode("t_150")
                        .build()
        );
        StreamMetaInfo streamMetaInfo = new StreamMetaInfo();
        streamMetaInfo.setWidth(1920);
        streamMetaInfo.setHeight(1080);

        try {
            recorder.init("G:\\stream_record\\download\\test\\2025-11-02-10-04-00", streamMetaInfo);
            recorder.start();
            Thread.sleep(1000 * 60 * 5);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            recorder.close();
        }
    }
}