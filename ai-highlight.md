# 基于弹幕和AI的直播高光片段检测系统 - 实现计划

## 上下文与背景

StreamerRecord 是一个 Java 直播录制系统，当前已实现：
1. 多平台直播录制（Bilibili、抖音、斗鱼、虎牙等）
2. 弹幕录制（存储为 damaku.txt，格式：`time__SEP__timestamp__content__color`）
3. 视频处理插件架构（WorkProcessStageProcessor 协调，VideoProcessPlugin 接口）
4. 现有 LOL 高光剪辑插件（基于 OCR 识别 KDA）

本计划实现一个全新的视频处理插件，通过"Java 负责算力与统计 + AI大模型负责理解与决策"的架构，基于弹幕密度和AI语义分析自动识别高光片段。

## 实现目标

创建 `DanmakuAIHighlightPlugin` 插件，实现以下流程：
1. **阶段一**：Java 弹幕波峰检测（按10秒时间桶计算情绪烈度，Z-Score识别波峰）
2. **阶段二**：按需提取音频并ASR转写（仅处理疑似高光片段）
3. **阶段三**：AI大模型语义判定（LangChain4j架构，判断是否为真正高光，生成标题和精准时间）
4. **阶段四**：FFmpeg 精准剪辑输出

---

## 实现方案

### 1. 新增数据模型

**文件**: `sh-engine/src/main/java/com/sh/engine/model/danmaku/TimeBucket.java`
```java
@Data
public class TimeBucket {
    private int startTime;           // 桶起始时间（秒）
    private int endTime;             // 桶结束时间（秒）
    private int danmakuCount;        // 弹幕数量
    private int emotionScore;        // 情绪烈度得分
    private List<SimpleDanmaku> danmakus;  // 原始弹幕列表
}
```

**文件**: `sh-engine/src/main/java/com/sh/engine/model/danmaku/HighlightSegment.java`
```java
@Data
public class HighlightSegment {
    private int startTime;           // 片段起始时间（秒）
    private int endTime;             // 片段结束时间（秒）
    private int emotionScore;        // 情绪得分
    private File videoFile;          // 所属视频文件
    private double videoOffset;      // 在视频文件中的偏移（秒）
    private String asrText;          // ASR转写文本
    private boolean isHighlight;     // DeepSeek判定结果
    private int score;               // 精彩程度评分(1-10)
    private String reason;           // 判定理由
    private String suggestedTitle;   // 建议标题
    private String exactStartTime;   // 精准起始时间(HH:mm:ss)
    private String exactEndTime;     // 精准结束时间(HH:mm:ss)
}
```

**文件**: `sh-engine/src/main/java/com/sh/engine/model/danmaku/HighlightAnalysisResult.java`
```java
@Data
public class HighlightAnalysisResult {
    private boolean isHighlight;
    private int score;
    private String reason;
    private String exactClipStart;
    private String exactClipEnd;
    private String suggestedTitle;
}
```

### 2. 弹幕分析服务

**文件**: `sh-engine/src/main/java/com/sh/engine/service/DanmakuAnalysisService.java`

核心方法：
```java
public interface DanmakuAnalysisService {
    /**
     * 分析弹幕，返回疑似高光时间段列表
     */
    List<TimeBucket> analyzeDanmakuPeak(String recordPath, List<SimpleDanmaku> danmakus);

    /**
     * 计算单条弹幕情绪分
     */
    int calculateEmotionScore(String text);

    /**
     * 使用Z-Score算法识别波峰
     */
    List<TimeBucket> detectPeaks(List<TimeBucket> buckets);
}
```

实现要点：
- 按 10 秒划分时间桶
- 情绪分计算：基础分1分，含连续重复字符或高情绪标点(`哈哈哈`、`？？？`、`！！！`)则加分
- Z-Score算法：emotionScore > μ + 2σ 标记为波峰
- 时间扩展：以波峰为中心，前后各扩展30秒形成疑似高光片段

### 3. ASR服务（阿里云实现）

