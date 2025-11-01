package com.sh.engine.processor.recorder.danmu;

import com.sh.config.utils.ExecutorPoolUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class AssWriter implements AutoCloseable {
    /**
     * 弹幕缓存队列大小
     */
    private static final int batchSize = 100;

    /**
     * 总共写入弹幕大小
     */
    private static int totalSize = 0;

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

    /**
     * 透明度（十六进制，如"FF"）
     */
    private final String opacity;

    /**
     * 描边颜色（十六进制，如"000000"）
     */
    private final String outlineColor;
    private final int outlineSize;
    private final String assTextTemplate;
    private String filename;
    private int superChatState = 0;
    private float latestEndTime = 0;

    /**
     * 各轨道最后一条弹幕
     */
    private List<SimpleDanmaku> trackTails = new ArrayList<>();
    private List<SuperChatDanmaku> superChatTails = new ArrayList<>();
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
    public AssWriter(String description, int width, int height, int dst, float dmrate,
                     String font, int fontsize, int marginH, int marginW, float dmduration,
                     float opacity, boolean autoFontsize, String outlineColor, int outlineSize,
                     String assTextTemplate) {
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
        if (nTracks <= 0) {
            nTracks = 1;
        };

        // 初始化ASS头部信息
        initMetaInfo();
    }

    public AssWriter(String description, int width, int height) {
        this(description, width, height, 10, 0.3f, "SimSun", 20, 10, 20, 15.0f, 0.8f, true, "000000", 2, null);
    }

    /**
     * 批量将缓存中的弹幕写入文件
     */
    private void flush() {
        if (filename == null || dmCacheQueue.isEmpty()) {
            return;
        }

        boolean flushSuccess = false;
        lock.lock();
        try {
            // 一次性读取缓存中的所有弹幕
            List<String> batchLines = new ArrayList<>();
            dmCacheQueue.drainTo(batchLines);

            // 批量写入文件
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename, true), StandardCharsets.UTF_8))) {
                for (String line : batchLines) {
                    writer.write(line);
                }
            }
            flushSuccess = true;
            totalSize += batchLines.size();
        } catch (IOException e) {
            log.error("Error writing ass file", e);
        } finally {
            lock.unlock();
            log.info("write ass finish, filePath: {}, totalSize: {}, flushSuccess: {}", this.filename, totalSize, flushSuccess);
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
    public void open(String filename) throws IOException {
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
    public boolean add(Danmaku danmaku) {
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
    private boolean addSimple(SimpleDanmaku danmaku, boolean calcCollision) {
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
            dmInfo.append(filterDanmakuText(danmaku.getText()));
            dmInfo.append("\n");

            // 添加到缓存队列
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


    /**
     * 处理弹幕文本：过滤特殊字符，确保ASS文件正常解析和显示
     * @param rawText 原始弹幕文本
     * @return 过滤后的安全文本
     */
    private String filterDanmakuText(String rawText) {
        if (StringUtils.isBlank(rawText)) {
            return "";
        }

        // 1. 先处理控制字符：换行、回车、制表符、换页符 → 替换为普通空格
        String filteredText = rawText.replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ")
                .replace("\f", " ");

        // 2. 处理ASS语法敏感字符：逗号、冒号、分号、中括号、等号 → 转义（加反斜杠）
        filteredText = filteredText.replace(",", "\\,")  // ASS中逗号是字段分隔符，需转义
                .replace(":", "\\:")  // 避免与ASS时间格式（如 00:00:00.00）冲突
                .replace(";", "\\;")  // ASS中分号是注释符，需转义
                .replace("[", "\\[")  // 避免与ASS段落标记（如 [V4+ Styles]）冲突
                .replace("]", "\\]")
                .replace("=", "\\=");  // 避免与ASS键值对（如 Fontsize=28）冲突

        // 3. 处理转义符本身：反斜杠 → 双反斜杠（ASS中反斜杠是转义符，需二次转义）
        filteredText = filteredText.replace("\\", "\\\\");

        // 4. 处理不可见/异常字符：全角空格→普通空格，零宽字符→删除（可选，根据需求调整）
        filteredText = filteredText.replace("　", " ")  // 全角空格（Unicode：U+3000）→ 半角空格（U+0020）
                .replaceAll("[\\p{Cf}]", "");  // 匹配所有控制字符（零宽、不可见字符）并删除

        // 5. 处理潜在风险字符：尖括号、引号（可选，避免显示异常）
        filteredText = filteredText.replace("<", "\\<")
                .replace(">", "\\>")
                .replace("\"", "\\\"")  // 双引号转义
                .replace("'", "\\'");   // 单引号转义

        return filteredText;
    }

    /**
     * 添加超级弹幕
     */
    public void addSuperChat(SuperChatDanmaku superChat) {
        lock.lock();
        try {
            if (filename == null) {
                throw new IllegalStateException("ASS文件未打开，请先调用open()");
            }

            // 格式化超级弹幕内容（每行15字）
            List<String> contentLines = new ArrayList<>();
            String content = superChat.getContent();
            for (int i = 0; i < content.length(); i += 15) {
                int end = Math.min(i + 15, content.length());
                contentLines.add(content.substring(i, end));
            }
            String formattedContent = String.join("\\N", contentLines)
                    .replace(",", "\\,").replace("\\", "\\\\");

            // 更新超级弹幕状态
            float currentTime = superChat.getTime();
            if (currentTime > latestEndTime) {
                superChatState = 0; // 重置状态（无重叠）
            }
            superChatState++;
            latestEndTime = currentTime + 20; // 超级弹幕持续20秒

            // 计算Y坐标（避免重叠）
            int baseY = 100;
            int yOffset = 120;
            int y = baseY + (superChatState - 1) * yOffset;

            // 时间格式转换
            String t0Str = secToHms(currentTime);
            String t1Str = secToHms(currentTime + 20);

            // 构建超级弹幕ASS内容（多行为一个完整弹幕）
            StringBuilder dmInfo = new StringBuilder();
            // 背景框1
            dmInfo.append(String.format(
                    "Dialogue: 0,%s,%s,message_box,,0000,0000,0000,,{\\pos(0,%d)\\c&HFF6600\\shad0\\p1}m 0 0 l 250 0 l 250 81 l 0 81\n",
                    t0Str, t1Str, y
            ));
            // 背景框2
            dmInfo.append(String.format(
                    "Dialogue: 0,%s,%s,message_box,,0000,0000,0000,,{\\pos(0,%d)\\shad0\\p1\\c&HCC0000}m 0 0 l 250 0 l 250 80 l 0 80\n",
                    t0Str, t1Str, y + 40
            ));
            // 用户名
            dmInfo.append(String.format(
                    "Dialogue: 1,%s,%s,message_box,,0000,0000,0000,,{\\pos(6,%d)\\c&HFFFFFF\\fs15\\b1\\q2}%s\n",
                    t0Str, t1Str, y + 5, superChat.getUname().replace(",", "\\,")
            ));
            // 价格
            dmInfo.append(String.format(
                    "Dialogue: 1,%s,%s,message_box,,0000,0000,0000,,{\\pos(6,%d)\\c&HFFFFFF\\fs15\\q2}SuperChat CNY %d\n",
                    t0Str, t1Str, y + 20, superChat.getPrice()
            ));
            // 内容
            dmInfo.append(String.format(
                    "Dialogue: 1,%s,%s,message_box,,0000,0000,0000,,{\\pos(6,%d)\\c&HFFFFFF\\q2}%s\n",
                    t0Str, t1Str, y + 40, formattedContent
            ));

            // 添加到缓存队列，而不是直接写入
            dmCacheQueue.offer(dmInfo.toString());
            // 若达到批量阈值，立即flush
            if (dmCacheQueue.size() >= batchSize) {
                flush();
            }

            superChatTails.add(superChat);
        } catch (Exception e) {
            log.error("Failed to add super chat", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 计算弹幕文本长度（考虑中英文差异）
     */
    private int getLength(String text) {
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
    private float tailDistance(SimpleDanmaku tailDm, float currentTime) {
        if (tailDm == null) {
            // 轨道为空，距离无穷大
            return 1e5f;
        }
        int dmLength = getLength(tailDm.getText());
        // 距离公式：(当前时间 - 上条弹幕时间) * (弹幕长度 + 屏幕宽度) / 持续时间 - 弹幕长度
        return (currentTime - tailDm.getTime()) * (dmLength + width) / dmduration - dmLength;
    }

    /**
     * 秒转换为ASS时间格式（HH:MM:SS.xx）
     */
    private String secToHms(float seconds) {
        int totalSec = (int) seconds;
        int hours = totalSec / 3600;
        int minutes = (totalSec % 3600) / 60;
        int sec = totalSec % 60;
        int ms = (int) ((seconds - totalSec) * 100);

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
    private String rgbToBgr(String rgb) {
        if (rgb == null || rgb.length() != 6) {
            return "FFFFFF";
        }
        String r = rgb.substring(0, 2);
        String g = rgb.substring(2, 4);
        String b = rgb.substring(4, 6);
        return b + g + r;
    }

    public static void main(String[] args) throws IOException {
        // 总时长：20分40秒 = 20*60 + 40 = 1240秒
        final int totalSeconds = 1240;
        // 弹幕数量
        final int danmuCount = 5000;
        // 随机数生成器（用于生成随机文本和颜色）
        Random random = new Random();

        // 记录开始时间（用于性能测试）
        long startTime = System.currentTimeMillis();

        // 1. 创建AssWriter实例
        AssWriter assWriter = new AssWriter(
                "性能测试弹幕",
                1920,
                1080,
                10,
                0.3f,
                "SimSun",
                20,
                10,
                20,
                15.0f,
                0.8f,
                true,
                "000000",
                2,
                null
        );

        // 2. 打开ASS文件
        String filePath = "G:\\stream_record\\performance_test_danmaku.ass";
        assWriter.open(filePath);

        // 3. 生成1000条弹幕（均匀分布在1240秒内）
        for (int i = 0; i < danmuCount; i++) {
            // 计算当前弹幕的时间（均匀分布）
            // 公式：第i条弹幕时间 = (总时长 / (总数量-1)) * i （确保最后一条在1240秒左右）
            float time = (totalSeconds / (float) (danmuCount - 1)) * i;

            // 生成随机文本（长度5-15字）
            int textLength = 5 + random.nextInt(11); // 5-15
            String text = generateRandomText(textLength, i);

            // 生成随机颜色（6位十六进制）
//            String color = generateRandomColor(random);

            // 添加弹幕
            assWriter.add(new SimpleDanmaku(time, text, "ffffff"));
        }

        // 4. 关闭资源
        assWriter.close();

        // 计算耗时（性能测试结果）
        long endTime = System.currentTimeMillis();
        long costMs = endTime - startTime;

        System.out.printf("ASS文件生成完成：%s%n", filePath);
        System.out.printf("生成信息：%d条弹幕，总时长%d秒%n", danmuCount, totalSeconds);
        System.out.printf("性能测试：生成耗时%.2f秒%n", costMs / 1000.0);
    }

    /**
     * 生成随机文本（包含序号，模拟真实弹幕内容）
     */
    private static String generateRandomText(int length, int index) {
        // 常用汉字库（简化版）
        String chars = "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸金木水火土天地日月风云雨雪春夏秋冬前后左右上下高低大小多少远近里外快慢强弱轻重好坏对错真假公私正反";
        StringBuilder sb = new StringBuilder();
        // 前缀：弹幕序号
        sb.append("[").append(index).append("]");
        // 随机添加汉字
        Random random = new Random();
        for (int i = 0; i < length; i++) {
            int pos = random.nextInt(chars.length());
            sb.append(chars.charAt(pos));
        }
        return sb.toString();
    }

    /**
     * 生成随机6位十六进制颜色码
     */
    private static String generateRandomColor(Random random) {
        // 生成0-0xFFFFFF的随机数，转换为6位十六进制
        int colorValue = random.nextInt(0xFFFFFF + 1);
        return String.format("%06X", colorValue);
    }
}