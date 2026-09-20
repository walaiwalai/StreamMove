package com.sh.engine.processor.plugin.highlight.danmaku;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.utils.DateUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.danmaku.ConfirmedHighlight;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.model.highlight.core.HighlightMaskPlan;
import com.sh.engine.processor.plugin.highlight.HighlightAdvertisementMaskDetector;
import com.sh.engine.processor.plugin.highlight.HighlightCoverGenerator;
import com.sh.engine.service.VideoMergeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 为已确认事件生成独立审片文件，并只将综合排序最高的单个事件作为正式发布产物。
 */
@Component
@Slf4j
public class DanmakuHighlightArtifactGenerator {
    private static final int MAXIMUM_REVIEW_CLIP_COUNT = 5;
    private static final int MAXIMUM_TITLE_CODE_POINTS = 18;
    private static final int MAXIMUM_COVER_TEXT_CODE_POINTS = 12;
    private static final String REVIEW_DIRECTORY_NAME = "highlight-review";

    @Resource
    private VideoMergeService videoMergeService;
    @Resource
    private HighlightAdvertisementMaskDetector advertisementMaskDetector;
    @Resource
    private HighlightCoverGenerator coverGenerator;

    /**
     * 生成全部必需产物。视频、标题或封面任一失败都会返回失败或抛出明确异常。
     */
    public boolean generate(
            String recordPath,
            File highlightFile,
            List<ConfirmedHighlight> confirmedHighlights) {
        List<ConfirmedHighlight> selected = selectForReview(confirmedHighlights);
        ConfirmedHighlight primary = selected.get(0);
        String publishTitle = buildPublishTitle(primary);
        String coverText = buildCoverText(primary, publishTitle);
        List<VideoInterval> intervals = new ArrayList<>();
        for (ConfirmedHighlight highlight : selected) {
            intervals.add(highlight.toVideoInterval());
        }
        HighlightMaskPlan maskPlan = advertisementMaskDetector.detect(
                intervals, workDirectory(recordPath));
        File stagingDirectory = prepareStagingDirectory(recordPath);
        List<File> reviewFiles = generateReviewClips(
                stagingDirectory, new File(recordPath).getName(), selected, maskPlan);
        if (reviewFiles.isEmpty()) {
            return false;
        }
        publishReviewFiles(recordPath, stagingDirectory, reviewFiles.size());
        copyPrimaryReview(reviewFiles.get(0), highlightFile);
        writePublishTitle(recordPath, publishTitle);
        coverGenerator.generate(recordPath, primary.getVideoFile(),
                primary.getCoverTimestamp(), coverText);
        return true;
    }

    /**
     * 本轮没有合格片段时清除上一轮正式产物，避免上传器误用过期结果。
     */
    public void clearGeneratedArtifacts(String recordPath) {
        deleteIfExists(new File(recordPath, RecordConstant.HL_VIDEO));
        deleteIfExists(new File(recordPath,
                RecordConstant.HIGHLIGHT_TITLE_FILE_NAME));
        deleteIfExists(new File(recordPath,
                RecordConstant.HIGHLIGHT_THUMBNAIL_FILE_NAME));
        clearReviewFiles(new File(recordPath, REVIEW_DIRECTORY_NAME), 0);
    }

    private List<ConfirmedHighlight> selectForReview(
            List<ConfirmedHighlight> confirmedHighlights) {
        if (confirmedHighlights == null || confirmedHighlights.isEmpty()) {
            throw new IllegalArgumentException("confirmed highlights are empty");
        }
        List<ConfirmedHighlight> ranked = new ArrayList<>(confirmedHighlights);
        ranked.sort(Comparator.comparingInt(ConfirmedHighlight::getScore)
                .thenComparingInt(ConfirmedHighlight::getAudienceValue)
                .thenComparingInt(ConfirmedHighlight::getContentDensity)
                .reversed());
        List<ConfirmedHighlight> selected = new ArrayList<>();
        for (ConfirmedHighlight candidate : ranked) {
            if (selected.stream().noneMatch(existing ->
                    existing.isSameEventAs(candidate))) {
                selected.add(candidate);
            }
            if (selected.size() >= MAXIMUM_REVIEW_CLIP_COUNT) {
                break;
            }
        }
        log.info("Found {} confirmed highlights; generating {} independent review clips "
                        + "and publishing only the best-ranked clip",
                confirmedHighlights.size(), selected.size());
        return selected;
    }

