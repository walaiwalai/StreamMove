package com.sh.engine.manager;

import cn.hutool.core.io.FileUtil;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigManager;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.model.record.RecordTask;
import com.sh.engine.model.record.Recorder;
import com.sh.engine.util.CommandUtil;
import com.sh.engine.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.sh.config.constant.StreamHelperPathConfig.RECORD_ROOT_PATH;

/**
 * 进行录制，停止录制
 *
 * @author caiWen
 * @date 2023/1/23 14:53
 */
@Slf4j
@Component
public class RecordManager {
    @Resource
    ConfigManager configManager;
    @Resource
    StatusManager statusManager;

    /**
     * 录播线程池
     */
    private final ExecutorService RECORD_POOL = Executors.newFixedThreadPool(4, Executors.defaultThreadFactory());

    private static Map<String, String> fakeHeaderMap = Maps.newHashMap();
    private static final String USER_AGENT = "User-Agent";

    static {
        fakeHeaderMap.put("Accept", "*/*");
        fakeHeaderMap.put("Accept-Encoding", "gzip, deflate, br");
        fakeHeaderMap.put("Accept-Language", "zh,zh-TW;q=0.9,en-US;q=0.8,en;q=0.7,zh-CN;q=0.6,ru;q=0.5");
        fakeHeaderMap.put("Origin", "https://www.huya.com");
        fakeHeaderMap.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like " +
                "Gecko) Chrome/83.0.4103.106 Safari/537.36");

    }


    /**
     * 启动录制
     *
     * @param recorder
     * @throws Exception
     */
    public void startRecord(Recorder recorder, String targetStreamUrl) {
        RecordTask recordTask = recorder.getRecordTask();
        recordTask.setStreamUrl(targetStreamUrl);
        recorder.setFfmpegProcessEnd(false);

        // 1.创建直播录像保存的文件夹（主播维度）
        String recorderName = recordTask.getRecorderName();
        log.info("begin download: {}, stream: {}", recorderName, targetStreamUrl);
        File recordStreamerFile = new File(RECORD_ROOT_PATH, recorderName);
        String pathWithRecordName = recordStreamerFile.getAbsolutePath();
        if (!recordStreamerFile.exists()) {
            recordStreamerFile.mkdir();
        }

        // 2. 创建某个主播直播录像保存的子文件夹（时间维度）
        Integer startNumber = 1;
        File timeVFile = new File(pathWithRecordName, recordTask.getTimeV());
        String pathWithTimeV = timeVFile.getAbsolutePath();
        recorder.setSavePath(pathWithTimeV);
        recorder.syncFileStatus(pathWithTimeV);


        if (!timeVFile.exists()) {
            timeVFile.mkdir();
        } else if (recorder.isPost() || statusManager.isRecordOnSubmission(pathWithTimeV)) {
            // 正在上传或者已经投稿（针对同一天有多次开播情况，前一次开播后的录像已经被上传了，这边新建一个小时分钟结尾的视频存放文件夹）
            SimpleDateFormat formatter = new SimpleDateFormat("HH-mm");
            String curTime = formatter.format(new Date());

            // 清除直播间的状态
            statusManager.deleteRoomPathStatus(recorder.getSavePath());
            recorder.setSavePath(pathWithTimeV + "-" + curTime);
            recordTask.setTimeV(recordTask.getTimeV() + " " + curTime + DateUtil.getCurDateDesc());
            timeVFile.mkdir();
        } else {
            // 计算记录应该写第几个文件
            startNumber = countVideoFileInDir(recorder.getSavePath(), recorder.getVideoExt()) + 1;
        }
        // 记录相关信息到fileStatus.json
        recorder.writeInfoToFileStatus();

        // 3.用ffmpeg进行流获取
        startDownLoadWithFfmpeg(recorder, recordTask, startNumber);
    }

    private int countVideoFileInDir(String savePath, String videoExt) {
        List<String> fileNames = FileUtil.listFileNames(savePath);
        return (int) fileNames.stream()
                .filter(fileName -> fileName.endsWith(videoExt))
                .count();

    }

    /**
     * 使用ffmpeg开始拉流
     *
     * @param recorder
     * @param recordTask
     */
    private void startDownLoadWithFfmpeg(Recorder recorder, RecordTask recordTask, Integer startNumber) {
        File fileToDownload = new File(recorder.getSavePath(),
                recordTask.getRecorderName() + "-" + recordTask.getTimeV() + "-part-%03d." + recorder.getVideoExt());

        String command = genFfmpegCmd(recordTask.getStreamUrl(), startNumber, fileToDownload.getAbsolutePath());
        statusManager.addRoomPathStatus(recorder.getSavePath());

        // 生成一个ffmpegCmd命令放到线程池中
        FfmpegCmd ffmpegCmd = new FfmpegCmd(command);
        recorder.setFfmpegCmd(ffmpegCmd);
        doFfmpegCmd(ffmpegCmd, recordTask, recorder);
    }

    private String genFfmpegCmd(String streamUrl, Integer startNumber, String downloadFileName) {
        String fakeHeaders = "";
        for (String key : fakeHeaderMap.keySet()) {
            if (StringUtils.equals(key, USER_AGENT)) {
                continue;
            }
            fakeHeaders += "$" + key + ":" + fakeHeaderMap.get(key) + "\\r\\n";
        }
//        String command = String.format(" -headers \"%s\" -user_agent \"%s\" -r 60 -async 1 -i \"%s\" -c:v copy -c:a copy -f segment " +
//                        "-segment_time %s -segment_start_number %s \"%s\"",
//                fakeHeaders,
//                fakeHeaderMap.get(USER_AGENT),
//                streamUrl,
//                configManager.getStreamHelperConfig().getSegmentDuration(),
//                startNumber,
//                downloadFileName
//        );
        String command = String.format(
                " -headers \"%s\" -user_agent \"%s\" -r 60 -async 1 -i \"%s\" -c:v libx264 -crf 18 -c:a "
                        + "aac -b:a 128k -f segment " + "-segment_time %s -segment_start_number %s \"%s\"",
                fakeHeaders,
                fakeHeaderMap.get(USER_AGENT),
                streamUrl,
                configManager.getStreamHelperConfig().getSegmentDuration(),
                startNumber,
                downloadFileName
        );
        return command;
    }

    private void doFfmpegCmd(FfmpegCmd ffmpegCmd, RecordTask recordTask, Recorder recorder) {
        CompletableFuture.supplyAsync(() -> {
            return CommandUtil.cmdExec(ffmpegCmd);
        }, RECORD_POOL).whenComplete((resCode, throwable) -> {
            if (throwable == null && resCode == 0) {
                log.info("download stream completed, recordName: {}, savePath: {}, code: {}",
                        recordTask.getRecorderName(), recorder.getSavePath(), resCode);
                recorder.writeInfoToFileStatus();
            } else {
                log.error("download stream fail, recordName: {}, savePath: {}", recordTask.getRecorderName(),
                        recorder.getSavePath());
            }
            // 清除直播间状态
            statusManager.deleteRoomPathStatus(recorder.getSavePath());
            statusManager.deleteRecorder(recorder.getRecordTask().getRecorderName());
        });
    }
}
