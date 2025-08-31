package com.sh.engine.model.buffer;

/**
 * @Author caiwen
 * @Date 2025 08 30 10 01
 **/

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * 通用CSV文件写入缓冲区
 *
 * @param <T> 要写入CSV的对象类型
 */
@Slf4j
public class GenericCsvBuffer<T extends CsvItem> {
    private final int batchSize;
    private final String savePath;
    private final List<T> items = Lists.newArrayList();
    private BufferedWriter writer;
    private long totalCnt;

    /**
     * 表头写入标记
     */
    private boolean headerWritten = false;

    /**
     * 构造方法
     *
     * @param savePath  文件保存路径
     * @param batchSize 批量写入阈值
     */
    public GenericCsvBuffer(String savePath, int batchSize) {
        this.savePath = savePath;
        this.batchSize = batchSize;
    }


    /**
     * 初始化文件和写入器
     */
    public void init() {
        try {
            writer = new BufferedWriter(new FileWriter(savePath, true));
        } catch (IOException e) {
            log.error("CSV file init fail, file: {}", savePath, e);
        }
    }

    /**
     * 添加对象到缓冲区，达到批量阈值时自动刷新
     */
    public void add(T item) {
        if (!headerWritten) {
            try {
                String header = item.genHeader();
                writer.write(header + "\n");
                headerWritten = true;
            } catch (IOException e) {
                log.error("Failed to write CSV header", e);
            }
        }
        items.add(item);
        if (items.size() >= batchSize) {
            flush();
        }
    }

    /**
     * 关闭缓冲区，刷新剩余数据
     */
    public void close() {
        try {
            if (!items.isEmpty()) {
                flush();
            }
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            log.error("CSV file close fail, file: {}", savePath, e);
        }
    }

    /**
     * 将缓冲区数据写入文件
     */
    private void flush() {
        try {
            for (T item : items) {
                writer.write(item.genLine() + "\n");
            }
            writer.flush();
            totalCnt += items.size();
            items.clear();
            log.info("Flushed {} items, total: {}", batchSize, totalCnt);
        } catch (IOException e) {
            log.error("CSV file flush fail, file: {}", savePath, e);
        }
    }

}