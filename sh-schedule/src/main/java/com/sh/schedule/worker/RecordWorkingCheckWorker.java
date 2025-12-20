package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.sh.config.manager.StatusManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.lang3.SystemUtils;
import org.quartz.JobExecutionContext;
import org.springframework.core.env.Environment;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * @author caiWen
 * @date 2025/12/19 10:30
 */
@Slf4j
public class RecordWorkingCheckWorker extends ProcessWorker {
    private static final StatusManager statusManager = SpringUtil.getBean(StatusManager.class);
    
    // 记录每个录制目录下最大TS文件的大小
    private static final Map<String, Long> lastFileSizeMap = new ConcurrentHashMap<>();
    // 记录检测到相同大小的次数
    private static final Map<String, Integer> sameSizeCountMap = new ConcurrentHashMap<>();

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        ConcurrentMap<String, String> recordingMap = statusManager.getRecordingMap();
        // 遍历所有正在录制的直播间
        for (Map.Entry<String, String> entry : recordingMap.entrySet()) {
            String recordPath = entry.getValue();

            if (StringUtils.isBlank(recordPath)) {
                continue;
            }
            checkTsFileSize(recordPath);
        }

        // 修复类型转换问题，正确处理subtract的结果
        Collection<String> clearPaths = CollectionUtils.subtract(lastFileSizeMap.keySet(), recordingMap.values());
        for (String clearPath : clearPaths) {
            clearRecordData(clearPath);
        }
    }
    
    private void checkTsFileSize(String recordPath) {
        File recordDir = new File(recordPath);
        if (!recordDir.exists()) {
            clearRecordData(recordPath);
            return;
        }

        // 计算目录下所有文件的大小总和
        long currentTotalSize = FileUtils.sizeOfDirectory(recordDir);
        Long lastTotalSize = lastFileSizeMap.get(recordPath);

        if (lastTotalSize != null && lastTotalSize.equals(currentTotalSize)) {
            // 文件大小总和没有变化，增加计数
            int sameCount = sameSizeCountMap.getOrDefault(recordPath, 0) + 1;
            sameSizeCountMap.put(recordPath, sameCount);

            log.info("Directory total size unchanged for {} times, path: {}, size: {}", sameCount, recordPath, currentTotalSize);

            // 如果连续3次大小总和相同，强制终止录制进程
            if (sameCount >= 3) {
                log.warn("Directory total size unchanged for 3 consecutive checks, will force kill recording process, path: {}", recordPath);
                forceKillRecordingProcess(recordPath);
                // 重置计数
                sameSizeCountMap.remove(recordPath);
            }
        } else {
            // 文件大小总和有变化，重置计数
            sameSizeCountMap.put(recordPath, 0);
        }

        // 更新最后一次记录的大小总和
        lastFileSizeMap.put(recordPath, currentTotalSize);
    }
    
    private void forceKillRecordingProcess(String recordPath) {
        log.info("Attempting to kill ffmpeg processes for path: {}", recordPath);
        try {
            if (SystemUtils.IS_OS_WINDOWS) {
                killProcessOnWindows(recordPath);
            } else {
                killProcessOnLinux(recordPath);
            }
        } catch (Exception e) {
            log.error("Error force killing recording process for path: {}", recordPath, e);
        }
    }
    
    private void killProcessOnWindows(String recordPath) throws Exception {
        String escapedPath = recordPath.replace("\\", "\\\\").replace("'", "\\'");
        
        // 查找包含指定录制路径的ffmpeg进程
        CommandLine cmdLine = CommandLine.parse("wmic");
        cmdLine.addArgument("process");
        cmdLine.addArgument("where");
        cmdLine.addArgument("name='ffmpeg.exe' and commandline like '%" + escapedPath + "%'");
        cmdLine.addArgument("get");
        cmdLine.addArgument("processid");
        cmdLine.addArgument("/format:list");
        
        DefaultExecutor executor = new DefaultExecutor();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
        executor.setStreamHandler(streamHandler);
        
        try {
            executor.execute(cmdLine);
            String output = outputStream.toString();
            
            // 解析PID并杀死进程
            BufferedReader reader = new BufferedReader(new StringReader(output));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("ProcessId=")) {
                    String pidStr = line.substring("ProcessId=".length()).trim();
                    try {
                        int pid = Integer.parseInt(pidStr);
                        log.info("Killing ffmpeg process with PID: {}", pid);
                        
                        // 杀死进程
                        CommandLine killCmd = CommandLine.parse("taskkill");
                        killCmd.addArgument("/F");
                        killCmd.addArgument("/PID");
                        killCmd.addArgument(String.valueOf(pid));
                        
                        DefaultExecutor killExecutor = new DefaultExecutor();
                        int killResult = killExecutor.execute(killCmd);
                        
                        if (killResult == 0) {
                            log.info("Successfully killed process with PID: {}", pid);
                        } else {
                            log.warn("Failed to kill process with PID: {}", pid);
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Could not parse PID from wmic output line: {}", line);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error finding ffmpeg processes on Windows, trying alternative method", e);
            // 备用方法：尝试使用tasklist
            killProcessOnWindowsAlt(recordPath);
        }
    }
    
    private void killProcessOnWindowsAlt(String recordPath) throws Exception {
        CommandLine cmdLine = CommandLine.parse("tasklist");
        cmdLine.addArgument("/FI");
        cmdLine.addArgument("IMAGENAME eq ffmpeg.exe");
        cmdLine.addArgument("/FO");
        cmdLine.addArgument("CSV");
        
        DefaultExecutor executor = new DefaultExecutor();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
        executor.setStreamHandler(streamHandler);
        
        try {
            executor.execute(cmdLine);
            String output = outputStream.toString();
            
            // 简单处理：杀死所有ffmpeg进程（因为Windows上精确匹配命令行比较复杂）
            log.warn("Killing all ffmpeg processes on Windows as fallback method");
            CommandLine killCmd = CommandLine.parse("taskkill");
            killCmd.addArgument("/F");
            killCmd.addArgument("/IM");
            killCmd.addArgument("ffmpeg.exe");
            
            DefaultExecutor killExecutor = new DefaultExecutor();
            killExecutor.execute(killCmd);
            log.info("All ffmpeg processes killed on Windows");
        } catch (Exception e) {
            log.error("Error killing ffmpeg processes on Windows with alternative method", e);
        }
    }
    
    private void killProcessOnLinux(String recordPath) throws Exception {
        // Linux/Unix/Mac系统处理
        // 查找包含指定录制路径的ffmpeg进程
        CommandLine cmdLine = CommandLine.parse("ps");
        cmdLine.addArgument("aux");
        
        DefaultExecutor executor = new DefaultExecutor();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
        executor.setStreamHandler(streamHandler);
        
        executor.execute(cmdLine);
        String output = outputStream.toString();
        
        BufferedReader reader = new BufferedReader(new StringReader(output));
        String line;
        
        // 杀死找到的进程
        while ((line = reader.readLine()) != null) {
            if (line.contains("ffmpeg") && line.contains(recordPath)) {
                // 提取进程PID (第二个字段)
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 1) {
                    try {
                        int pid = Integer.parseInt(parts[1]);
                        log.info("Killing ffmpeg process with PID: {}", pid);
                        
                        // 杀死进程
                        CommandLine killCmd = CommandLine.parse("kill");
                        killCmd.addArgument("-9");
                        killCmd.addArgument(String.valueOf(pid));
                        
                        DefaultExecutor killExecutor = new DefaultExecutor();
                        int killResult = killExecutor.execute(killCmd);
                        
                        if (killResult == 0) {
                            log.info("Successfully killed process with PID: {}", pid);
                        } else {
                            log.warn("Failed to kill process with PID: {}", pid);
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Could not parse PID from ps output line: {}", line);
                    }
                }
            }
        }
    }
    
    private void clearRecordData(String recordPath) {
        lastFileSizeMap.remove(recordPath);
        sameSizeCountMap.remove(recordPath);
    }
}