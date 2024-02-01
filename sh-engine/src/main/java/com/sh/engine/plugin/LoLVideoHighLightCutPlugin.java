package com.sh.engine.plugin;

import com.google.common.collect.Lists;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.plugin.lol.LoLPicData;
import com.sh.engine.plugin.lol.LolSequenceStatistic;
import com.sh.engine.service.VideoMergeService;
import com.sh.engine.util.CommandUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2024 01 26 22 18
 **/
@Component
@Slf4j
public class LoLVideoHighLightCutPlugin implements VideoProcessPlugin {
    @Resource
    private VideoMergeService videoMergeService;

    private static final int MAX_HIGH_LIGHT_COUNT = 20;

    @Override
    public String getPluginName() {
        return "LOL_HL_TCUT";
    }

    @Override
    public boolean process() {
        String recordPath = StreamerInfoHolder.getCurStreamer().getRecordPath();
        Collection<File> videos = FileUtils.listFiles(new File(recordPath), new String[]{"ts"}, false);

        // 1. 截图
        List<File> pics = Lists.newArrayList();
        for (File video : videos) {
            Optional.ofNullable(snapShot(video)).ifPresent(pics::add);
        }

        // 2. 筛选精彩区间
        List<LoLPicData> datas = pics.stream().map(this::parse).collect(Collectors.toList());

        // 3. 分析KDA找出精彩片段
        LolSequenceStatistic statistic = new LolSequenceStatistic(datas, MAX_HIGH_LIGHT_COUNT);
        List<Pair<Integer, Integer>> potentialIntervals = statistic.getPotentialIntervals();

        // 4. 过滤中间视频文件缺失掉片段

        // 5. 进行合并视频
        return videoMergeService.merge(buildMergeFileNames(potentialIntervals, videos), new File(recordPath, "highlight.mp4"));
    }

    private File snapShot(File segFile) {
        String segFileName = segFile.getName();
        String filePrefix = segFileName.substring(0, segFileName.lastIndexOf("."));

        File snapShotFile = new File(segFile.getParent(), "snapshot");
        File picFile = new File(snapShotFile, filePrefix + ".jpg");
        if (picFile.exists()) {
            // 已经存在不在重复裁剪
            return picFile;
        }

        List<String> params = Lists.newArrayList(
                "-i", segFile.getAbsolutePath(),
                "-vf", "crop=100:50:in_w*17/20:0",
                "-ss", "00:00:02",
                "-frames:v", "1",
                picFile.getAbsolutePath()
        );
        FfmpegCmd ffmpegCmd = new FfmpegCmd(StringUtils.join(params, " "));
        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
            log.info("get pic success, path: {}", segFile.getAbsolutePath());
            return picFile;
        } else {
            log.info("get pic fail, path: {}", segFile.getAbsolutePath());
            return null;
        }
    }

    private LoLPicData parse(File snapShotFile) {
        // todo 调用python进行识别
        LoLPicData data = new LoLPicData(0, 0, 0);
        data.setTargetIndex(getIndexFromFileName(snapShotFile));
        return data;
    }

    private static Integer getIndexFromFileName(File file) {
        if (!file.exists()) {
            return null;
        }
        String fileName = file.getName();
        int start = fileName.lastIndexOf("-");
        int end = fileName.lastIndexOf(".");
        return Integer.valueOf(fileName.substring(start + 1, end));
    }

    private List<String> buildMergeFileNames(List<Pair<Integer, Integer>> intervals, Collection<File> files) {
        Set<String> nameSet = files.stream().map(File::getName).collect(Collectors.toSet());
        List<String> res = Lists.newArrayList();
        for (Pair<Integer, Integer> interval : intervals) {
            for (int i = interval.getLeft(); i < interval.getRight() + 1; i++) {
                String fileName = "seg-" + i + ".ts";
                if (nameSet.contains(fileName)) {
                    res.add(fileName);
                }
            }
        }
        return res;
    }
}
