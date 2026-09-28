package com.sh.engine.processor.plugin.highlight.talk;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.model.asr.AsrSegment;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.service.AsrService;
import com.sh.engine.service.LlmService;
import com.sh.engine.service.VideoMergeService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对谈话类直播执行全场 ASR、受众价值筛选和独立视频片段导出。
 */
@Component
@Slf4j
public class TalkHighlightProcessor {
    private static final int ANALYSIS_WINDOW_SECONDS = 12 * 60;
    private static final int ANALYSIS_WINDOW_STEP_SECONDS = 11 * 60;
    private static final int MIN_CLIP_SECONDS = 45;
    private static final int MAX_CLIP_SECONDS = 5 * 60;
    private static final int CLIP_CONTEXT_SECONDS = 8;
    private static final int MIN_CANDIDATE_SCORE = 60;
    private static final int MAX_CANDIDATES_PER_WINDOW = 2;

    @Resource
    private AsrService asrService;

    @Resource
    private LlmService llmService;

    @Resource
    private VideoMergeService videoMergeService;

    /**
     * 执行一次离线谈话高光处理，并返回本地工作目录及最终片段信息。
     *
     * @param inputDirectory 同一场直播的源视频目录
     * @param streamerName   主播名，用于提示词和 ASR 对象路径
     * @param maxClips       最多输出的片段数量
     */
    public TalkHighlightRunResult process(File inputDirectory, String streamerName, int maxClips) {
        if (StringUtils.isBlank(streamerName) || maxClips <= 0) {
            throw new StreamerRecordException(
                    ErrorEnum.INVALID_PARAM, "streamer name and positive maxClips are required");
        }

        TalkVideoTimeline timeline = TalkVideoTimeline.open(inputDirectory);
        TalkTranscriptStore store = new TalkTranscriptStore(inputDirectory);
        List<TalkTranscriptStore.SourceTranscript> sourceTranscripts = transcribeSources(timeline, store);
        String transcriptFingerprint = TalkTranscriptStore.combinedFingerprint(sourceTranscripts);
        List<TalkTranscriptStore.TalkTranscriptSegment> transcript = mergeTranscripts(sourceTranscripts);
        store.saveCombinedTranscript(transcript, transcriptFingerprint);

        List<TalkHighlightCandidate> candidates = analyzeTranscript(
                transcript, transcriptFingerprint, streamerName, timeline, store);
        List<TalkHighlightCandidate> selected = selectTopCandidates(candidates, maxClips);
        if (selected.isEmpty()) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "no qualified talk highlight was found in " + inputDirectory.getAbsolutePath());
        }

        store.writeJson(new File(store.getWorkspaceDirectory(), "selection.json"), selected);
        List<RenderedClip> renderedClips = renderClips(selected, timeline, store);
        TalkHighlightRunResult result = new TalkHighlightRunResult();
        result.setInputDirectory(inputDirectory.getAbsolutePath());
        result.setWorkspaceDirectory(store.getWorkspaceDirectory().getAbsolutePath());
        result.setTranscriptFile(new File(store.getWorkspaceDirectory(), "transcript.json").getAbsolutePath());
        result.setSubtitleFile(new File(store.getWorkspaceDirectory(), "transcript.srt").getAbsolutePath());
        result.setClips(renderedClips);
        store.writeJson(new File(store.getWorkspaceDirectory(), "manifest.json"), result);
        return result;
    }

    /**
     * 逐源文件读取缓存或调用 ASR；远程调用成功后立即写本地缓存。
     */
    private List<TalkTranscriptStore.SourceTranscript> transcribeSources(
            TalkVideoTimeline timeline, TalkTranscriptStore store) {
        List<TalkTranscriptStore.SourceTranscript> transcripts = new ArrayList<>();
        List<TalkVideoTimeline.SourceVideo> sourceVideos = timeline.getSourceVideos();
        for (int index = 0; index < sourceVideos.size(); index++) {
            TalkVideoTimeline.SourceVideo sourceVideo = sourceVideos.get(index);
            Optional<TalkTranscriptStore.SourceTranscript> cached =
                    store.loadSourceTranscript(sourceVideo, index + 1);
            if (cached.isPresent()) {
                log.info("Using local ASR cache: {}", sourceVideo.getVideoFile().getAbsolutePath());
                transcripts.add(cached.get());
                continue;
            }

            int endSecond = (int) Math.ceil(sourceVideo.getDurationSeconds());
            log.info("Calling ASR for source {}/{}: {}", index + 1, sourceVideos.size(),
                    sourceVideo.getVideoFile().getAbsolutePath());
            List<AsrSegment> segments = asrService.transcribeSegment(
                    sourceVideo.getVideoFile(), 0, endSecond);
            if (CollectionUtils.isEmpty(segments)) {
                throw new StreamerRecordException(
                        ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                        "ASR returned no transcript for: " + sourceVideo.getVideoFile().getAbsolutePath());
            }
            segments.sort(Comparator.comparingInt(AsrSegment::getStartTime));
            TalkTranscriptStore.SourceTranscript saved =
                    store.saveSourceTranscript(sourceVideo, index + 1, segments);
            transcripts.add(saved);
            log.info("Saved local ASR cache, source: {}, segments: {}",
                    sourceVideo.getVideoFile().getName(), segments.size());
        }
        return transcripts;
    }

    /**
     * 合并各源文件的转写，补充全场时间及稳定证据编号。
     */
    private List<TalkTranscriptStore.TalkTranscriptSegment> mergeTranscripts(
            List<TalkTranscriptStore.SourceTranscript> sourceTranscripts) {
        List<TalkTranscriptStore.TalkTranscriptSegment> combined = new ArrayList<>();
        int evidenceIndex = 1;
        for (TalkTranscriptStore.SourceTranscript sourceTranscript : sourceTranscripts) {
            TalkVideoTimeline.SourceVideo sourceVideo = sourceTranscript.getSourceVideo();
            for (AsrSegment segment : sourceTranscript.getSegments()) {
                if (segment == null || StringUtils.isBlank(segment.getText())) {
                    continue;
                }
                combined.add(new TalkTranscriptStore.TalkTranscriptSegment(
                        String.format("S%06d", evidenceIndex++),
                        sourceVideo.getVideoFile().getName(),
                        segment.getStartTime(),
                        segment.getEndTime(),
                        sourceVideo.toGlobalSecond(segment.getStartTime()),
                        sourceVideo.toGlobalSecond(segment.getEndTime()),
                        segment.getText().trim()));
            }
        }
        return combined;
    }

    /**
     * 以有界窗口分析转写；每个窗口的结果单独缓存，失败重跑不会重复调用已完成窗口。
     */
    private List<TalkHighlightCandidate> analyzeTranscript(
            List<TalkTranscriptStore.TalkTranscriptSegment> transcript,
            String transcriptFingerprint,
            String streamerName,
            TalkVideoTimeline timeline,
            TalkTranscriptStore store) {
        Map<String, TalkTranscriptStore.TalkTranscriptSegment> evidenceIndex = indexEvidence(transcript);
        List<TalkHighlightCandidate> candidates = new ArrayList<>();
        int windowIndex = 1;
        for (int windowStart = 0; windowStart < timeline.getTotalDurationSeconds();
             windowStart += ANALYSIS_WINDOW_STEP_SECONDS) {
            int windowEnd = Math.min(
                    timeline.getTotalDurationSeconds(), windowStart + ANALYSIS_WINDOW_SECONDS);
            List<TalkTranscriptStore.TalkTranscriptSegment> windowSegments =
                    segmentsInWindow(transcript, windowStart, windowEnd);
            if (windowSegments.isEmpty()) {
                windowIndex++;
                continue;
            }

            File cacheFile = store.analysisCacheFile(transcriptFingerprint, windowIndex);
            Optional<CandidateBatchResponse> cachedResponse =
                    store.readJson(cacheFile, CandidateBatchResponse.class);
            CandidateBatchResponse response = cachedResponse.isPresent()
                    ? cachedResponse.get()
                    : analyzeWindow(windowSegments, streamerName, windowStart, windowEnd, cacheFile, store);
            candidates.addAll(validateCandidates(
                    response, evidenceIndex, timeline.getTotalDurationSeconds(), windowIndex));
            windowIndex++;
        }
        return candidates;
    }

    private CandidateBatchResponse analyzeWindow(
            List<TalkTranscriptStore.TalkTranscriptSegment> segments,
            String streamerName,
            int windowStart,
            int windowEnd,
            File cacheFile,
            TalkTranscriptStore store) {
        String prompt = buildAnalysisPrompt(segments, streamerName, windowStart, windowEnd);
        CandidateBatchResponse response = llmService.chat(prompt, CandidateBatchResponse.class);
        if (response == null) {
            response = new CandidateBatchResponse();
            response.setCandidates(Collections.emptyList());
        }
        store.writeJson(cacheFile, response);
        return response;
    }

    /**
     * 构造通用谈话选片提示词；模型只能通过证据编号回指边界，时间由本地代码解析。
     */
    private String buildAnalysisPrompt(List<TalkTranscriptStore.TalkTranscriptSegment> segments,
                                       String streamerName,
                                       int windowStart,
                                       int windowEnd) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是谈话类直播的资深内容编辑。主播是「").append(streamerName).append("」。\n")
                .append("请从以下转写中最多选择").append(MAX_CANDIDATES_PER_WINDOW)
                .append("个普通观众愿意单独点开观看的完整片段。\n")
                .append("优先：明确观点与论证、反常识信息、实用经验、争议判断、真诚故事或自然幽默。\n")
                .append("排除：寒暄、读弹幕、重复表述、无上下文碎片、纯推广、只有情绪没有内容。\n")
                .append("片段应能独立理解，建议45秒到5分钟。不要编造转写之外的信息。\n")
                .append("只能用下列证据编号指定起止边界，不要自行填写时间戳。\n")
                .append("窗口：").append(formatTime(windowStart)).append(" - ")
                .append(formatTime(windowEnd)).append("\n\n【转写证据】\n");
        for (TalkTranscriptStore.TalkTranscriptSegment segment : segments) {
            prompt.append(segment.getEvidenceId()).append(" [")
                    .append(formatTime(segment.getGlobalStartSeconds())).append('-')
                    .append(formatTime(segment.getGlobalEndSeconds())).append("] ")
                    .append(segment.getText()).append('\n');
        }
        prompt.append("\n严格输出JSON，不要Markdown：\n")
                .append("{\"candidates\":[{")
                .append("\"title\":\"准确、有吸引力但不夸大的标题\",")
                .append("\"score\":0,")
                .append("\"reason\":\"观众为什么愿意看\",")
                .append("\"startEvidenceId\":\"S000001\",")
                .append("\"endEvidenceId\":\"S000010\",")
                .append("\"evidenceIds\":[\"S000001\",\"S000010\"]}]}\n")
                .append("score为0到100；没有合格内容时返回{\"candidates\":[]}。");
        return prompt.toString();
    }

    /**
     * 校验模型证据引用并由可信时间线生成真实片段边界。
     */
    private List<TalkHighlightCandidate> validateCandidates(
            CandidateBatchResponse response,
            Map<String, TalkTranscriptStore.TalkTranscriptSegment> evidenceIndex,
            int totalDurationSeconds,
            int windowIndex) {
        if (response == null || CollectionUtils.isEmpty(response.getCandidates())) {
            return Collections.emptyList();
        }
        List<TalkHighlightCandidate> validated = new ArrayList<>();
        for (CandidateResponse candidate : response.getCandidates()) {
            TalkTranscriptStore.TalkTranscriptSegment start =
                    evidenceIndex.get(candidate.getStartEvidenceId());
            TalkTranscriptStore.TalkTranscriptSegment end =
                    evidenceIndex.get(candidate.getEndEvidenceId());
            if (start == null || end == null || start.getGlobalStartSeconds() > end.getGlobalEndSeconds()
                    || candidate.getScore() < MIN_CANDIDATE_SCORE || StringUtils.isBlank(candidate.getTitle())) {
                continue;
            }
            int clipStart = Math.max(0, start.getGlobalStartSeconds() - CLIP_CONTEXT_SECONDS);
            int clipEnd = Math.min(totalDurationSeconds,
                    end.getGlobalEndSeconds() + CLIP_CONTEXT_SECONDS);
            int[] normalizedRange = normalizeDuration(clipStart, clipEnd, totalDurationSeconds);
            TalkHighlightCandidate accepted = new TalkHighlightCandidate();
            accepted.setTitle(candidate.getTitle().trim());
            accepted.setScore(Math.min(100, candidate.getScore()));
            accepted.setReason(StringUtils.defaultString(candidate.getReason()).trim());
            accepted.setStartSeconds(normalizedRange[0]);
            accepted.setEndSeconds(normalizedRange[1]);
            accepted.setStartEvidenceId(start.getEvidenceId());
            accepted.setEndEvidenceId(end.getEvidenceId());
            accepted.setWindowIndex(windowIndex);
            validated.add(accepted);
        }
        return validated;
    }

    private int[] normalizeDuration(int startSeconds, int endSeconds, int totalDurationSeconds) {
        int duration = endSeconds - startSeconds;
        if (duration < MIN_CLIP_SECONDS) {
            int missing = MIN_CLIP_SECONDS - duration;
            startSeconds = Math.max(0, startSeconds - missing / 2);
            endSeconds = Math.min(totalDurationSeconds, startSeconds + MIN_CLIP_SECONDS);
            startSeconds = Math.max(0, endSeconds - MIN_CLIP_SECONDS);
        } else if (duration > MAX_CLIP_SECONDS) {
            endSeconds = startSeconds + MAX_CLIP_SECONDS;
        }
        return new int[]{startSeconds, endSeconds};
    }

    /**
     * 按分数选择全场候选，并去除高度重叠的重复片段。
     */
    private List<TalkHighlightCandidate> selectTopCandidates(
            List<TalkHighlightCandidate> candidates, int maxClips) {
        candidates.sort(Comparator.comparingInt(TalkHighlightCandidate::getScore).reversed()
                .thenComparingInt(TalkHighlightCandidate::getStartSeconds));
        List<TalkHighlightCandidate> selected = new ArrayList<>();
        for (TalkHighlightCandidate candidate : candidates) {
            if (overlapsSelected(candidate, selected)) {
                continue;
            }
            candidate.setCandidateId(String.format("C%03d", selected.size() + 1));
            selected.add(candidate);
            if (selected.size() >= maxClips) {
                break;
            }
        }
        return selected;
    }

    private boolean overlapsSelected(TalkHighlightCandidate candidate,
                                     List<TalkHighlightCandidate> selected) {
        for (TalkHighlightCandidate existing : selected) {
            int overlap = Math.min(candidate.getEndSeconds(), existing.getEndSeconds())
                    - Math.max(candidate.getStartSeconds(), existing.getStartSeconds());
            int shorter = Math.min(
                    candidate.getEndSeconds() - candidate.getStartSeconds(),
                    existing.getEndSeconds() - existing.getStartSeconds());
            if (overlap > 0 && overlap * 2 >= shorter) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为每个最终候选生成独立视频；已有非空文件直接复用。
     */
    private List<RenderedClip> renderClips(List<TalkHighlightCandidate> selected,
                                           TalkVideoTimeline timeline,
                                           TalkTranscriptStore store) {
        List<RenderedClip> clips = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            TalkHighlightCandidate candidate = selected.get(index);
            String fileName = String.format("%02d-%s.mp4", index + 1,
                    sanitizeFileName(candidate.getTitle()));
            File clipFile = new File(store.getClipsDirectory(), fileName);
            if (!clipFile.isFile() || clipFile.length() == 0L) {
                List<VideoInterval> intervals = timeline.toVideoIntervals(
                        candidate.getStartSeconds(), candidate.getEndSeconds());
                boolean merged = videoMergeService.mergeWithCover(
                        intervals, clipFile, formatCoverTitle(candidate.getTitle()));
                if (!merged) {
                    throw new StreamerRecordException(
                            ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                            "failed to render talk highlight: " + clipFile.getAbsolutePath());
                }
            }
            clips.add(new RenderedClip(candidate, clipFile.getAbsolutePath()));
        }
        return clips;
    }

    /**
     * 将长标题按中文封面的可读宽度分行，避免单行文字越过画面边缘。
     */
    private String formatCoverTitle(String title) {
        final int charactersPerLine = 18;
        if (title.length() <= charactersPerLine) {
            return title;
        }
        StringBuilder formatted = new StringBuilder(title.length() + 2);
        for (int offset = 0; offset < title.length(); offset += charactersPerLine) {
            if (offset > 0) {
                formatted.append('\n');
            }
            formatted.append(title, offset, Math.min(title.length(), offset + charactersPerLine));
        }
        return formatted.toString();
    }

    private Map<String, TalkTranscriptStore.TalkTranscriptSegment> indexEvidence(
            List<TalkTranscriptStore.TalkTranscriptSegment> transcript) {
        Map<String, TalkTranscriptStore.TalkTranscriptSegment> indexed = new HashMap<>();
        for (TalkTranscriptStore.TalkTranscriptSegment segment : transcript) {
            indexed.put(segment.getEvidenceId(), segment);
        }
        return indexed;
    }

    private List<TalkTranscriptStore.TalkTranscriptSegment> segmentsInWindow(
            List<TalkTranscriptStore.TalkTranscriptSegment> transcript,
            int windowStart,
            int windowEnd) {
        List<TalkTranscriptStore.TalkTranscriptSegment> selected = new ArrayList<>();
        for (TalkTranscriptStore.TalkTranscriptSegment segment : transcript) {
            if (segment.getGlobalEndSeconds() >= windowStart
                    && segment.getGlobalStartSeconds() <= windowEnd) {
                selected.add(segment);
            }
        }
        return selected;
    }

    private String sanitizeFileName(String title) {
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeTitle.length() > 60) {
            safeTitle = safeTitle.substring(0, 60).trim();
        }
        return StringUtils.defaultIfBlank(safeTitle, "talk-highlight");
    }

    private String formatTime(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        return String.format("%02d:%02d:%02d",
                safeSeconds / 3600, (safeSeconds % 3600) / 60, safeSeconds % 60);
    }

    @Data
    public static class CandidateBatchResponse {
        private List<CandidateResponse> candidates;
    }

    @Data
    public static class CandidateResponse {
        private String title;
        private int score;
        private String reason;
        private String startEvidenceId;
        private String endEvidenceId;
        private List<String> evidenceIds;
    }

    @Data
    public static class TalkHighlightCandidate {
        private String candidateId;
        private String title;
        private int score;
        private String reason;
        private int startSeconds;
        private int endSeconds;
        private String startEvidenceId;
        private String endEvidenceId;
        private int windowIndex;
    }

    @Data
    public static class RenderedClip {
        private final TalkHighlightCandidate candidate;
        private final String videoFile;
    }

    @Data
    public static class TalkHighlightRunResult {
        private String inputDirectory;
        private String workspaceDirectory;
        private String transcriptFile;
        private String subtitleFile;
        private List<RenderedClip> clips;
    }
}
