package com.sh.engine.model.ffmpeg;

import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 直播是否在线命令
 *
 * @Author caiwen
 * @Date 2024 10 26 10 45
 **/
@Slf4j
public class StreamLinkCheckCmd extends CommonCmd {
    public static final String regex = "(\\w+)\\s*\\((?:worst|best)\\)";

    /**
     * 流是否在线
     */
    private boolean streamOnline = false;
    private String worstResolution;
    private String bestResolution;

    public StreamLinkCheckCmd(String command) {
        super(command, true, false);
    }

    @Override
    protected void doExecute(long timeout, TimeUnit unit) throws Exception {
        StringBuilder output = new StringBuilder();
        CompletableFuture<Void> future = super.start((line) -> {
            output.append(line).append("\n");
        }, null);
        future.get(timeout, unit);
        super.waitExit();

        streamOnline = output.toString().contains("Available");
        if (streamOnline) {
            try {
                List<String> matchList = RegexUtil.getMatchList(output.toString(), regex, false);
                worstResolution = matchList.get(0);
                bestResolution = matchList.get(1);
            } catch (Exception ignored) {
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
//        StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd("streamlink https://www.huya.com/chuhe");
        StreamLinkCheckCmd checkCmd = new StreamLinkCheckCmd("streamlink https://www.twitch.tv/videos/2374769396");
        checkCmd.execute(10, TimeUnit.SECONDS);

        System.out.println(checkCmd.isStreamOnline());
        System.out.println(checkCmd.getBestResolution());
        System.out.println(checkCmd.getWorstResolution());

        StreamLinkCheckCmd checkCmd1 = new StreamLinkCheckCmd("streamlink chzzk.naver.com/video/5691790");
        checkCmd1.execute(10, TimeUnit.SECONDS);

        System.out.println(checkCmd1.isStreamOnline());
        System.out.println(checkCmd1.getBestResolution());
        System.out.println(checkCmd1.getWorstResolution());
    }
}
