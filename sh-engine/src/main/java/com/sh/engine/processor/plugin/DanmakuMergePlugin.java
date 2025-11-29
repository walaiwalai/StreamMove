package com.sh.engine.processor.plugin;

import com.alibaba.fastjson.TypeReference;
import com.sh.config.utils.EnvUtil;
import com.sh.config.utils.FileStoreUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.ffmpeg.AssVideoMergeCmd;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import com.sh.engine.processor.recorder.danmu.AssWriter;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class DanmakuMergePlugin implements VideoProcessPlugin {
    @Resource
    MsgSendService msgSendService;

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.DAMAKU_MERGE.getType();
    }

    @Override
    public boolean process(String recordPath) {
        List<File> jsonFiles = FileUtils.listFiles(new File(recordPath), new String[]{"json"}, false)
                .stream()
                .filter(file -> file.getName().startsWith("P"))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(jsonFiles)) {
            return true;
        }

        List<File> videoFiles = new ArrayList<>(FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false));
        if (CollectionUtils.isEmpty(videoFiles)) {
            return true;
        }
        VideoSizeDetectCmd videoSizeDetectCmd = new VideoSizeDetectCmd(videoFiles.get(0).getAbsolutePath());
        videoSizeDetectCmd.execute(60 * 5);
        for (File jsonFile : jsonFiles) {
            convert2AssFile(jsonFile, videoSizeDetectCmd.getWidth(), videoSizeDetectCmd.getHeight());
        }

        // 弹幕ass文件
        List<File> assFiles = FileUtils.listFiles(new File(recordPath), new String[]{"ass"}, false)
                .stream()
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
        for (File assFile : assFiles) {
            String damakuFileName = RecordConstant.DAMAKU_FILE_PREFIX + assFile.getName().replace(".ass", ".mp4");
            File damakuFile = new File(assFile.getParent(), damakuFileName);
            if (damakuFile.exists()) {
                continue;
            }
            File mp4File = new File(assFile.getParent(), assFile.getName().replace(".ass", ".mp4"));
            if (!mp4File.exists()) {
                continue;
            }

            AssVideoMergeCmd assVideoMergeCmd = new AssVideoMergeCmd(assFile, mp4File);
            assVideoMergeCmd.execute(10 * 3600);
            if (assVideoMergeCmd.isNormalExit()) {
                msgSendService.sendText("合并弹幕文件成功！路径为：" + damakuFile.getAbsolutePath());
                // 成功就刪除原始MP4文件
                if (EnvUtil.isProd()) {
                    FileUtils.deleteQuietly(mp4File);
                }
            } else {
                msgSendService.sendText("合并弹幕文件失败！路径为：" + damakuFile.getAbsolutePath());
                // 失敗就刪除合成的彈幕文件
                if (EnvUtil.isProd()) {
                    FileUtils.deleteQuietly(damakuFile);
                }
            }
        }
        return true;
    }

    @Override
    public int getMaxProcessParallel() {
        return 1;
    }


    private void convert2AssFile(File jsonFile, int width, int height) {
        AssWriter assWriter = new AssWriter("直播弹幕", width, height);
        String assFile = jsonFile.getAbsolutePath().replace(".json", ".ass");
        List<SimpleDanmaku> simpleDanmakus = FileStoreUtil.loadFromFile(jsonFile, new TypeReference<List<SimpleDanmaku>>() {
        });

        try {
            assWriter.open(assFile);
            for (SimpleDanmaku danmakus : simpleDanmakus) {
                assWriter.add(danmakus);
            }
        } catch (IOException e) {
            log.error("covert danmu error", e);
        } finally {
            assWriter.close();
        }
    }
}