**依赖**: `sh-engine/pom.xml`

```xml
<!-- 阿里云DashScope SDK -->
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>dashscope-sdk-java</artifactId>
    <version>2.18.0</version>
</dependency>
```

**文件**: `sh-engine/src/main/java/com/sh/engine/service/AsrService.java`

```java
public interface AsrService {
    /**
     * 提取音频片段并转写为文本
     * @param videoFile 源视频文件
     * @param startSeconds 起始时间（秒）
     * @param endSeconds 结束时间（秒）
     * @return 带时间戳的ASR文本列表
     */
    List<AsrSegment> transcribeSegment(File videoFile, int startSeconds, int endSeconds);
}

@Data
public class AsrSegment {
    private int startTime;     // 起始时间（秒）
    private int endTime;       // 结束时间（秒）
    private String text;       // 转写文本
}
```

**文件**: `sh-engine/src/main/java/com/sh/engine/model/ffmpeg/AudioExtractCmd.java`

FFmpeg音频提取命令，用于从视频片段提取WAV格式音频：
```bash
ffmpeg -i input.mp4 -ss startSeconds -t duration -vn -ar 16000 -ac 1 -acodec pcm_s16le -y output.wav
```

参数说明：
- `-vn`: 禁用视频
- `-ar 16000`: 采样率16kHz（阿里云推荐）
- `-ac 1`: 单声道
- `-acodec pcm_s16le`: 16bit PCM编码

**文件**: `sh-engine/src/main/java/com/sh/engine/service/impl/AliyunAsrServiceImpl.java`

阿里云ASR实现，基于DashScope SDK：

```java
package com.sh.engine.service.impl;

import com.alibaba.dashscope.audio.asr.transcription.*;
import com.alibaba.dashscope.utils.Constants;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.service.AsrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
public class AliyunAsrServiceImpl implements AsrService {

    @Value("${asr.aliyun.api-key:}")
    private String apiKey;

    @Value("${asr.aliyun.model:fun-asr}")
    private String model;

    private static final String API_URL = "https://dashscope.aliyuncs.com/api/v1";

    @PostConstruct
    public void init() {
        Constants.baseHttpApiUrl = API_URL;
    }

    @Override
    public List<AsrSegment> transcribeSegment(File videoFile, int startSeconds, int endSeconds) {
        // 1. 提取音频到临时文件
        File tempAudio = extractAudio(videoFile, startSeconds, endSeconds);

        // 2. 上传音频获取公网URL
        // 阿里云ASR要求输入源为可通过公网访问的文件URL，不支持本地文件直传
        String audioUrl = uploadToOssAndGetUrl(tempAudio);

        // 3. 构建请求参数
        TranscriptionParam param = TranscriptionParam.builder()
                .apiKey(apiKey)
                .model(model)
                .parameter("language_hints", new String[]{"zh", "en"})
                .fileUrls(Arrays.asList(audioUrl))
                .build();

        // 4. 提交异步任务
        Transcription transcription = new Transcription();
        TranscriptionResult result = transcription.asyncCall(param);
        String taskId = result.getTaskId();
        log.info("ASR task submitted, taskId: {}", taskId);

        // 5. 阻塞等待任务完成
        result = transcription.wait(
                TranscriptionQueryParam.FromTranscriptionParam(param, taskId));

        // 6. 解析结果
        return parseResult(result, startSeconds);
    }

    private List<AsrSegment> parseResult(TranscriptionResult result, int segmentOffset) {
        List<AsrSegment> segments = new ArrayList<>();

        for (TranscriptionTaskResult taskResult : result.getResults()) {
            if (taskResult.getSubTaskStatus() != TaskStatus.SUCCEEDED) {
                log.warn("ASR subtask failed: {}", taskResult.getMessage());
                continue;
            }

            String transcriptionUrl = taskResult.getTranscriptionUrl();
            // 下载并解析JSON结果
            JsonObject jsonResult = downloadJson(transcriptionUrl);

            JsonArray transcripts = jsonResult.getAsJsonArray("transcripts");
            for (JsonElement transcript : transcripts) {
                JsonArray sentences = transcript.getAsJsonObject().getAsJsonArray("sentences");
                for (JsonElement sentence : sentences) {
                    JsonObject sentObj = sentence.getAsJsonObject();
                    AsrSegment segment = new AsrSegment();
                    // 时间戳为毫秒，转换为秒，并加上片段偏移
                    segment.setStartTime(segmentOffset + sentObj.get("begin_time").getAsInt() / 1000);
                    segment.setEndTime(segmentOffset + sentObj.get("end_time").getAsInt() / 1000);
                    segment.setText(sentObj.get("text").getAsString());
                    segments.add(segment);
                }
            }
        }
        return segments;
    }

    private JsonObject downloadJson(String urlString) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.connect();
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            return new Gson().fromJson(reader, JsonObject.class);
        } catch (Exception e) {
            log.error("Failed to download ASR result from: {}", urlString, e);
            throw new RuntimeException("Failed to download ASR result", e);
        }
    }
}
```

