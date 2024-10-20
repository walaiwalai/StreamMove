package com.sh.config.manager;

import com.google.common.collect.Lists;
import io.minio.*;
import io.minio.errors.MinioException;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 上传文件到minio
 *
 * @Author caiwen
 * @Date 2024 10 18 00 07
 **/
@Slf4j
public class MinioManager {
    private static MinioClient minioClient;
    private static final String BUCKET_NAME = "stream";

    public static MinioClient getMinioClient() {
        if (minioClient == null) {
            // 单例创建MinioClient
            synchronized (MinioManager.class) {
                if (minioClient == null) {
                    minioClient = MinioClient.builder()
                            .endpoint(ConfigFetcher.getInitConfig().getMinioUrl())
                            .credentials(ConfigFetcher.getInitConfig().getMinioAccessKey(), ConfigFetcher.getInitConfig().getMinioSecretKey())
                            .build();
                }
            }
        }
        return minioClient;
    }

    /**
     * 上传单个文件
     *
     * @param file
     * @param objPPath
     * @throws IOException
     * @throws MinioException
     */
    public static boolean uploadFile(File file, String objPPath) {
        String objectName = objPPath + "/" + file.getName();

        try (FileInputStream fis = new FileInputStream(file)) {
            // 使用 Minio SDK 上传文件
            getMinioClient().putObject(
                    PutObjectArgs.builder()
                            .bucket(BUCKET_NAME)
                            .object(objectName)
                            .stream(fis, file.length(), -1)
                            .contentType(Files.probeContentType(Paths.get(file.getAbsolutePath())))
                            .build()
            );
            return true;
        } catch (Exception e) {
            log.error("Error uploading file: {}", file.getAbsolutePath(), e);
            return false;
        }
    }


    public static boolean doesFileExist(String objectPath) {
        try {
            getMinioClient().statObject(
                    StatObjectArgs.builder()
                            .bucket(BUCKET_NAME)
                            .object(objectPath)
                            .build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取minio路径下的文件（不递归）
     *
     * @param minioFolderPath
     * @return
     */
    public static List<String> listFolderNames(String minioFolderPath) {
        List<String> folderNames = new ArrayList<>();
        Iterable<Result<Item>> results = getMinioClient().listObjects(
                ListObjectsArgs.builder()
                        .bucket(BUCKET_NAME)
                        .prefix(minioFolderPath)
                        .recursive(false)
                        .build()
        );

        // 遍历所有对象，过滤出文件夹
        for (Result<Item> result : results) {
            try {
                Item item = result.get();
                if (item.isDir()) {
                    // 获取文件夹名称（去掉前缀路径）
                    String objName = item.objectName();
                    folderNames.add(objName.substring(minioFolderPath.length(), objName.length() - 1));
                }
            } catch (Exception e) {
                return Lists.newArrayList();
            }
        }

        return folderNames;
    }

    /**
     * 获取前缀下的所有文件名称
     *
     * @param prefix
     * @return
     */
    public static List<String> listObjectNames(String prefix) {
        Iterable<Result<Item>> results = getMinioClient().listObjects(
                ListObjectsArgs.builder()
                        .bucket(BUCKET_NAME)
                        .recursive(false)
                        .prefix(prefix)
                        .build()
        );
        List<String> res = Lists.newArrayList();
        for (Result<Item> result : results) {
            Item item = null;
            try {
                item = result.get();
            } catch (Exception e) {
            }
            res.add(item.objectName());
        }
        return res;
    }

    /**
     * 下载单个文件
     *
     * @param objectName
     * @param localFolderPath
     */
    public static boolean downloadFile(String objectName, String localFolderPath) {
        File savePath = new File(localFolderPath, objectName.substring(objectName.lastIndexOf("/")));
        String localFilePath = savePath.getAbsolutePath();
        try (InputStream is = getMinioClient().getObject(
                GetObjectArgs.builder()
                        .bucket(BUCKET_NAME)
                        .object(objectName)
                        .build());
             FileOutputStream fos = new FileOutputStream(localFilePath)) {

            byte[] buf = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buf)) != -1) {
                fos.write(buf, 0, bytesRead);
            }
            return true;
        } catch (Exception e) {
            log.error("Error downloading objectName: {}", objectName, e);
            return false;
        }
    }
}