    private List<File> generateReviewClips(
            File reviewDirectory,
            String recordTime,
            List<ConfirmedHighlight> highlights,
            HighlightMaskPlan maskPlan) {
        List<File> files = new ArrayList<>();
        for (int index = 0; index < highlights.size(); index++) {
            ConfirmedHighlight highlight = highlights.get(index);
            File output = new File(reviewDirectory,
                    String.format(Locale.ROOT, "clip-%02d.mp4", index + 1));
            String title = buildPublishTitle(highlight);
            boolean merged = videoMergeService.mergeWithCover(
                    Collections.singletonList(highlight.toVideoInterval()), output,
                    buildMergeTitle(recordTime, title), maskPlan);
            if (!merged) {
                return Collections.emptyList();
            }
            files.add(output);
        }
        writeReviewManifest(reviewDirectory, highlights, files);
        return files;
    }

    private File prepareStagingDirectory(String recordPath) {
        File directory = new File(workDirectory(recordPath), "review-staging");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot create highlight staging directory: "
                            + directory.getAbsolutePath());
        }
        clearReviewFiles(directory, 0);
        return directory;
    }

    private void publishReviewFiles(
            String recordPath, File stagingDirectory, int clipCount) {
        File reviewDirectory = new File(recordPath, REVIEW_DIRECTORY_NAME);
        if (!reviewDirectory.isDirectory() && !reviewDirectory.mkdirs()) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot create highlight review directory: "
                            + reviewDirectory.getAbsolutePath());
        }
        for (int index = 1; index <= clipCount; index++) {
            copyFile(new File(stagingDirectory, reviewClipName(index)),
                    new File(reviewDirectory, reviewClipName(index)));
        }
        copyFile(new File(stagingDirectory, "manifest.json"),
                new File(reviewDirectory, "manifest.json"));
        clearReviewFiles(reviewDirectory, clipCount);
    }

    private void copyPrimaryReview(File primaryReview, File highlightFile) {
        copyFile(primaryReview, highlightFile);
    }

    private void copyFile(File source, File target) {
        try {
            Files.copy(source.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot copy highlight artifact: " + target.getAbsolutePath(), e);
        }
    }

    private void clearReviewFiles(File directory, int retainedClipCount) {
        for (int index = retainedClipCount + 1;
                index <= MAXIMUM_REVIEW_CLIP_COUNT; index++) {
            deleteIfExists(new File(directory, reviewClipName(index)));
        }
        if (retainedClipCount == 0) {
            deleteIfExists(new File(directory, "manifest.json"));
        }
    }

    private String reviewClipName(int index) {
        return String.format(Locale.ROOT, "clip-%02d.mp4", index);
    }

    private void deleteIfExists(File file) {
        try {
            if (Files.deleteIfExists(file.toPath())) {
                log.info("Removed stale highlight artifact: {}", file.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot remove stale highlight artifact: "
                            + file.getAbsolutePath(), e);
        }
    }

    private void writeReviewManifest(
            File reviewDirectory,
            List<ConfirmedHighlight> highlights,
            List<File> files) {
        JSONArray items = new JSONArray();
        for (int index = 0; index < highlights.size(); index++) {
            ConfirmedHighlight highlight = highlights.get(index);
            JSONObject item = new JSONObject(true);
            item.put("file", files.get(index).getName());
            item.put("sourceVideo", highlight.getVideoFile().getName());
            item.put("startSecond", highlight.getStartTime());
            item.put("endSecond", highlight.getEndTime());
            item.put("eventAnchorSecond", highlight.getEventAnchorTimestamp());
            item.put("score", highlight.getScore());
            item.put("audienceValue", highlight.getAudienceValue());
            item.put("contentDensity", highlight.getContentDensity());
            item.put("title", buildPublishTitle(highlight));
            item.put("reason", highlight.getReason());
            items.add(item);
        }
        File manifest = new File(reviewDirectory, "manifest.json");
        try {
            Files.write(manifest.toPath(),
                    items.toJSONString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot write highlight review manifest: "
                            + manifest.getAbsolutePath(), e);
        }
    }

    private File workDirectory(String recordPath) {
        return new File(recordPath, ".highlight-work/"
                + ProcessPluginEnum.DAN_MU_HL_VOD_CUT.getType().toLowerCase(Locale.ROOT));
    }

    private String buildMergeTitle(String timeString, String publishTitle) {
        return DateUtil.describeTime(timeString, DateUtil.YYYY_MM_DD_HH_MM_SS_V2)
                + "\n" + StringUtils.abbreviate(publishTitle, 24);
    }

    private String buildPublishTitle(ConfirmedHighlight highlight) {
        String suggestedTitle = StringUtils.trimToEmpty(highlight.getSuggestedTitle());
        if (suggestedTitle.isEmpty()) {
            return StreamerInfoHolder.getCurStreamerName() + "直播精彩片段";
        }
        String normalized = normalizeTitleText(suggestedTitle);
        if (normalized.codePointCount(0, normalized.length()) <= MAXIMUM_TITLE_CODE_POINTS) {
            return normalized;
        }
        String titleWithPunchline = preservePunchline(suggestedTitle);
        if (titleWithPunchline != null) {
            return titleWithPunchline;
        }
        int endIndex = normalized.offsetByCodePoints(0, MAXIMUM_TITLE_CODE_POINTS);
        return normalized.substring(0, endIndex);
    }

    private String buildCoverText(
            ConfirmedHighlight highlight, String publishTitle) {
        String normalized = normalizeTitleText(highlight.getCoverText());
        if (normalized.isEmpty()) {
            normalized = publishTitle;
        }
        int length = normalized.codePointCount(0, normalized.length());
        if (length <= MAXIMUM_COVER_TEXT_CODE_POINTS) {
            return normalized;
        }
        int endIndex = normalized.offsetByCodePoints(0, MAXIMUM_COVER_TEXT_CODE_POINTS);
        return normalized.substring(0, endIndex);
    }

    private String preservePunchline(String suggestedTitle) {
        String[] clauses = suggestedTitle.split("[，,。！!？?：:；;、]+");
        if (clauses.length <= 1) {
            return null;
        }
        String punchline = normalizeTitleText(clauses[clauses.length - 1]);
        int punchlineLength = punchline.codePointCount(0, punchline.length());
        if (punchlineLength < 2 || punchlineLength > 6) {
            return null;
        }
        StringBuilder prefix = new StringBuilder();
        int prefixLimit = MAXIMUM_TITLE_CODE_POINTS - punchlineLength;
        for (int index = 0; index < clauses.length - 1; index++) {
            String clause = normalizeTitleText(clauses[index]);
            int combinedLength = prefix.codePointCount(0, prefix.length())
                    + clause.codePointCount(0, clause.length());
            if (combinedLength <= prefixLimit) {
                prefix.append(clause);
            }
        }
        return prefix.length() == 0 ? null : prefix.append(punchline).toString();
    }

    private String normalizeTitleText(String text) {
        return StringUtils.trimToEmpty(text)
                .replaceAll("[\\s，,。！!？?：:；;、‘’“”\\\"']+", "");
    }

    private void writePublishTitle(String recordPath, String title) {
        File titleFile = new File(recordPath, RecordConstant.HIGHLIGHT_TITLE_FILE_NAME);
        try {
            Files.write(titleFile.toPath(),
                    Collections.singletonList(title), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "cannot write highlight title: " + titleFile.getAbsolutePath(), e);
        }
    }
}