**关键说明**：

1. **导入包**: `com.alibaba.dashscope.audio.asr.transcription.*`
2. **核心类**: `Transcription`, `TranscriptionParam`, `TranscriptionResult`, `TranscriptionQueryParam`, `TranscriptionTaskResult`
3. **调用流程**: `asyncCall`提交异步任务 → `wait`阻塞等待结果 → 从`transcriptionUrl`下载JSON
4. **文件上传**: 阿里云ASR要求公网可访问的URL，需先上传OSS获取URL（`uploadToOssAndGetUrl`方法需额外实现）
5. **模型选择**: 默认`fun-asr`，支持中文、英文、日语

**返回结果格式**：
```json
{
    "transcripts": [{
        "sentences": [{
            "begin_time": 760,
            "end_time": 3240,
            "text": "Hello World，这里是阿里巴巴语音实验室。",
            "words": [{
                "begin_time": 760,
                "end_time": 1000,
                "text": "Hello"
            }]
        }]
    }]
}
```

注：阿里云ASR是异步API，需要先提交任务，再轮询等待结果。时间戳为毫秒，需转换为秒。

### 4. AI高光分析服务（LangChain4j架构）

**文件**: `sh-engine/src/main/java/com/sh/engine/service/HighlightAnalyzerService.java`

```java
public interface HighlightAnalyzerService {
    /**
     * 分析片段是否为高光
     */
    HighlightAnalysisResult analyze(List<AsrSegment> asrSegments,
                                     List<SimpleDanmaku> danmakus,
                                     int segmentStartTime,
                                     int segmentEndTime);
}
```

**文件**: `sh-engine/src/main/java/com/sh/engine/service/impl/LangChain4jHighlightAnalyzer.java`

使用LangChain4j框架接入DeepSeek：

```java
@Service
@Slf4j
public class LangChain4jHighlightAnalyzer implements HighlightAnalyzerService {

    @Value("${langchain4j.deepseek.api-key:}")
    private String apiKey;

    @Value("${langchain4j.deepseek.model:deepseek-chat}")
    private String modelName;

    private ChatLanguageModel chatModel;

    @PostConstruct
    public void init() {
        if (StringUtils.isBlank(apiKey)) {
            log.warn("DeepSeek API Key not configured, highlight analyzer will not work");
            return;
        }
        this.chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl("https://api.deepseek.com/v1")
                .build();
    }

    @Override
    public HighlightAnalysisResult analyze(List<AsrSegment> asrSegments,
                                           List<SimpleDanmaku> danmakus,
                                           int segmentStartTime,
                                           int segmentEndTime) {
        // 构建Prompt
        String prompt = buildPrompt(asrSegments, danmakus, segmentStartTime, segmentEndTime);

        // 调用模型
        String response = chatModel.generate(prompt);

        // 解析JSON响应
        return parseResponse(response);
    }

    private String buildPrompt(...) {
        // Prompt设计...
    }
}
```

