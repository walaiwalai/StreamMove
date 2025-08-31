package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.google.common.collect.Lists;
import com.sh.config.model.stauts.FileStatusModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.quartz.JobExecutionContext;
import org.springframework.core.env.Environment;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Collection;
import java.util.List;


/**
 * @author caiWen
 * @date 2023/2/1 23:14
 */
@Slf4j
public class FileCleanWorker extends ProcessWorker {
    public static final Environment environment = SpringUtil.getBean(Environment.class);
    public static final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        gc();
        clear();
    }

    private void gc() {
        MemoryUsage heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage();
        long start = System.currentTimeMillis();
        log.info("before heap: {}M, nonHeap: {}", heapMemoryUsage.getUsed() / 1024 / 1024, nonHeapMemoryUsage.getUsed() / 1024 / 1024);
        System.gc();
        heapMemoryUsage = memoryMXBean.getHeapMemoryUsage();
        nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage();
        log.info("after heap: {}M, nonHeap: {}, cost: {}ms", heapMemoryUsage.getUsed() / 1024 / 1024, nonHeapMemoryUsage.getUsed() / 1024 / 1024, System.currentTimeMillis() - start);
    }

    private void clear() {
        List<File> streamerFiles = listRecordDir();
        for (File streamerFile : streamerFiles) {
            Collection<File> statusFiles = FileUtils.listFiles(streamerFile, new NameFileFilter("fileStatus.json"),
                    DirectoryFileFilter.INSTANCE);
            for (File statusFile : statusFiles) {
                String curRecordPath = statusFile.getParent();
                try {
                    FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(curRecordPath);
                    if (!fileStatusModel.allPost()) {
                        // 没有上传的
                        continue;
                    }
                    log.info("Begin to delete file {}", curRecordPath);
                    FileUtils.deleteDirectory(new File(curRecordPath));
                } catch (Exception e) {
                    log.error("fuck!, recordPath: {}", curRecordPath, e);
                }
            }
        }
    }

    private static List<File> listRecordDir() {
        String videoSavePath = environment.getProperty("sh.video-save.path");
        File dir = new File(videoSavePath);
        List<File> res = Lists.newArrayList();
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) {
                res.add(file);
            }
        }
        return res;
    }
}
