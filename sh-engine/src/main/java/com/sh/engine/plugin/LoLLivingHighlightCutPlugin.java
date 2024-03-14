package com.sh.engine.plugin;

import com.google.common.collect.Lists;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.util.CommandUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 03 14 18 42
 **/
@Slf4j
@Component
public class LoLLivingHighlightCutPlugin extends LoLVodHighLightCutPlugin implements VideoProcessPlugin {
    @Override
    public String getPluginName() {
        return "LOL_HL_LIVING_CUT";
    }

    @Override
    public boolean process(String recordPath) {
//        File highlightFile = new File(recordPath, "highlight.mp4");
//        if (highlightFile.exists()) {
//            log.info("highlight file already existed, will skip, path: {}", recordPath);
//            return true;
//        }
//
//        Collection<File> videos = FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false)
//                .stream()
//                .sorted(Comparator.comparingLong(File::lastModified))
//                .collect(Collectors.toList());
//
//        // 1. 截图
//        File snapShotFile = new File(recordPath, "snapshot");
//        if (!snapShotFile.exists()) {
//            snapShotFile.mkdir();
//        }
//
//        for (File video : videos) {
//            snapShot(video);
//        }
//        List<File> pics = FileUtils.listFiles(snapShotFile, new String[]{"jpg"}, false)
//                .stream()
//                .sorted(Comparator.comparingLong(File::lastModified))
//                .collect(Collectors.toList());
//
//        // 2. 筛选精彩区间
//        List<LoLPicData> datas = ocrSeqPics(pics).stream()
//                .filter(d -> d.getTargetIndex() != null)
//                .collect(Collectors.toList());
//
//        // 3. 分析KDA找出精彩片段
//        LolSequenceStatistic statistic = new LolSequenceStatistic(datas, MAX_HIGH_LIGHT_COUNT);
//        List<Pair<Integer, Integer>> potentialIntervals = statistic.getPotentialIntervals();
//
//        // 4. 进行合并视频
//        return videoMergeService.mergeMulti(buildMergeFileNames(potentialIntervals, videos), highlightFile);
        return false;
    }

    private void snapShot(File segFile) {
        Integer index = VideoFileUtils.getIndexOnLivingVideo(segFile);
        File snapShotFile = new File(segFile.getParent(), "snapshot");
        File picFile = new File(snapShotFile, index + "-%04d.jpg");

        List<String> params = Lists.newArrayList(
                "-y",
                "-i", segFile.getAbsolutePath(),
                "-vf", "\"fps=1/5,crop=80:30:in_w*867/1000:0\"",
                "-qscale:v", "2",
                picFile.getAbsolutePath()
        );

        FfmpegCmd ffmpegCmd = new FfmpegCmd(StringUtils.join(params, " "));
        Integer resCode = CommandUtil.cmdExecWithoutLog(ffmpegCmd);
        if (resCode == 0) {
            log.info("get pic for living video success, path: {}", segFile.getAbsolutePath());
        } else {
            log.info("get pic for living video fail, path: {}", segFile.getAbsolutePath());
        }
    }
}