**Prompt设计**：
```
你是一个专业的电竞/娱乐直播剪辑师。以下是一段"疑似高光"直播片段的上下文数据。
请根据主播语音和弹幕的互动关系，判断这是否是一段完整的精彩内容。

【主播语音】
{ASR文本列表}

【观众弹幕】
{弹幕列表（已折叠重复内容）}

【任务指令】
请严格输出 JSON 格式：
{
  "is_highlight": true/false,
  "score": 8,
  "reason": "简述判定理由",
  "exact_clip_start": "01:20:05",
  "exact_clip_end": "01:20:40",
  "suggested_title": "主播自信满脸，闪现迁坟引爆弹幕"
}
```

**依赖**: `sh-engine/pom.xml`
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
    <version>0.35.0</version>
</dependency>
```

### 5. 核心插件实现

**文件**: `sh-engine/src/main/java/com/sh/engine/processor/plugin/DanmakuAIHighlightPlugin.java`

```java
@Component
@Slf4j
public class DanmakuAIHighlightPlugin implements VideoProcessPlugin {
    @Resource
    private DanmakuAnalysisService danmakuAnalysisService;
    @Resource
    private AsrService asrService;
    @Resource
    private HighlightAnalyzerService highlightAnalyzerService;
    @Resource
    private VideoMergeService videoMergeService;

