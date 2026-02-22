package com.sh.engine.service.impl.hlAnalysis;

import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.danmaku.HighlightAnalysisResult;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import com.sh.engine.service.HighlightAnalysisService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of HighlightAnalysisService using LangChain4j framework.
 * <p>
 * Uses OpenAI-compatible API to connect to DeepSeek AI for highlight analysis.
 * This implementation leverages LangChain4j's structured output capabilities.
 *
 * @Author : caiwen
 * @Date: 2026/2/21
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "highlight.analysis.provider", havingValue = "langchain4j")
public class LangChain4jHighlightAnalysisServiceImpl implements HighlightAnalysisService {

    @Value("${langchain4j.open-ai.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.model-name}")
    private String modelName;

    private ChatLanguageModel chatModel;

    @PostConstruct
    public void init() {
        if (StringUtils.isBlank(apiKey)) {
            log.warn("DeepSeek API key is not configured");
            return;
        }

        this.chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();

        log.info("LangChain4j chat model initialized, baseUrl: {}", baseUrl);
    }

    @Override
    public HighlightAnalysisResult analyze(List<AsrSegment> asrSegments, List<SimpleDanmaku> danmakus,
                                           int segmentStartTime, int segmentEndTime) {
        if (chatModel == null) {
            log.error("Chat model is not initialized, cannot perform highlight analysis");
            return createFallbackResponse(segmentStartTime, segmentEndTime);
        }

        try {
            String prompt = buildPrompt(asrSegments, danmakus, segmentStartTime, segmentEndTime);
            String response = chatModel.generate(prompt);
            return parseResponse(response, segmentStartTime, segmentEndTime);
        } catch (Exception e) {
            log.error("Failed to analyze highlight using LangChain4j", e);
            return createFallbackResponse(segmentStartTime, segmentEndTime);
        }
    }

    /**
     * Build the prompt for AI analysis
     */
    private String buildPrompt(List<AsrSegment> asrSegments, List<SimpleDanmaku> danmakus,
                               int segmentStartTime, int segmentEndTime) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一名专业的电竞/娱乐直播剪辑师。请根据以下疑似高光片段的数据进行分析：\n\n");

        // Add ASR text
        prompt.append("【主播语音】\n");
        if (asrSegments == null || asrSegments.isEmpty()) {
            prompt.append("（该片段未检测到语音）\n");
        } else {
            String asrText = asrSegments.stream()
                    .map(seg -> "[" + formatTime(seg.getStartTime()) + "-" + formatTime(seg.getEndTime()) + "] " + seg.getText())
                    .collect(Collectors.joining("\n"));
            prompt.append(asrText).append("\n");
        }

        prompt.append("\n");

        // Add Danmaku
        prompt.append("【观众弹幕】\n");
        if (danmakus == null || danmakus.isEmpty()) {
            prompt.append("（该片段没有弹幕）\n");
        } else {
            String danmakuText = danmakus.stream()
                    .map(d -> String.format("[%.1fs] %s: %s", d.getTime(), d.getUname(), d.getText()))
                    .collect(Collectors.joining("\n"));
            prompt.append(danmakuText).append("\n");
        }

        prompt.append("\n");

        // Add expected output format
        String segmentStartTimeStr = formatTime(segmentStartTime);
        String segmentEndTimeStr = formatTime(segmentEndTime);

        prompt.append("疑似高光片段的时间范围是从 ")
                .append(segmentStartTimeStr).append(" 到 ").append(segmentEndTimeStr).append("。\n\n");

        prompt.append("请严格按照以下JSON格式输出：\n");
        prompt.append("{\n");
        prompt.append("  \"is_highlight\": true/false,\n");
        prompt.append("  \"score\": 8,\n");
        prompt.append("  \"reason\": \"判断理由\",\n");
        prompt.append("  \"exact_clip_start\": \"").append(segmentStartTimeStr).append("\",\n");
        prompt.append("  \"exact_clip_end\": \"").append(segmentEndTimeStr).append("\",\n");
        prompt.append("  \"suggested_title\": \"建议的标题\"\n");
        prompt.append("}\n");

        return prompt.toString();
    }

    /**
     * Format seconds to HH:mm:ss format
     */
    private String formatTime(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    /**
     * Parse AI response into HighlightAnalysisResult
     */
    private HighlightAnalysisResult parseResponse(String response, int segmentStartTime, int segmentEndTime) {
        if (StringUtils.isBlank(response)) {
            log.warn("Empty response from AI");
            return createFallbackResponse(segmentStartTime, segmentEndTime);
        }

        // Extract JSON from content (handle markdown code blocks)
        String jsonContent = extractJsonFromContent(response);

        try {
            // Simple JSON parsing using regex for basic fields
            HighlightAnalysisResult result = new HighlightAnalysisResult();

            // Extract is_highlight
            if (jsonContent.contains("\"is_highlight\": true") || jsonContent.contains("\"is_highlight\":true")) {
                result.setHighlight(true);
            } else {
                result.setHighlight(false);
            }

            // Extract score
            result.setScore(extractIntField(jsonContent, "score", 0));

            // Extract reason
            result.setReason(extractStringField(jsonContent, "reason", ""));

            // Extract exact_clip_start
            String clipStart = extractStringField(jsonContent, "exact_clip_start", null);
            result.setExactClipStart(clipStart != null ? clipStart : formatTime(segmentStartTime));

            // Extract exact_clip_end
            String clipEnd = extractStringField(jsonContent, "exact_clip_end", null);
            result.setExactClipEnd(clipEnd != null ? clipEnd : formatTime(segmentEndTime));

            // Extract suggested_title
            result.setSuggestedTitle(extractStringField(jsonContent, "suggested_title", ""));

            return result;
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", response, e);
            return createFallbackResponse(segmentStartTime, segmentEndTime);
        }
    }

    /**
     * Extract JSON from content that may be wrapped in markdown code blocks
     */
    private String extractJsonFromContent(String content) {
        if (content == null) {
            return "{}";
        }

        content = content.trim();

        // Remove markdown code block markers if present
        if (content.startsWith("```json")) {
            content = content.substring(7);
        } else if (content.startsWith("```")) {
            content = content.substring(3);
        }

        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3);
        }

        return content.trim();
    }

    /**
     * Extract string field from JSON content using simple regex
     */
    private String extractStringField(String json, String fieldName, String defaultValue) {
        String pattern = "\"" + fieldName + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return defaultValue;
    }

    /**
     * Extract int field from JSON content using simple regex
     */
    private int extractIntField(String json, String fieldName, int defaultValue) {
        String pattern = "\"" + fieldName + "\"\\s*:\\s*(\\d+)";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Create a fallback response when API call fails
     */
    private HighlightAnalysisResult createFallbackResponse(int segmentStartTime, int segmentEndTime) {
        HighlightAnalysisResult result = new HighlightAnalysisResult();
        result.setHighlight(false);
        result.setScore(0);
        result.setReason("API call failed, defaulting to non-highlight");
        result.setExactClipStart(formatTime(segmentStartTime));
        result.setExactClipEnd(formatTime(segmentEndTime));
        result.setSuggestedTitle("");
        return result;
    }
}
