import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.config.utils.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author caiWen
 * @date 2023/2/27 22:19
 */
@Slf4j
public class PostWorkTest {
    //    private static final String CLIENT_POST_VIDEO_URL
    //            = "https://member.bilibili.com/x/vu/client/add?access_key=%sbuild=%s&sign=%s";
    private static final String CLIENT_POST_VIDEO_URL
            = "https://member.bilibili.com/x/vu/client/add?access_key=%s";

    public static void main(String[] args) throws Exception {
        String accessToken = "4faa3abfb2c76c48c5adad38260c5e22";
        String build = "2301088";
        String servrName = "n230228032sdt8b15hl1yh32mef24g9d";
        String sign = "38b99704f9393fe15ecd7d38635999c3";

        RemoteSeverVideo remoteSeverVideo = new RemoteSeverVideo("P1", "testVideo", servrName);
        List<RemoteSeverVideo> remoteSeverVideos = Lists.newArrayList(remoteSeverVideo);

        //        String postWorkUrl = String.format(CLIENT_POST_VIDEO_URL, accessToken, build, sign);
        String postWorkUrl = String.format(CLIENT_POST_VIDEO_URL, accessToken);
        Map<String, String> uploadChunkHeaders = Maps.newHashMap();
        uploadChunkHeaders.put("Connection", "keep-alive");
        uploadChunkHeaders.put("Content-Type", "application/json");
        uploadChunkHeaders.put("user-agent", "");
        uploadChunkHeaders.put("Accept-Encoding", "gzip,deflate");
        String resp = HttpClientUtil.sendPost(postWorkUrl, uploadChunkHeaders,
                buildPostWorkParamOnClient(remoteSeverVideos));

        JSONObject respObj = JSONObject.parseObject(resp);
        if (Objects.equals(respObj.getString("code"), "0")) {
            log.info("postWork success, video is uploaded, remoteSeverVideos: {}",
                    JSON.toJSONString(remoteSeverVideos));
        } else {
            log.error("postWork failed, res: {}, title: {}", resp, JSON.toJSONString(remoteSeverVideos));
        }
    }

    private static JSONObject buildPostWorkParamOnClient(List<RemoteSeverVideo> remoteSeverVideos) {
        JSONObject params = new JSONObject();
        params.put("cover", "http://i2.hdslb.com/bfs/archive/81dc1a6000f4df7b734802558582dd8cc32bb8ed.jpg");
        params.put("build", 1088);
        params.put("title", "TESTTT2");
        params.put("tid", 171);
        params.put("tag", "英雄联盟,网络游戏");
        params.put("desc", "living");
        params.put("dynamic", "living dynamic");
        params.put("copyright", 2);
        params.put("source", "11");
        params.put("videos", remoteSeverVideos);
        params.put("open_elec", 1);

        return params;
    }
}