    private static final int MIN_DANMAKU_COUNT = 500;     // 最小弹幕数量阈值
    private static final int MIN_SCORE = 7;                // DeepSeek评分阈值
    private static final String HIGHLIGHT_VIDEO = "ai-highlight.mp4";

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.DAN_MU_HL_VOD_CUT.getType();
    }

    @Override
    public boolean process(String recordPath) {
        // 1. 读取弹幕文件
        File danmuFile = new File(recordPath, RecordConstant.DAMAKU_TXT_ALL_FILE);
        if (!danmuFile.exists()) {
            log.warn("Danmaku file not found: {}", danmuFile.getAbsolutePath());
            return true;
        }

        List<SimpleDanmaku> danmakus = readDanmakuFromFile(danmuFile);
        if (danmakus.size() < MIN_DANMAKU_COUNT) {
            log.info("Danmaku count {} is less than threshold {}, skip processing",
                danmakus.size(), MIN_DANMAKU_COUNT);
            return true;
        }

        // 2. 获取视频文件列表（按P01, P02排序）
        List<File> videoFiles = getVideoFiles(recordPath);
        if (videoFiles.isEmpty()) {
            log.warn("No video files found in: {}", recordPath);
            return true;
        }

        // 3. 阶段一：弹幕波峰检测
        List<TimeBucket> peakBuckets = danmakuAnalysisService.analyzeDanmakuPeak(recordPath, danmakus);
        if (peakBuckets.isEmpty()) {
            log.info("No danmaku peaks detected in: {}", recordPath);
            return true;
        }
        log.info("Detected {} potential highlight segments", peakBuckets.size());

        // 4. 阶段二+三：ASR转写 + DeepSeek分析
        List<HighlightSegment> confirmedHighlights = new ArrayList<>();
        for (TimeBucket bucket : peakBuckets) {
            // 检查片段是否跨越视频边界，跨边界则跳过
            VideoLocation startLocation = findVideoAndOffset(videoFiles, bucket.getStartTime());
            VideoLocation endLocation = findVideoAndOffset(videoFiles, bucket.getEndTime());

            // 跨视频边界的片段直接抛弃
            if (startLocation == null || endLocation == null ||
                !startLocation.getVideoFile().equals(endLocation.getVideoFile())) {
                log.debug("Skipping cross-video segment: {}s - {}s",
                    bucket.getStartTime(), bucket.getEndTime());
                continue;
            }

            // ASR转写（如果ASR服务可用）
            List<AsrSegment> asrSegments = asrService != null
                ? asrService.transcribeSegment(startLocation.getVideoFile(),
                    startLocation.getOffset(),
                    endLocation.getOffset())
                : Collections.emptyList();

            // 获取该时间段内的弹幕
            List<SimpleDanmaku> segmentDanmakus = filterDanmakusByTime(
                danmakus, bucket.getStartTime(), bucket.getEndTime());

            // AI高光分析
            HighlightAnalysisResult result = highlightAnalyzerService.analyze(
                asrSegments, segmentDanmakus, bucket.getStartTime(), bucket.getEndTime());

            if (result.isHighlight() && result.getScore() >= MIN_SCORE) {
                HighlightSegment segment = new HighlightSegment();
                segment.setStartTime(bucket.getStartTime());
                segment.setEndTime(bucket.getEndTime());
                segment.setEmotionScore(bucket.getEmotionScore());
                segment.setVideoFile(startLocation.getVideoFile());
                segment.setVideoOffset(startLocation.getOffset());
                segment.setAsrText(formatAsrText(asrSegments));
                segment.setHighlight(true);
                segment.setScore(result.getScore());
                segment.setReason(result.getReason());
                segment.setSuggestedTitle(result.getSuggestedTitle());
                segment.setExactStartTime(result.getExactClipStart());
                segment.setExactEndTime(result.getExactClipEnd());
                confirmedHighlights.add(segment);
            }
        }

        // 5. 阶段四：视频剪辑输出（只生成一个高光视频）
        if (confirmedHighlights.isEmpty()) {
            log.info("No confirmed highlights after AI analysis");
            return true;
        }

        // 按评分排序，取评分最高的单个片段
        confirmedHighlights.sort((a, b) -> b.getScore() - a.getScore());
        HighlightSegment bestHighlight = confirmedHighlights.get(0);

        log.info("Best highlight: score={}, title={}, time={}-{}",
            bestHighlight.getScore(), bestHighlight.getSuggestedTitle(),
            bestHighlight.getExactStartTime(), bestHighlight.getExactEndTime());

        // 使用现有的 VideoMergeService.mergeWithCover 方法生成高光视频
        File outputFile = new File(recordPath, HIGHLIGHT_VIDEO);

        // 计算在视频文件中的实际时间（考虑DeepSeek返回的精确时间）
        double clipStart = parseTimeToSeconds(bestHighlight.getExactStartTime());
        double clipEnd = parseTimeToSeconds(bestHighlight.getExactEndTime());

        // 如果DeepSeek没有返回精确时间，使用原始波峰时间
        if (clipStart < 0 || clipEnd < 0 || clipEnd <= clipStart) {
            clipStart = bestHighlight.getVideoOffset();
            clipEnd = bestHighlight.getVideoOffset() +
                (bestHighlight.getEndTime() - bestHighlight.getStartTime());
        }

        VideoInterval interval = new VideoInterval(
            bestHighlight.getVideoFile(), clipStart, clipEnd);

        // 调用现有的 mergeWithCover，传入单个 interval
        String title = bestHighlight.getSuggestedTitle() + "\n" +
            StreamerInfoHolder.getCurStreamerName() + "直播精彩片段";
        boolean success = videoMergeService.mergeWithCover(
            Collections.singletonList(interval), outputFile, title);

        if (success) {
            log.info("Generated highlight video: {}", outputFile.getAbsolutePath());
        }

        return true;
    }

    @Override
    public int getMaxProcessParallel() {
        return 1;  // AI分析成本较高，限制并发
    }
}
```

### 6. 配置类扩展

**文件**: `sh-config/src/main/java/com/sh/config/model/config/StreamerConfig.java`

无需添加AI相关配置，所有配置通过`application.properties`环境变量管理。

代码中硬编码的常量：
- `MIN_DANMAKU_COUNT = 500`（最小弹幕数量阈值）
- `MIN_SCORE = 7`（AI高光片段最小评分阈值）
- `HIGHLIGHT_VIDEO = "ai-highlight.mp4"`（输出文件名）

### 7. 外部依赖配置

**文件**: `sh-config/src/main/resources/application.properties`

```properties
# LangChain4j DeepSeek配置
langchain4j.deepseek.api-key=${DEEPSEEK_API_KEY:}
langchain4j.deepseek.model=${DEEPSEEK_MODEL:deepseek-chat}

