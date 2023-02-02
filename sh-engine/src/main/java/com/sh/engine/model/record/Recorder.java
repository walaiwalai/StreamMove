package com.sh.engine.model.record;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.util.RecordConverter;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author caiWen
 * @date 2023/1/23 14:36
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
public class Recorder {
    /**
     * 录播任务
     */
    private RecordTask recordTask;

    /**
     * 录播视频保存路径
     */
    private String savePath;

    /**
     * 流拉取是否结束
     */
    private boolean ffmpegProcessEnd = false;

    /**
     * 流是否被用户手动终止
     */
    private boolean ffmpegProcessEndByUser = false;

    /**
     * 视频后缀
     */
    private String videoExt = "mp4";

    /**
     * 录播的录像是否被上传
     */
    private boolean isPost;

    /**
     * 执行Ffmpeg命令的对象
     */
    private FfmpegCmd ffmpegCmd;

    /**
     * 当前是否在录制
     *
     * @return
     */
    public boolean getRecorderStat() {
        return !this.ffmpegProcessEnd;
    }

    /**
     * 初始化一个新的recorder
     *
     * @param recordTask
     * @return
     */
    public static Recorder initRecorder(RecordTask recordTask) {
        return Recorder.builder()
                .videoExt("mp4")
                .ffmpegProcessEnd(false)
                .ffmpegProcessEndByUser(false)
                .isPost(false)
                .recordTask(recordTask)
                .build();
    }

    /**
     * 终止录制
     */
    public void stopRecord() {
        ffmpegProcessEndByUser = true;
        if (!ffmpegProcessEnd) {
            ffmpegCmd.destroy();
            log.info("stop recoding recordName: {}", recordTask.getRecorderName());
            // 同步文件状态
            writeInfoToFileStatus();
        }
    }

    /**
     * 杀死拉流进程
     */
    public void kill() {
        ffmpegCmd.destroy();
    }

    /**
     * 从fileStatus.json同步文件状态
     *
     * @param dirName
     * @throws Exception
     */
    @SneakyThrows
    public void syncFileStatus(String dirName) {
        File file = new File(dirName, "fileStatus.json");
        if (file.exists()) {
            String statusStr = IOUtils.toString(new FileInputStream(file), "utf-8");
            JSONObject statusObj = JSON.parseObject(statusStr);
            this.isPost = BooleanUtils.isTrue(statusObj.getBoolean("isPost"));
        }
    }


    /**
     * 写当前状态到fileStatus.json文件
     */
    public void writeInfoToFileStatus() {
        File file = new File(savePath, "fileStatus.json");
        FileStatusModel fileStatusModel = RecordConverter.convertToFileStatusModel(this);
        if (!file.exists()) {
            fileStatusModel.writeSelfToFile(savePath);
        } else {
            SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
            FileStatusModel.updateToFile(savePath,
                    FileStatusModel.builder()
                            .endRecordTime(formatter.format(new Date()))
                            .build()
            );
        }

    }
}
