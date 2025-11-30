package com.sh.engine.processor.plugin;

import com.sh.config.utils.EnvUtil;
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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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
        // 查找txt弹幕文件
        List<File> txtFiles = FileUtils.listFiles(new File(recordPath), new String[]{"txt"}, false)
                .stream()
                .filter(file -> file.getName().startsWith("P"))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(txtFiles)) {
            return true;
        }

        // 查找对应的mp4视频文件
        List<File> videoFiles = new ArrayList<>(FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false));
        if (CollectionUtils.isEmpty(videoFiles)) {
            return true;
        }

        // 获取视频尺寸信息
        VideoSizeDetectCmd videoSizeDetectCmd = new VideoSizeDetectCmd(videoFiles.get(0).getAbsolutePath());
        videoSizeDetectCmd.execute(60 * 5);

        // 为每个txt文件生成对应的ass文件（如果不存在）
        for (File txtFile : txtFiles) {
            String assFilePath = txtFile.getAbsolutePath().replace(".txt", ".ass");
            File assFile = new File(assFilePath);
            
            // 只有当ass文件不存在时才进行转换
            if (!assFile.exists()) {
                convertTxtToAssFile(txtFile, assFile, videoSizeDetectCmd.getWidth(), videoSizeDetectCmd.getHeight());
            }
        }

        // 查找ass文件
        List<File> assFiles = FileUtils.listFiles(new File(recordPath), new String[]{"ass"}, false)
                .stream()
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
        
        // 为每个ass文件生成带弹幕的视频
        for (File assFile : assFiles) {
            String mp4FileName = assFile.getName().replace(".ass", ".mp4");
            String damakuFileName = RecordConstant.DAMAKU_FILE_PREFIX + mp4FileName;
            File damakuFile = new File(recordPath, damakuFileName);
            
            // 只有当带弹幕的视频不存在时才进行合成
            if (!damakuFile.exists()) {
                File mp4File = new File(recordPath, mp4FileName);
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
        }
        return true;
    }

    @Override
    public int getMaxProcessParallel() {
        return 1;
    }

    /**
     * 将txt弹幕文件转换为ass格式
     * @param txtFile txt弹幕文件
     * @param assFile ass文件
     * @param width 视频宽度
     * @param height 视频高度
     */
    private void convertTxtToAssFile(File txtFile, File assFile, int width, int height) {
        AssWriter assWriter = new AssWriter("直播弹幕", width, height);
        
        try {
            assWriter.open(assFile.getAbsolutePath());
            
            // 读取txt文件中的每一行，并转换为SimpleDanmaku对象
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(txtFile.toPath()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        SimpleDanmaku danmaku = SimpleDanmaku.fromLine(line);
                        if (danmaku != null) {
                            assWriter.add(danmaku);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("convert txt to ass file error, txtFile: {}", txtFile.getAbsolutePath(), e);
        } finally {
            assWriter.close();
        }
    }
}