# ASR配置
asr.provider=${ASR_PROVIDER:none}
asr.aliyun.api-key=${ASR_ALIYUN_API_KEY:}
asr.aliyun.model=${ASR_ALIYUN_MODEL:fun-asr}

# OSS配置（用于上传音频文件供ASR识别）
oss.endpoint=${OSS_ENDPOINT:}
oss.bucket=${OSS_BUCKET:}
oss.access-key-id=${OSS_ACCESS_KEY_ID:}
oss.access-key-secret=${OSS_ACCESS_KEY_SECRET:}
# OSS文件过期时间（小时），ASR要求文件在识别期间可访问
oss.temp-url-expiration=${OSS_TEMP_URL_EXPIRATION:24}
```

### 8. OSS上传服务

**文件**: `sh-engine/src/main/java/com/sh/engine/service/OssUploadService.java`

```java
public interface OssUploadService {
    /**
     * 上传本地文件到OSS并获取公网可访问的URL
     * @param localFile 本地文件
     * @param expireHours URL过期时间（小时）
     * @return 公网可访问的URL
     */
    String uploadAndGetUrl(File localFile, int expireHours);
}
```

**文件**: `sh-engine/src/main/java/com/sh/engine/service/impl/AliyunOssUploadServiceImpl.java`

阿里云OSS上传实现：

```java
@Service
@Slf4j
public class AliyunOssUploadServiceImpl implements OssUploadService {

    @Value("${oss.endpoint:}")
    private String endpoint;

    @Value("${oss.bucket:}")
    private String bucketName;

    @Value("${oss.access-key-id:}")
    private String accessKeyId;

    @Value("${oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${oss.temp-url-expiration:24}")
    private int defaultExpirationHours;

    private OSS ossClient;

    @PostConstruct
    public void init() {
        if (StringUtils.isAnyBlank(endpoint, bucketName, accessKeyId, accessKeySecret)) {
            log.warn("OSS config not complete, upload service will not work");
            return;
        }
        ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }

    @Override
    public String uploadAndGetUrl(File localFile, int expireHours) {
        // 生成唯一的OSS对象键
        String objectKey = "asr-temp/" + UUID.randomUUID() + "/" + localFile.getName();

        // 上传文件
        ossClient.putObject(bucketName, objectKey, localFile);
        log.info("File uploaded to OSS: {}", objectKey);

        // 生成带签名的临时URL
        Date expiration = new Date(System.currentTimeMillis() + expireHours * 3600 * 1000);
        URL url = ossClient.generatePresignedUrl(bucketName, objectKey, expiration);
        log.info("Generated OSS temp URL, expires in {} hours", expireHours);

        return url.toString();
    }
}
```

**依赖**: `sh-engine/pom.xml`

```xml
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.17.4</version>
</dependency>
```

**ASR服务中的使用**: 在`AliyunAsrServiceImpl`中注入`OssUploadService`：

```java
@Resource
private OssUploadService ossUploadService;

@Override
public List<AsrSegment> transcribeSegment(File videoFile, int startSeconds, int endSeconds) {
    // 1. 提取音频到临时文件
    File tempAudio = extractAudio(videoFile, startSeconds, endSeconds);

    // 2. 上传OSS获取公网URL
    String audioUrl = ossUploadService.uploadAndGetUrl(tempAudio, 24);

    // 3. 调用阿里云ASR
    // ... 后续流程
}
```

**注意**：
1. 阿里云ASR要求输入源为公网可访问的URL，不支持本地文件直传
2. SDK方式不支持以`oss://`为前缀的OSS临时URL，必须使用HTTP/HTTPS协议的签名URL
3. 音频文件使用完后可考虑从OSS删除以节省存储成本

---

## 关键文件路径汇总

