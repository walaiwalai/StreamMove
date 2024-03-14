package com.sh.engine.util;

import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.model.ffmpeg.StreamGobbler;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;

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
        Integer code = 999;
        String errMsg = null;
        try {
            Thread.sleep(200);
            ffmpegCmd.execute(true);

            // 打印输出信息
            StreamGobbler errorGobbler = new StreamGobbler(ffmpegCmd.getErrorStream(), "ERROR");
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

    public static Integer cmdExecWithoutLog(FfmpegCmd ffmpegCmd) {
        Integer code = 999;
        String errMsg = null;
        try {
            ffmpegCmd.execute(false);
            code = ffmpegCmd.getProcessExitCode();
        } catch (Exception e) {
            log.error("exec cmd fail, cmdStr: {}, errMsg: {}", ffmpegCmd.getFfmpegCommand(), errMsg, e);
        } finally {
            // 关闭资源
            ffmpegCmd.close();
        }
        return code;
    }

    public static String cmdExecWithRes(FfmpegCmd ffmpegCmd) {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        try {
            ffmpegCmd.execute(true);

            BufferedReader reader = new BufferedReader(new InputStreamReader(ffmpegCmd.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(ffmpegCmd.getErrorStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int code = ffmpegCmd.getProcessExitCode();
            if (code == 0) {
                return output.toString();
            } else {
                System.out.println("Command failed with error code " + code);
            }
        } catch (Exception e) {
        }

        return null;
    }
}
