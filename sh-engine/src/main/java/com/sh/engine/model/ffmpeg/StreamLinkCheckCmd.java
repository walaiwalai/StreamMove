package com.sh.engine.model.ffmpeg;

import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 直播是否在线命令
 *
 * @Author caiwen
 * @Date 2024 10 26 10 45
 **/
@Slf4j
public class StreamLinkCheckCmd extends AbstractCmd {
    private static final String regex = "(\\w+)\\s*\\((?:worst|best)\\)";
    private final StringBuilder infoOutSb = new StringBuilder();

    /**
     * 流是否在线
     */
    private boolean streamOnline = false;
    private String worstResolution;
    private String bestResolution;


    public StreamLinkCheckCmd(String url) {
        super("streamlink " + url);
    }

    public void execute(long timeoutSeconds) {
        try {
            super.execute(timeoutSeconds);
        } catch (StreamerRecordException recordException) {
            if (getExitCode() == 1) {
                // 直播流没有开播，正常退出
                return;
            }
            throw recordException;
        }

        // 执行完成后
        if (streamOnline) {
            try {
                List<String> matchList = RegexUtil.getMatchList(infoOutSb.toString(), regex, false);
                worstResolution = matchList.get(0);
                bestResolution = matchList.get(1);
            } catch (Exception ignored) {
                log.error("parse stream quality error, output: {}", infoOutSb);
            }
        }
    }

    public boolean isStreamOnline() {
        return streamOnline;
    }

    public String getWorstResolution() {
        return worstResolution;
    }

    public String getBestResolution() {
        return bestResolution;
    }

    public static void main(String[] args) {
//        StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd("https://www.huya.com/chuhe");
        StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd("https://www.twitch.tv/videos/2374769396");
        checkCmd.execute(10);

        System.out.println(checkCmd.isStreamOnline());
        System.out.println(checkCmd.getBestResolution());
        System.out.println(checkCmd.getWorstResolution());

        StreamLinkCheckCmd checkCmd1 = new StreamLinkCheckCmd("chzzk.naver.com/video/5691790");
        checkCmd1.execute(10);

        System.out.println(checkCmd1.isStreamOnline());
        System.out.println(checkCmd1.getBestResolution());
        System.out.println(checkCmd1.getWorstResolution());
    }

    @Override
    protected void processOutputLine(String line) {
        streamOnline = line.contains("Available");
        infoOutSb.append(line);
    }

    @Override
    protected void processErrorLine(String line) {
        log.info("ER-STREAM>>>>" + line);
    }
}
