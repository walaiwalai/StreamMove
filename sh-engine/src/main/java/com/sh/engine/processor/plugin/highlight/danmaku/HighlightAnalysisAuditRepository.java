package com.sh.engine.processor.plugin.highlight.danmaku;

import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.danmaku.VisualEvidenceBatch;
import com.sh.engine.model.llm.LlmImageInput;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 将每次模型请求、响应和视觉帧索引持久化为可复盘的 JSONL。 */
@Component
public class HighlightAnalysisAuditRepository {
    private static final String AUDIT_FILE_NAME = "highlight-analysis-audit.jsonl";

    /**
     * 审计是高光链路的必需产物，写入失败时终止当前任务而不是静默丢证据。
     */
    public synchronized void append(
            File recordDirectory,
            String stage,
            String cacheKey,
            String prompt,
            String rawResponse,
            Object parsedResponse,
            VisualEvidenceBatch visualEvidence) {
        JSONObject audit = createAudit(
                stage, cacheKey, prompt, rawResponse, parsedResponse);
        if (visualEvidence != null) {
            audit.put("inputFrames", visualEvidence.toAuditMetadata());
        }
        write(recordDirectory, audit);
    }

    /** 记录非视频帧类图片请求的名称、哈希、提示词和原始响应。 */
    public synchronized void appendImages(
            File recordDirectory,
            String stage,
            String cacheKey,
            String prompt,
            String rawResponse,
            Object parsedResponse,
            List<LlmImageInput> images) {
        JSONObject audit = createAudit(
                stage, cacheKey, prompt, rawResponse, parsedResponse);
        List<JSONObject> metadata = new ArrayList<>();
        if (images != null) {
            for (int index = 0; index < images.size(); index++) {
                LlmImageInput image = images.get(index);
                JSONObject item = new JSONObject(true);
                item.put("imageIndex", index + 1);
                item.put("name", image.getName());
                item.put("sha256", DigestUtils.sha256Hex(image.getJpegData()));
                item.put("bytes", image.sizeInBytes());
                metadata.add(item);
            }
        }
        audit.put("inputImages", metadata);
        write(recordDirectory, audit);
    }

    private JSONObject createAudit(
            String stage,
            String cacheKey,
            String prompt,
            String rawResponse,
            Object parsedResponse) {
        JSONObject audit = new JSONObject(true);
        audit.put("timestamp", Instant.now().toString());
        audit.put("stage", stage);
        audit.put("cacheKey", cacheKey);
        audit.put("prompt", prompt);
        audit.put("rawResponse", rawResponse);
        audit.put("parsedResponse", parsedResponse);
        return audit;
    }

    private void write(File recordDirectory, JSONObject audit) {
        File auditFile = new File(recordDirectory, AUDIT_FILE_NAME);
        String line = audit.toJSONString() + System.lineSeparator();
        try {
            Files.write(auditFile.toPath(), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot write highlight analysis audit: " + auditFile, e);
        }
    }
}
