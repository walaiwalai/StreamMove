package com.sh.engine.model.ffmpeg;

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
            endNormal = !line.contains("error");
            log.info("-------------------endNormal--------, {}", isEndNormal());
        });

        // 同步等待
        exitNormal = super.getPrExitCode() == 0;
    }

    public boolean isEndNormal() {
        return endNormal;
    }

    public boolean isExitNormal() {
        return exitNormal;
    }
}
