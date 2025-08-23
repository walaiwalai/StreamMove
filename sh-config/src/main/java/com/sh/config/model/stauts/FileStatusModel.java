package com.sh.config.model.stauts;

import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Maps;
import com.sh.config.utils.FileStoreUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author caiWen
 * @date 2023/1/25 22:51
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
public class FileStatusModel {
    /**
     * 各平台上传情况
     */
    private List<String> platforms;

    /**
     * 各平台上传情况
     */
    private Map<String, Boolean> postMap = Maps.newHashMap();

    /**
     * 写到fileStatus.json
     */
    public void writeSelfToFile(String path) {
        FileStoreUtil.saveToFile(new File(path, "fileStatus.json"), this);
    }

    /**
     * 从fileStatus.json中读取
     *
     * @param path 所在文件目录
     * @return 录播文件状态
     */
    public static FileStatusModel loadFromFile(String path) {
        return FileStoreUtil.loadFromFile(new File(path, "fileStatus.json"), new TypeReference<FileStatusModel>() {
        });
    }

    public boolean isPost(String platform) {
        return BooleanUtils.isTrue(postMap.get(platform));
    }

    public void postSuccess(String platform) {
        postMap.put(platform, true);
    }

    public void postSuccessAll() {
        Set<String> plats = postMap.keySet();
        for (String plat : plats) {
            postSuccess(plat);
        }
    }


    public boolean allPost() {
        boolean allPost = true;
        for (String platform : platforms) {
            allPost = allPost && isPost(platform);
        }
        return allPost;
    }
}
