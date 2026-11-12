package com.sh.engine.processor.plugin.highlight.analysis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 从视频真实编码包提取绝对时钟，避免用目录日期猜测首帧。 */
@Component
@Slf4j
public class SeiMediaClock {
    @Getter
    @AllArgsConstructor
    public static class Anchor {
        private final double pts;
        private final long wallMillis;
    }

    @Getter
    @AllArgsConstructor
    public static class Source {
        private final File video;
        private final double duration;
        private final double origin;
        private final List<Anchor> anchors;

        public Double locate(long wallMillis) {
            if (anchors.isEmpty()) {
                return null;
            }
            int low = 0, high = anchors.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (anchors.get(middle).wallMillis <= wallMillis) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            int index = low - 1;
            if (index < 0 || index >= anchors.size() - 1) {
                Anchor edge = anchors.get(index < 0 ? 0 : anchors.size() - 1);
                double delta = (wallMillis - edge.wallMillis) / 1000.0;
                double seek = edge.pts + delta - origin;
                return Math.abs(delta) <= 3 && seek >= 0 && seek < duration ? seek : null;
            }
            Anchor a = anchors.get(index), b = anchors.get(index + 1);
            double wallDelta = (b.wallMillis - a.wallMillis) / 1000.0;
            double ptsDelta = b.pts - a.pts;
            if (wallDelta <= 0 || ptsDelta <= 0 || Math.abs(wallDelta - ptsDelta) > 1) {
                return null;
            }
            return a.pts + (wallMillis - a.wallMillis) / 1000.0 * ptsDelta / wallDelta - origin;
        }
    }

    public List<Source> read(List<File> videos) {
        List<Source> result = new ArrayList<>();
        for (File video : videos) {
            result.add(read(video));
        }
        return result;
    }

    private Source read(File video) {
        try {
            Process metadata = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries",
                    "format=duration,start_time:stream=codec_type,codec_name,nal_length_size", "-of", "json",
                    video.getAbsolutePath()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            JSONObject meta = JSON.parseObject(readAll(metadata.getInputStream()));
            if (metadata.waitFor() != 0) {
                throw new IOException("ffprobe metadata failed");
            }
            JSONObject format = meta.getJSONObject("format");
            double duration = format.getDoubleValue("duration");
            double origin = format.getDoubleValue("start_time");
            int lengthSize = 4;
            for (Object entry : meta.getJSONArray("streams")) {
                JSONObject stream = (JSONObject) entry;
                if (!"video".equals(stream.getString("codec_type"))) {
                    continue;
                }
                if (!"h264".equals(stream.getString("codec_name"))) {
                    return new Source(video, duration, origin, Collections.emptyList());
                }
                if (stream.containsKey("nal_length_size")) {
                    lengthSize = stream.getIntValue("nal_length_size");
                }
            }
            List<Anchor> anchors = new ArrayList<>();
            Process packets = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0",
                    "-show_packets", "-show_entries", "packet=pts_time,pos,size,flags",
                    "-of", "compact=p=0:nk=0", video.getAbsolutePath())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(packets.getInputStream(), StandardCharsets.UTF_8));
                 RandomAccessFile media = new RandomAccessFile(video, "r")) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Map<String, String> values = new HashMap<>();
                    for (String field : line.split("\\|")) {
                        String[] pair = field.split("=", 2);
                        if (pair.length == 2) {
                            values.put(pair[0], pair[1]);
                        }
                    }
                    if (!values.getOrDefault("flags", "").contains("K")) {
                        continue;
                    }
                    int size = Integer.parseInt(values.get("size"));
                    if (size <= 0 || size > 16 * 1024 * 1024) {
                        throw new IOException("Invalid video packet size");
                    }
                    byte[] packet = new byte[size];
                    media.seek(Long.parseLong(values.get("pos")));
                    media.readFully(packet);
                    Long wall = timestamp(packet, lengthSize);
                    if (wall != null) {
                        anchors.add(new Anchor(Double.parseDouble(values.get("pts_time")), wall));
                    }
                }
            } finally {
                if (packets.waitFor() != 0) {
                    throw new IOException("ffprobe packet scan failed");
                }
            }
            anchors.sort(Comparator.comparingDouble(Anchor::getPts));
            log.info("SEI clock indexed: {}, anchors: {}", video.getName(), anchors.size());
            return new Source(video, duration, origin, anchors);
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Cannot index media clock: " + video.getName(), error);
        }
    }

    static Long timestamp(byte[] packet, int lengthSize) throws IOException {
        int offset = 0;
        while (offset + lengthSize <= packet.length) {
            int size = 0;
            for (int i = 0; i < lengthSize; i++) {
                size = (size << 8) | (packet[offset++] & 255);
            }
            if (size <= 0 || size > packet.length - offset) {
                throw new IOException("Invalid AVCC NAL length");
            }
            if ((packet[offset] & 31) == 6) {
                ByteArrayOutputStream rbsp = new ByteArrayOutputStream();
                int zeros = 0;
                for (int i = offset + 1; i < offset + size; i++) {
                    int value = packet[i] & 255;
                    if (zeros >= 2 && value == 3) {
                        zeros = 0;
                        continue;
                    }
                    rbsp.write(value);
                    zeros = value == 0 ? zeros + 1 : 0;
                }
                byte[] data = rbsp.toByteArray();
                int i = 0;
                while (i < data.length - 1) {
                    int type = 0, count = 0;
                    while (i < data.length && (data[i] & 255) == 255) { type += 255; i++; }
                    if (i >= data.length) { break; }
                    type += data[i++] & 255;
                    while (i < data.length && (data[i] & 255) == 255) { count += 255; i++; }
                    if (i >= data.length) { break; }
                    count += data[i++] & 255;
                    if (count > data.length - i) { throw new IOException("Invalid SEI payload length"); }
                    if (type == 100 && count > 0 && data[i] == '{') {
                        JSONObject json = JSON.parseObject(new String(data, i, count, StandardCharsets.UTF_8));
                        long wall = json.getLongValue("ts");
                        if (wall > 1500000000000L && wall < 2000000000000L) { return wall; }
                    }
                    i += count;
                }
            }
            offset += size;
        }
        return null;
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int size;
        while ((size = input.read(buffer)) >= 0) {
            output.write(buffer, 0, size);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
