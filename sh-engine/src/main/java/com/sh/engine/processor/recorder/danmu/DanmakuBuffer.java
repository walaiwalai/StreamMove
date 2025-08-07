package com.sh.engine.processor.recorder.danmu;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2025 08 03 16 43
 **/
@Slf4j
public class DanmakuBuffer {
    private final String filePath;
    private List<DanmakuItem> items;
    private final int batchSize;
    private long cnt;
    private BufferedWriter writer;

    public DanmakuBuffer(String filePath) {
        this.filePath = filePath;
        this.items = Lists.newArrayList();
        this.batchSize = 100;
    }

    /**
     * 初始化缓冲区，创建文件并写入表头
     */
    public void init() {
        try {
            // 创建文件写入器，true表示追加模式
            writer = new BufferedWriter(new FileWriter(filePath, true));

            // 写csv表头
            writer.write("timestamp,content\n");

            // 注册关闭钩子，确保程序退出时刷新缓冲区
            Runtime.getRuntime().addShutdownHook(new Thread(this::close));
            log.info("Danmaku file init success, file: {}", filePath);
        } catch (IOException e) {
            log.error("Danmaku file init fail, file: {}", filePath, e);
        }
    }

    /**
     * 添加弹幕到缓冲区，当达到批量大小时自动刷新
     */
    public void add(DanmakuItem item) {
        if (item == null || writer == null) {
            return;
        }

        items.add(item);

        // 当缓冲区达到设定大小时，执行刷新操作
        if (items.size() >= batchSize) {
            flush();
        }
    }

    /**
     * 关闭缓冲区，释放资源
     */
    public void close() {
        try {
            // 刷新剩余数据
            if (!items.isEmpty()) {
                flush();
            }

            // 关闭写入器
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            log.error("Danmaku file close fail, file: {}", filePath, e);
        }
    }

    /**
     * 将缓冲区中的弹幕写入文件
     */
    private void flush() {
        if (items.isEmpty() || writer == null) {
            return;
        }

        try {
            // 批量写入所有弹幕
            for (DanmakuItem item : items) {
                String line = String.format("%d,%s", item.getTs(), escapeCsv(item.getContent()));
                writer.write(line + "\n");
            }

            // 强制刷新到磁盘
            writer.flush();
            cnt += items.size();

            log.info("total {} danmu have been collected", cnt);

            // 清空缓冲区
            items.clear();
        } catch (IOException e) {
            log.error("Danmaku file flush fail, file: {}", filePath, e);
        }
    }

    /**
     * 处理CSV特殊字符
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }

        // 如果包含逗号、引号或换行符，需要用双引号包裹
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            // 替换双引号为两个双引号
            value = value.replace("\"", "\"\"");
            return "\"" + value + "\"";
        }

        return value;
    }
}
