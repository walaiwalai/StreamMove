package com.sh.schedule.worker;

import cn.hutool.extra.spring.SpringUtil;
import com.sh.config.manager.StatusManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.lang3.SystemUtils;
import org.quartz.JobExecutionContext;
import org.springframework.core.env.Environment;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author caiWen
 * @date 2025/12/19 10:30
 */
@Slf4j
public class RecordProcessCheckWorker extends ProcessWorker {
    public static final Environment environment = SpringUtil.getBean(Environment.class);
    private static final StatusManager statusManager = SpringUtil.getBean(StatusManager.class);
    
    // 记录每个录制目录下最大TS文件的大小
    private static final Map<String, Long> lastFileSizeMap = new ConcurrentHashMap<>();
    // 记录检测到相同大小的次数
    private static final Map<String, Integer> sameSizeCountMap = new ConcurrentHashMap<>();

    @Override
    protected void executeJob(JobExecutionContext jobExecutionContext) {
        checkRecordProcesses();
    }

    private void checkRecordProcesses() {
        try {
            // 获取所有正在录制的目录
            String videoSavePath = environment.getProperty("sh.video-save.path");
            File baseDir = new File(videoSavePath);
            
            if (!baseDir.exists() || !baseDir.isDirectory()) {
                return;
            }
            
            // 遍历所有正在录制的直播间
            ConcurrentMap<String, String> recordingMap = statusManager.getRecordingMap();
            for (Map.Entry<String, String> entry : recordingMap.entrySet()) {
                String streamerName = entry.getKey();
                String recordPath = entry.getValue();
                
                if (StringUtils.isBlank(recordPath)) {
                    continue;
                }
                
                checkTsFileSize(recordPath);
            }
        } catch (Exception e) {
            log.error("Error checking record processes", e);
        }
    }
    
    private void checkTsFileSize(String recordPath) {
        try {
            File recordDir = new File(recordPath);
            if (!recordDir.exists() || !recordDir.isDirectory()) {
                // 清除已不存在目录的数据
                clearRecordData(recordPath);
                return;
            }
            
            // 查找最大的TS文件
            File[] tsFiles = recordDir.listFiles((dir, name) -> name.matches("P\\d+\\.ts"));
            if (tsFiles == null || tsFiles.length == 0) {
                // 没有TS文件，清除记录
                clearRecordData(recordPath);
                return;
            }
            
            // 找到编号最大的TS文件（最新的）
            File latestTsFile = null;
            int maxIndex = -1;
            for (File tsFile : tsFiles) {
                String fileName = tsFile.getName();
                int index = Integer.parseInt(fileName.replaceAll("[^\\d]", ""));
                if (index > maxIndex) {
                    maxIndex = index;
                    latestTsFile = tsFile;
                }
            }
            
            if (latestTsFile == null) {
                clearRecordData(recordPath);
                return;
            }
            
            // 检查文件大小
            long currentSize = latestTsFile.length();
            Long lastSize = lastFileSizeMap.get(recordPath);
            
            if (lastSize != null && lastSize.equals(currentSize)) {
                // 文件大小没有变化，增加计数
                int sameCount = sameSizeCountMap.getOrDefault(recordPath, 0) + 1;
                sameSizeCountMap.put(recordPath, sameCount);
                
                log.info("TS file size unchanged for {} times, path: {}, size: {}", sameCount, recordPath, currentSize);
                
                // 如果连续3次大小相同，强制终止录制进程
                if (sameCount >= 3) {
                    log.warn("TS file size unchanged for 3 consecutive checks, will force kill recording process, path: {}", recordPath);
                    forceKillRecordingProcess(recordPath);
                    // 重置计数
                    sameSizeCountMap.put(recordPath, 0);
                }
            } else {
                // 文件大小有变化，重置计数
                sameSizeCountMap.put(recordPath, 0);
            }
            
            // 更新最后一次记录的大小
            lastFileSizeMap.put(recordPath, currentSize);
        } catch (Exception e) {
            log.error("Error checking TS file size, path: {}", recordPath, e);
        }
    }
    
    private void forceKillRecordingProcess(String recordPath) {
        try {
            log.info("Attempting to kill ffmpeg processes for path: {}", recordPath);
            
            if (SystemUtils.IS_OS_WINDOWS) {
                // Windows系统处理
                killProcessOnWindows(recordPath);
            } else {
                // Linux/Unix/Mac系统处理
                killProcessOnLinux(recordPath);
            }
        } catch (Exception e) {
            log.error("Error force killing recording process for path: {}", recordPath, e);
        }
    }
    
    private void killProcessOnWindows(String recordPath) throws Exception {
        // Windows系统使用tasklist和taskkill命令
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
        // 备用方法：使用tasklist命令
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