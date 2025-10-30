package com.sh.engine.processor.recorder.danmu;

import com.sh.config.utils.ExecutorPoolUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class AssWriter implements AutoCloseable {
    /**
     * 弹幕缓存队列大小（如100）
     */
    private static final int batchSize = 100;
    /**
     * 定时flush间隔（ms）
     */
    private static final long flushIntervalMs = 5000;

    /**
     * 弹幕缓存队列
     */
    private final BlockingQueue<String> dmCacheQueue = new LinkedBlockingQueue<>(batchSize * 2);

    /**
     * 写锁
     */
    private final Lock lock = new ReentrantLock();

    private final String description;
    private final int width;
    private final int height;
    private final int dst;
    private final float dmrate;
    private final String font;
    private final int fontsize;
    private final int marginH;
    private final int marginW;
    private final float dmduration;
    private final String opacity; // 透明度（十六进制，如"FF"）
    private final String outlineColor; // 描边颜色（十六进制，如"000000"）
    private final int outlineSize;
    private final String assTextTemplate;


    private String filename;

    /**
     * 各轨道最后一条弹幕
     */
    private List<SimpleDanmaku> trackTails;
    private int superChatState = 0;
    private float latestEndTime = 0;

    /**
     * 弹幕轨道数量
     */
    private int nTracks;

    /**
     * ASS头部信息
     */
    private List<String> metaInfo = new ArrayList<>();


    /**
     * 构造函数
     */
    public AssWriter( String description, int width, int height, int dst, float dmrate,
                      String font, int fontsize, int marginH, int marginW, float dmduration,
                      float opacity, boolean autoFontsize, String outlineColor, int outlineSize,
                      String assTextTemplate ) {
        this.description = description;
        this.width = width;
        this.height = height;
        this.dst = dst;
        this.dmrate = dmrate;
        this.font = font;
        // 自动计算字号（根据视频高度比例）
        this.fontsize = autoFontsize ? (int) (height / 1080.0 * fontsize) : fontsize;
        // 计算边距（支持比例或固定值）
        this.marginH = marginH > 1 ? marginH : (int) (marginH * height);
        this.marginW = marginW > 1 ? marginW : (int) (marginW * width);
        this.dmduration = dmduration;
        // 透明度转换（0-1 -> 十六进制）
        this.opacity = String.format("%02X", 255 - (int) (opacity * 255)).toLowerCase();
        this.outlineColor = outlineColor;
        this.outlineSize = outlineSize;
        this.assTextTemplate = assTextTemplate;

        // 计算轨道数量
        nTracks = (int) (((height - dst) * dmrate) / (this.fontsize + this.marginH));
        if (nTracks <= 0) nTracks = 1; // 至少1个轨道

        // 初始化ASS头部信息
        initMetaInfo();

        // 启动定时flush任务
        startFlushTask();
    }

    private void startFlushTask() {
        ExecutorPoolUtil.getFlushPool().scheduleAtFixedRate(this::flush, 0, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 批量将缓存中的弹幕写入文件
     */
    private void flush() {
        lock.lock();
        try {
            if (filename == null || dmCacheQueue.isEmpty()) {
                return;
            }

            // 一次性读取缓存中的所有弹幕
            List<String> batchLines = new ArrayList<>();
            dmCacheQueue.drainTo(batchLines);

            // 批量写入文件
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename, true), StandardCharsets.UTF_8))) {
                for (String line : batchLines) {
                    writer.write(line);
                }
            }
        } catch (IOException e) {
            log.error("Error writing to file: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 初始化ASS文件头部信息
     */
    private void initMetaInfo() {
        metaInfo.add("[Script Info]");
        metaInfo.add("Title: " + description);
        metaInfo.add("ScriptType: v4.00+");
        metaInfo.add("Collisions: Normal");
        metaInfo.add("PlayResX: " + width);
        metaInfo.add("PlayResY: " + height);
        metaInfo.add("Timer: 100.0000");
        metaInfo.add("");
        metaInfo.add("[V4+ Styles]");
        metaInfo.add("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding");
        // 滚动弹幕样式（R2L）
        metaInfo.add(String.format(
                "Style: R2L,%s,%d,&H%sFFFFFF,&H%s000000,&H%s%s,&H4F0000FF,-1,0,0,0,100,100,0,0,1,%d,0,1,0,0,0,0",
                font, fontsize, opacity, opacity, opacity, outlineColor, outlineSize
        ));
        // 超级弹幕样式（message_box）
        metaInfo.add("Style: message_box,Microsoft YaHei,20,&H00FFFFFF,&H00FFFFFF,&H00000000,&H1E6A5149,1,0,0,0,100.00,100.00,0.00,0.00,1,1,0,7,0,0,0,1");
        metaInfo.add("");
        metaInfo.add("[Events]");
        metaInfo.add("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text");
    }

    /**
     * 打开ASS文件并写入头部信息
     */
    public void open( String filename ) throws IOException {
        lock.lock();
        try {
            this.filename = filename;
            trackTails = new ArrayList<>(nTracks);
            for (int i = 0; i < nTracks; i++) {
                // 初始化轨道（无弹幕）
                trackTails.add(null);
            }

            // 创建文件并写入头部
            File file = new File(filename);
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
                for (String line : metaInfo) {
                    writer.write(line + "\n");
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 添加弹幕（自动区分类型）
     */
    public boolean add( Danmaku danmaku ) {
        if (filename == null) {
            throw new IllegalStateException("ASS文件未打开，请先调用open()");
        }
        if (danmaku instanceof SimpleDanmaku) {
            return addSimple((SimpleDanmaku) danmaku, true);
        }
        return false;
    }


    @Override
    public void close() {
        lock.lock();
        try {
            flush();
            filename = null;
            if (trackTails != null) {
                trackTails.clear();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 添加普通弹幕（带冲突检测）
     */
    private boolean addSimple( SimpleDanmaku danmaku, boolean calcCollision ) {
        lock.lock();
        try {
            int trackId = 0;
            float maxDist = -1e5f;

            // 计算当前弹幕与轨道最后一条弹幕的距离
            for (int i = 0; i < nTracks; i++) {
                SimpleDanmaku tailDm = trackTails.get(i);
                float dist = tailDistance(tailDm, danmaku.getTime());
                if (dist > 0.2 * width && dist > marginW) {
                    trackId = i;
                    maxDist = dist;
                    break;
                }
                if (dist > maxDist) {
                    maxDist = dist;
                    trackId = i;
                }
            }

            // 冲突检测：距离不足则忽略
            if (calcCollision && maxDist < marginW) {
                return false;
            }

            // 计算弹幕长度和位置
            int dmLength = getLength(danmaku.getText());
            int x0 = width; // 起始X坐标（右侧进入）
            int x1 = -dmLength; // 结束X坐标（左侧离开）
            int y = fontsize + (fontsize + marginH) * trackId + dst; // Y坐标（轨道位置）

            // 计算开始/结束时间（秒）
            float t0 = danmaku.getTime();
            float t1 = t0 + dmduration;

            // 转换时间格式为ASS格式（HH:MM:SS.xx）
            String t0Str = secToHms(t0);
            String t1Str = secToHms(t1);

            // 构建ASS Dialogue行
            StringBuilder dmInfo = new StringBuilder();
            dmInfo.append(String.format("Dialogue: 0,%s,%s,R2L,,0,0,0,,", t0Str, t1Str));
            dmInfo.append(String.format("{\\move(%d,%d,%d,%d)}", x0, y, x1, y));
            dmInfo.append(String.format("{\\alpha&H%s\\1c%s&}", opacity, rgbToBgr(danmaku.getColor())));
            // 处理文本（替换换行，转义特殊字符）
            String content = danmaku.getText().replace("\n", " ").replace("\r", " ")
                    .replace(",", "\\,").replace("\\", "\\\\");
            dmInfo.append(content);
            dmInfo.append("\n");

            // 构建单条弹幕的ASS行
            dmCacheQueue.offer(dmInfo.toString());
            // 若达到批量阈值，立即flush
            if (dmCacheQueue.size() >= batchSize) {
                flush();
            }
            trackTails.set(trackId, danmaku);
            return true;
        } catch (Exception e) {
            log.error("Failed to write to ASS file", e);
            return false;
        } finally {
            lock.unlock();
        }
    }
//
//    /**
//     * 添加超级弹幕
//     */
//    public void addSuperChat(SuperChatDanmaku superChat) {
//        lock.lock();
//        try {
//            if (filename == null) {
//                throw new IllegalStateException("ASS文件未打开，请先调用open()");
//            }
//
//            // 格式化超级弹幕内容（每行15字）
//            List<String> contentLines = new ArrayList<>();
//            String content = superChat.getContent();
//            for (int i = 0; i < content.length(); i += 15) {
//                int end = Math.min(i + 15, content.length());
//                contentLines.add(content.substring(i, end));
//            }
//            String formattedContent = String.join("\\N", contentLines)
//                    .replace(",", "\\,").replace("\\", "\\\\");
//
//            // 更新超级弹幕状态
//            float currentTime = superChat.getTime();
//            if (currentTime > latestEndTime) {
//                superChatState = 0; // 重置状态（无重叠）
//            }
//            superChatState++;
//            latestEndTime = currentTime + 20; // 超级弹幕持续20秒
//
//            // 计算Y坐标（避免重叠）
//            int baseY = 100;
//            int yOffset = 120;
//            int y = baseY + (superChatState - 1) * yOffset;
//
//            // 时间格式转换
//            String t0Str = secToHms(currentTime);
//            String t1Str = secToHms(currentTime + 20);
//
//            // 构建超级弹幕ASS内容（多行为一个完整弹幕）
//            StringBuilder dmInfo = new StringBuilder();
//            // 背景框1
//            dmInfo.append(String.format(
//                    "Dialogue: 0,%s,%s,message_box,,0000,0000,0000,,{\\pos(0,%d)\\c&HFF6600\\shad0\\p1}m 0 0 l 250 0 l 250 81 l 0 81\n",
//                    t0Str, t1Str, y
//            ));
//            // 背景框2
//            dmInfo.append(String.format(
//                    "Dialogue: 0,%s,%s,message_box,,0000,0000,0000,,{\\pos(0,%d)\\shad0\\p1\\c&HCC0000}m 0 0 l 250 0 l 250 80 l 0 80\n",
//                    t0Str, t1Str, y + 40
//            ));
//            // 用户名
//            dmInfo.append(String.format(
//                    "Dialogue: 1,%s,%s,message_box,,0000,0000,0000,,{\\pos(6,%d)\\c&HFFFFFF\\fs15\\b1\\q2}%s\n",
//                    t0Str, t1Str, y + 5, superChat.getUname().replace(",", "\\,")
//            ));
//            // 价格
//            dmInfo.append(String.format(
//                    "Dialogue: 1,%s,%s,message_box,,0000,0000,0000,,{\\pos(6,%d)\\c&HFFFFFF\\fs15\\q2}SuperChat CNY %d\n",
//                    t0Str, t1Str, y + 20, superChat.getPrice()
//            ));
//            // 内容
//            dmInfo.append(String.format(
//                    "Dialogue: 1,%s,%s,message_box,,0000,0000,0000,,{\\pos(6,%d)\\c&HFFFFFF\\q2}%s\n",
//                    t0Str, t1Str, y + 40, formattedContent
//            ));
//
//            // 写入文件
//            try (BufferedWriter writer = new BufferedWriter(
//                    new OutputStreamWriter(new FileOutputStream(filename, true), StandardCharsets.UTF_8)
//            )) {
//                writer.write(dmInfo.toString());
//            }
//
//            superChatTails.add(superChat);
//        } catch (IOException e) {
//            e.printStackTrace();
//        } finally {
//            lock.unlock();
//        }
//    }

    /**
     * 计算弹幕文本长度（考虑中英文差异）
     */
    private int getLength( String text ) {
        int length = 0;
        for (char c : text.toCharArray()) {
            // 中文字符占1个fontsize，英文字符占0.5个
            if (String.valueOf(c).getBytes(StandardCharsets.UTF_8).length == 1) {
                length += 0.5 * fontsize;
            } else {
                length += fontsize;
            }
        }
        return length;
    }

    /**
     * 计算当前弹幕与轨道最后一条弹幕的距离（用于冲突检测）
     */
    private float tailDistance( SimpleDanmaku tailDm, float currentTime ) {
        if (tailDm == null) {
            return 1e5f; // 轨道为空，距离无穷大
        }
        int dmLength = getLength(tailDm.getText());
        // 距离公式：(当前时间 - 上条弹幕时间) * (弹幕长度 + 屏幕宽度) / 持续时间 - 弹幕长度
        return (currentTime - tailDm.getTime()) * (dmLength + width) / dmduration - dmLength;
    }

    /**
     * 秒转换为ASS时间格式（HH:MM:SS.xx）
     */
    private String secToHms( float seconds ) {
        int totalSec = (int) seconds;
        int hours = totalSec / 3600;
        int minutes = (totalSec % 3600) / 60;
        int sec = totalSec % 60;
        int ms = (int) ((seconds - totalSec) * 100); // 保留两位小数

        DecimalFormat df = new DecimalFormat("00");
        return String.format("%s:%s:%s.%s",
                df.format(hours),
                df.format(minutes),
                df.format(sec),
                df.format(ms)
        );
    }

    /**
     * RGB颜色转BGR（ASS格式要求BGR）
     */
    private String rgbToBgr( String rgb ) {
        if (rgb.length() != 6) {
            return "FFFFFF"; // 默认白色
        }
        String r = rgb.substring(0, 2);
        String g = rgb.substring(2, 4);
        String b = rgb.substring(4, 6);
        return b + g + r; // BGR顺序
    }

    public static void main( String[] args ) throws IOException {
        // 1. 创建AssWriter实例（参数与Python版对应）
        AssWriter assWriter = new AssWriter(
                "抖音直播弹幕", // description
                1920, // width
                1080, // height
                50, // dst
                0.8f, // dmrate
                "SimHei", // font
                28, // fontsize
                10, // marginH
                20, // marginW
                5.0f, // dmduration（弹幕持续5秒）
                1.0f, // opacity（不透明）
                false, // auto_fontsize
                "000000", // outlineColor（黑色描边）
                2, // outlineSize
                null // assTextTemplate
        );

        // 2. 打开ASS文件
        assWriter.open("./test_danmaku.ass");

        // 3. 添加普通弹幕
        for (int i = 0; i < 20; i++) {
            // 模拟不同时间发送的弹幕（间隔0.5秒）
            long time = (long) (i * 0.5);
            String text = "普通弹幕测试 " + i;
            String color = "FF0000"; // 红色
            assWriter.add(new SimpleDanmaku(time, text, color));
        }

        // 5. 关闭资源
        assWriter.close();
        System.out.println("ASS文件生成完成：./test_danmaku.ass");
    }
}


