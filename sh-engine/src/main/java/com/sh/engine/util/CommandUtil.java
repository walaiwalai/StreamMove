package com.sh.engine.util;

import com.google.common.collect.Maps;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.model.ffmpeg.StreamGobbler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * @author caiWen
 * @date 2023/1/24 15:24
 */
@Slf4j
public class CommandUtil {
    /**
     * @throws
     * @Description:
     * @param: @param cmdStr
     * @param: @return
     * @return: Integer
     */
    public static Integer cmdExec(FfmpegCmd ffmpegCmd) {
        // 错误流
        Integer code = 999;
        String errMsg = null;
        try {
            // destroyOnRuntimeShutdown表示是否立即关闭Runtime, openIOStreams表示是不是需要打开输入输出流:
            // 如果ffmpeg命令需要长时间执行，destroyOnRuntimeShutdown = false
            Thread.sleep(5000);
//            ffmpegCmd.execute(false, true);
            ffmpegCmd.execute(true, true);

            // 打印输出信息
            StreamGobbler errorGobbler = new StreamGobbler(ffmpegCmd.getErrorStream(),  "ERROR");
            errorGobbler.start();

            StreamGobbler outGobbler = new StreamGobbler(ffmpegCmd.getInputStream(), "OUTPUT",
                    ffmpegCmd.getOutputStream());
            outGobbler.start();

            code = ffmpegCmd.getProcessExitCode();
        } catch (Exception e) {
            log.error("exec cmd fail, cmdStr: {}, errMsg: {}", ffmpegCmd.getFfmpegCommand(), errMsg, e);
        } finally {
            // 关闭资源
            ffmpegCmd.close();
        }
        return code;
    }
}
