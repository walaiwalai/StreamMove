package com.sh.config.repo;

import com.sh.config.model.dao.StreamerWorkDO;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2025 05 31 09 51
 **/
public interface StreamerWorkRepoService {
    StreamerWorkDO getByName(String name);

    List<StreamerWorkDO> getByNames(List<String> names);
}