| 类型 | 文件路径 |
|------|----------|
| 弹幕数据模型 | `sh-engine/src/main/java/com/sh/engine/processor/recorder/danmu/SimpleDanmaku.java` |
| 插件接口 | `sh-engine/src/main/java/com/sh/engine/processor/plugin/VideoProcessPlugin.java` |
| 插件枚举 | `sh-engine/src/main/java/com/sh/engine/constant/ProcessPluginEnum.java` |
| 视频合并服务 | `sh-engine/src/main/java/com/sh/engine/service/VideoMergeService.java` |
| FFmpeg命令基类 | `sh-engine/src/main/java/com/sh/engine/model/ffmpeg/AbstractCmd.java` |
| 录制常量 | `sh-engine/src/main/java/com/sh/engine/constant/RecordConstant.java` |
| 主播配置 | `sh-config/src/main/java/com/sh/config/model/config/StreamerConfig.java` |
| 视频文件工具 | `sh-config/src/main/java/com/sh/config/utils/VideoFileUtil.java` |

---

## 实施步骤

### Step 1: 数据模型（1个文件）
- 创建 `HighlightSegment.java` 和 `DeepSeekResponse.java`

### Step 2: 分析服务（2个文件）
- 创建 `DanmakuAnalysisService.java` 及实现类
- 实现波峰检测算法（Z-Score）

### Step 3: AI高光分析服务（2个文件）
- 创建 `HighlightAnalyzerService.java` 接口
- 创建 `LangChain4jHighlightAnalyzer.java` 实现类（使用LangChain4j框架）

### Step 4: ASR服务（1个文件，可选）
- 创建 `AsrService.java` 接口
- 可先用空实现，后续接入具体ASR provider

### Step 5: 核心插件（1个文件）
- 创建 `DanmakuAIHighlightPlugin.java`
- 实现4阶段处理流程
- 使用现有的 `VideoMergeService.mergeWithCover()` 方法生成单个高光视频
- 跳过跨视频边界的片段

### Step 6: 配置扩展
- 扩展 `StreamerConfig` 添加AI相关配置
- 更新 `application.properties`

---

## 验证方案

### 本地测试步骤

1. **准备测试数据**：
   ```bash
   # 确保有录播目录结构
   /home/admin/stream/download/主播名/2026-02-15-17-04-12/
   ├── P01.mp4
   ├── P02.mp4
   └── damaku.txt
   ```

2. **配置插件**：
   - 在 `streamer` 表的 `video_plugins` 字段添加 `DAN_MU_HL_VOD_CUT`
   - 配置 `deep_seek_api_key`

3. **运行测试**：
   ```bash
   mvn -pl sh-start spring-boot:run
   ```

4. **验证输出**：
   - 检查日志中的波峰检测数量
   - 检查生成的 `ai-highlight.mp4` 文件（单个高光视频）
   - 检查 `fileStatus.json` 中的处理状态

### 关键日志检查点

```
[INFO] Detected X potential highlight segments       // 阶段一完成
[INFO] Skipping cross-video segment: 120s - 180s      // 跳过跨视频边界的片段
[INFO] Calling highlight analyzer for segment ...     // 阶段三开始
[INFO] Analysis result: is_highlight=true, score=8    // AI判定结果
[INFO] Best highlight: score=8, title=xxx             // 选择最佳片段
[INFO] Generated highlight video: ai-highlight.mp4    // 阶段四完成（单个视频）
```

---

## 成本优化建议

1. **ASR降本**：
   - 仅对波峰片段进行ASR（而非全视频）
   - 可配置ASR采样率（如只分析前5个波峰）
   - 支持关闭ASR仅用弹幕分析

2. **DeepSeek降本**：
   - 对弹幕进行折叠去重（如"哈哈哈x40"）
   - 限制单次请求的弹幕数量（如前50条）
   - 缓存相似片段的分析结果

3. **并发控制**：
   - 设置 `getMaxProcessParallel() = 1`
   - 避免多个录播同时调用AI API

---

## 风险与应对

