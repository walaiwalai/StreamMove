package com.sh.engine.command;

import com.sh.engine.command.callback.Recorder2StorageCallback;
import com.sh.engine.command.callback.RecordCallback;
import com.sh.engine.model.bili.RecordSegmentInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author caiwen
 * @Date 2024 10 25 22 57
 **/
@Slf4j
public class FfmpegRecordCmd extends AbstractCmd {
    private static final Pattern SEGMENT_OPEN_PATTERN = Pattern.compile("Opening '(.+?)' for writing");
    /**
     * 已完成录制的分片列表
     */
    private final List<String> completedSegments = new CopyOnWriteArrayList<>();

    /**
     * 当前正在录制的分片
     */
    private final AtomicReference<String> recordingSegment = new AtomicReference<>();

    /**
     * 执行回调的函数
     */
    private RecordCallback recordCallback;

    public FfmpegRecordCmd(String command) {
        super(command);
    }

    /**
     * 注册分段完成回调函数
     *
     * @param callback 回调函数
     */
    public void addSegmentCompletedCallback( RecordCallback callback) {
        if (callback != null) {
            this.recordCallback = callback;
        }
    }

    @Override
    protected void processOutputLine(String line) {
    }

    @Override
    protected void processErrorLine(String line) {
        if (recordCallback != null) {
            callbackSegmentFinish(line);
        }
    }

    public void execute(long timeoutSeconds) {
        try {
            super.execute(timeoutSeconds);
        } catch (Exception ignored) {
        } finally {
            // 处理最后一个分片（录制结束时，当前分片也已完成）
            if (recordCallback != null) {
                callBackLastSegFinish();
            }
        }
    }

    private void callBackLastSegFinish() {
        String lastSegment = recordingSegment.get();
        if (lastSegment != null && !lastSegment.isEmpty()) {
            completedSegments.add(lastSegment);
            // 执行回调
            try {
                recordCallback.onSegmentCompleted(lastSegment, true);
            } catch (Exception e) {
                log.error("分片完成回调执行失败，文件: {}", lastSegment, e);
            }
        }
    }


    /**
     * 检查日志行，更新分片状态并触发回调
     */
    private void callbackSegmentFinish( String line) {
        Matcher matcher = SEGMENT_OPEN_PATTERN.matcher(line);
        if (!matcher.find()) {
            return;
        }

        String newSegment = matcher.group(1);
        // 获取上一个正在录制的分片
        String previousSegment = recordingSegment.getAndSet(newSegment);

        // 如果上一个分片存在，说明其已完成录制
        if (previousSegment != null && !previousSegment.isEmpty()) {
            // 添加到已完成列表
            completedSegments.add(previousSegment);

            // 执行回调
            try {
                recordCallback.onSegmentCompleted(previousSegment, false);
            } catch (Exception e) {
                log.error("分片完成回调执行失败，文件: {}", previousSegment, e);
            }
        }
    }

    public boolean isExitNormal() {
        boolean normalExit = super.isNormalExit();
        if (!normalExit) {
            log.error("ffmpeg record fail, command: {}, code: {}", command, getExitCode());
        }
        return normalExit;
    }

    public static void main(String[] args) {
        String s = "streamlink  --stream-segment-threads 3 --retry-streams 3 --retry-open 3 https://live.douyin.com/22807555266 hd --stdout | ffmpeg -y -loglevel info -hide_banner -i - -rw_timeout 30000000 -reconnect_streamed 1 -reconnect_delay_max 60 -thread_queue_size 4096 -max_muxing_queue_size 4096 -analyzeduration 40000000 -probesize 20000000 -fflags \"+discardcorrupt +igndts +genpts\" -correct_ts_overflow 1 -avoid_negative_ts 1 -rtbufsize 100M -bufsize 50000k -c:v copy -c:a copy -c:s mov_text -map 0:v -map 0:a -map 0:s? -f segment -segment_format mpegts -reset_timestamps 1 -segment_time 10 -segment_start_number 1 \"G:/stream_record/download/mytest-mac/2025-08-15-20-59-49/P%02d.ts\"";
        FfmpegRecordCmd ffmpegRecordCmd = new FfmpegRecordCmd(s);
        ffmpegRecordCmd.addSegmentCompletedCallback(new Recorder2StorageCallback());

        ffmpegRecordCmd.execute(3600);
    }
}
