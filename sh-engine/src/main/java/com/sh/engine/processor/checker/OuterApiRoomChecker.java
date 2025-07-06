//package com.sh.engine.processor.checker;
//
//import com.alibaba.fastjson.JSON;
//import com.alibaba.fastjson.JSONObject;
//import com.google.common.collect.Maps;
//import com.sh.config.model.config.StreamerConfig;
//import com.sh.config.utils.OkHttpClientUtil;
//import com.sh.engine.constant.StreamChannelTypeEnum;
//import com.sh.engine.processor.recorder.Recorder;
//import com.sh.engine.processor.recorder.StreamUrlRecorder;
//import lombok.extern.slf4j.Slf4j;
//import okhttp3.MediaType;
//import okhttp3.Request;
//import okhttp3.RequestBody;
//import org.apache.commons.lang3.BooleanUtils;
//import org.apache.commons.lang3.StringUtils;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//
//import java.util.Date;
//import java.util.Map;
//
///**
// * 外部部署的api
// *
// * @Author caiwen
// * @Date 2025 06 19 23 35
// **/
//@Component
//@Slf4j
//public class OuterApiRoomChecker extends AbstractRoomChecker {
//    @Value("${live.api.server.host}")
//    private String liveHost;
//    @Value("${live.api.server.port}")
//    private String livePort;
//
//    @Override
//    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
//        MediaType mediaType = MediaType.parse("application/json");
//        Map<String, String> params = Maps.newHashMap();
//        params.put("url", streamerConfig.getRoomUrl());
//        params.put("quality", "原画");
//        RequestBody body = RequestBody.create(mediaType, JSON.toJSONString(params));
//        Request request = new Request.Builder()
//                .url("http://" + liveHost + ":" + livePort + "/stream_info")
//                .post(body)
//                .addHeader("Content-Type", "application/json")
//                .build();
//        String resp = OkHttpClientUtil.execute(request);
//        if (StringUtils.isBlank(resp)) {
//            log.error("no resp for stream info");
//            return null;
//        }
//        JSONObject respObj = JSON.parseObject(resp);
//        if (BooleanUtils.isNotTrue(respObj.getBoolean("is_live"))) {
//            return null;
//        }
//        log.info("get stream info success, resp: {}", resp);
//        return new StreamUrlRecorder(new Date(), getType().getType(), respObj.getString("record_url"));
//    }
//
//    @Override
//    public StreamChannelTypeEnum getType() {
//        return StreamChannelTypeEnum.LIVE_RECORD_API;
//    }
//}
