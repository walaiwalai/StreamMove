package com.sh.engine.model.record;

import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.util.RecordConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
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
     * 如：...download/TheShy/2023-02-12
     */
    private String savePath;

    /**
     * 流拉取是否结束
     */
    private boolean ffmpegProcessEnd = false;

    /**
     * 视频后缀
     */
    private String videoExt = "mp4";

//    /**
//     * 执行Ffmpeg命令的对象
//     */
//    private FfmpegCmd ffmpegCmd;

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
                .recordTask(recordTask)
                .build();
    }

//    /**
//     * 手动终止录制
//     */
//    public void manualStopRecord() {
//        if (!ffmpegProcessEnd) {
//            ffmpegCmd.destroy();
//            log.info("stop recoding recordName: {}", recordTask.getRecorderName());
//            // 同步文件状态
//            writeInfoToFileStatus();
//        }
//    }

//    /**
//     * 杀死拉流进程
//     */
//    public void kill() {
//        ffmpegCmd.destroy();
//    }

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
                            .updateTime(formatter.format(new Date()))
                            .build()
            );
        }

    }
}
