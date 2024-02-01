package com.sh.engine.plugin;

import com.google.common.collect.Lists;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.plugin.lol.LoLPicData;
import com.sh.engine.plugin.lol.LolSequenceStatistic;
import com.sh.engine.util.CommandUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * @Author caiwen
 * @Date 2024 01 26 22 18
 **/
@Component
@Slf4j
public class LoLVideoHighLightCutPlugin implements VideoProcessPlugin {
    private static final int MAX_HIGH_LIGHT_COUNT = 20;

    @Override
    public String getPluginName() {
        return "lol_highlight_cut";
    }

    @Override
    public void process(List<File> videos) {
        // 1. 截图
        List<File> pics = Lists.newArrayList();
        for (File video : videos) {
            Optional.ofNullable(snapShot(video)).ifPresent(pics::add);
        }

        // 2. 筛选精彩区间
        List<LoLPicData> datas = Lists.newArrayList();
        for (File pic : pics) {
            int index = pic.getName().split("-")[]
            datas.add(parse(pic));
        }

        // 3. 分析KDA找出精彩片段
        LolSequenceStatistic statistic = new LolSequenceStatistic(datas, maxInterval);
        return statistic.getPotentialIntervals();

        // 4. 合并视频
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
}
