package com.sh.engine.model.ffmpeg;

import com.sh.engine.constant.RecordConstant;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author caiwen
 * @Date 2024 10 25 22 57
 **/
@Slf4j
public class FfmpegRecordCmd extends CommonCmd {
    /**
     * 录像是否正常结束
     */
    private boolean endNormal = true;

    /**
     * 是否推出
     */
    private boolean exitNormal = false;

    public FfmpegRecordCmd(String command) {
        super(command, false, true);
    }

    @Override
    protected void doExecute() {
        // 后台执行命令
        super.start(null, (line) -> {
            // 监听输出流，判断是否正常结束
//            2024-10-27 17:11:31.611 [Thread-6] [] INFO  com.sh.engine.model.ffmpeg.StreamGobbler:48 - ERROR>>>>[h264 @ 0xaaaaccfec860] Invalid NAL unit size (255266 > 65472).
//            2024-10-27 17:11:31.611 [Thread-6] [] INFO  com.sh.engine.model.ffmpeg.StreamGobbler:48 - ERROR>>>>[h264 @ 0xaaaaccfec860] missing picture in access unit with size 65520
            endNormal = line.contains(RecordConstant.FFMPEG_NORM_END_LINE);
        });

        // 同步等待
        exitNormal = super.getPrExitCode() == 0;
        try {
            Thread.sleep(1000);
        } catch (Exception e) {
        }
    }

    public boolean isEndNormal() {
        return endNormal;
    }

    public boolean isExitNormal() {
        return exitNormal;
    }
}