| 风险 | 应对方案 |
|------|----------|
| AI分析服务调用失败 | 添加重试机制和降级策略（仅用弹幕密度输出） |
| ASR服务不可用 | 接口设计为可选，未配置时跳过ASR |
| 弹幕数量过少 | 设置MIN_DANMAKU_COUNT阈值，不足时跳过 |
| 剪辑时间不准确 | 使用FFmpeg精确切割，支持DeepSeek返回的时间微调 |
| 高光片段跨越视频边界 | 直接抛弃该片段，只处理完全在单个视频内的片段 |
| API成本过高 | 提供配置开关，支持仅使用阶段一的纯数学检测 |

---

## 扩展性考虑

1. **多模型支持**：HighlightAnalyzerService设计为接口，LangChain4j架构天然支持多模型（OpenAI、Claude、本地模型等）
2. **多ASR支持**：AsrService接口支持阿里云、讯飞、Whisper等
3. **多平台适配**：弹幕分析适用于任何平台的直播录制
4. **可配置策略**：情绪分算法、波峰阈值等都可配置化

---

## 阿里云ASR接入说明

基于官方文档 [Fun-ASR录音文件识别Java SDK](https://help.aliyun.com/zh/model-studio/fun-asr-recorded-speech-recognition-java-sdk)，关键实现细节：

### SDK信息
- **GroupId**: `com.alibaba`
- **ArtifactId**: `dashscope-sdk-java`
- **Version**: `2.18.0`（或更新版本）

### 核心类（`com.alibaba.dashscope.audio.asr.transcription` 包）
- `Transcription` - 核心类，提供asyncCall/wait/fetch方法
- `TranscriptionParam` - 请求参数构建器（链式调用）
- `TranscriptionResult` - 任务执行结果
- `TranscriptionQueryParam` - 查询参数（通过FromTranscriptionParam静态方法创建）
- `TranscriptionTaskResult` - 子任务执行结果（对应单个音频文件）
- `TaskStatus` - 任务状态枚举（PENDING, RUNNING, SUCCEEDED, FAILED）

### 调用流程
1. 设置API URL: `Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1"`
2. 构建`TranscriptionParam`：
   - `model`: 模型名称（如`fun-asr`）
   - `fileUrls`: 公网可访问的音频URL列表
   - `parameter("language_hints", new String[]{"zh", "en"})`: 语言提示
   - `apiKey`: API Key（如已配置环境变量可不设置）
3. 调用`transcription.asyncCall(param)`提交异步任务，获取`taskId`
4. 调用`transcription.wait(queryParam)`阻塞等待任务完成
5. 从`TranscriptionTaskResult`中获取`transcriptionUrl`
6. 下载JSON并解析`transcripts[].sentences[].{begin_time, end_time, text, words[]}`

### 重要约束（根据官方文档）
1. **文件来源**: 不支持本地文件直传，必须提供公网可访问的URL（HTTP/HTTPS）
2. **OSS临时URL**: 使用SDK时不支持以`oss://`为前缀的OSS临时URL
3. **文件限制**: 单个文件不超过2GB，时长不超过12小时
4. **结果有效期**: `transcriptionUrl`和查询结果有效期为24小时
5. **时间戳单位**: 毫秒（ms），需转换为秒

### 录播场景处理流程
1. **音频提取**: 使用FFmpeg从视频提取音频片段（WAV, 16kHz, 单声道）
2. **文件上传**: 上传音频文件到OSS获取公网URL（需实现OSS上传逻辑）
3. **提交识别**: 调用阿里云ASR异步接口提交任务
4. **等待结果**: 阻塞等待任务完成（通常数分钟内）
5. **解析结果**: 下载JSON结果并转换为`List<AsrSegment>`

### 可选参数（根据文档）
- `vocabularyId`: 热词ID
- `channelId`: 指定音轨索引（多音轨音频）
- `diarizationEnabled`: 启用说话人分离（默认为false）
- `speakerCount`: 说话人数量参考值（2-100）
- `specialWordFilter`: 敏感词过滤配置（JSON字符串）
