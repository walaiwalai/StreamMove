package com.sh.engine.model.ffmpeg;

import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.engine.constant.StreamChannelTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播是否在线命令
 *
 * @Author caiwen
 * @Date 2024 10 26 10 45
 **/
@Slf4j
public class StreamLinkCheckCmd extends AbstractCmd {
    private final StringBuilder infoOutSb = new StringBuilder();
    /**
     * 用于匹配可用流的行
     */
    private static final String AVAILABLE_STREAMS_PATTERN = "Available streams: (.*)";
    /**
     * 存储所有分辨率列表（按输出顺序，从worst到best）
     */
    private List<String> resolutions = new ArrayList<>();
    /**
     * 流是否在线
     */
    private boolean streamOnline = false;

    public StreamLinkCheckCmd(String url) {
        super("");
        StreamChannelTypeEnum channelEnum = StreamChannelTypeEnum.findChannelByUrl(url);
        if (channelEnum == StreamChannelTypeEnum.AFREECA_TV) {
            this.command = buildSoopCommand(url);
        } else {
            this.command = "streamlink " + url;
        }
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
            resolutions = parseResolutions(infoOutSb.toString());
        }
    }

    /**
     * 解析输出内容获取所有分辨率信息
     */
    private List<String> parseResolutions(String output) {
        List<String> qualities = new ArrayList<>();
        Pattern pattern = Pattern.compile(AVAILABLE_STREAMS_PATTERN);
        Matcher matcher = pattern.matcher(output);

        if (matcher.find()) {
            String streamsPart = matcher.group(1).trim();
            String[] streamItems = streamsPart.split(",");

            // 标记是否开始收集（找到worst后开始）和是否结束收集（找到best后结束）
            boolean startCollecting = false;
            boolean stopCollecting = false;

            for (String item : streamItems) {
                String trimmedItem = item.trim();
                // 提取分辨率名称（去除括号及内容）
                String resName = trimmedItem.replaceAll("\\s*\\([^)]+\\)", "").trim();

                // 找到worst时开始收集
                if (trimmedItem.contains("(worst)")) {
                    startCollecting = true;
                }

                // 找到best时标记结束点
                if (trimmedItem.contains("(best)")) {
                    stopCollecting = true;
                }

                // 只收集worst到best之间（包括两者）的分辨率
                if (startCollecting) {
                    qualities.add(resName);

                    // 如果已经到best，停止继续收集
                    if (stopCollecting) {
                        break;
                    }
                }
            }
        }
        Collections.reverse(qualities);
        return qualities;
    }

    /**
     * 根据参数选择分辨率
     *
     * @param qualityParam 选择参数
     * @return 选中的分辨率名称
     */
    public String selectQuality(int qualityParam) {
        if (CollectionUtils.isEmpty(resolutions)) {
            return "best";
        }
        if (qualityParam >= resolutions.size()) {
            qualityParam = resolutions.size() - 1;
        }
        return resolutions.get(qualityParam);
    }

    public boolean isStreamOnline() {
        return streamOnline;
    }

    public String getBestResolution() {
        return selectQuality(0);
    }

    private String buildSoopCommand(String url) {
        String soopUserName = ConfigFetcher.getInitConfig().getSoopUserName();
        String soopPassword = ConfigFetcher.getInitConfig().getSoopPassword();
        if (StringUtils.isNotBlank(soopUserName) && StringUtils.isNotBlank(soopPassword)) {
            return "streamlink --soop-username " + soopUserName + " --soop-password " + soopPassword + " " + url;
        } else {
            return "streamlink " + url;
        }
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

    public static void main(String[] args) {
        StreamLinkCheckCmd streamLinkCheckCmd = new StreamLinkCheckCmd("https://live.douyin.com/510200350291");
        streamLinkCheckCmd.execute(1);
        System.out.println(streamLinkCheckCmd.isStreamOnline());
        System.out.println(streamLinkCheckCmd.selectQuality(0));
    }
}
