package com.sh.config.mapper;

import com.sh.config.model.dao.StreamerWorkDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Author caiwen
 * @Date 2025 05 31 09 42
 **/
@Mapper
public interface StreamerWorkMapper {
    /**
     * 根据名称查询流媒体信息
     *
     * @param name 流媒体名称
     * @return 流媒体信息
     */
    StreamerWorkDO selectByName(@Param("name") String name);

    /**
     * 根据names批量查询
     *
     * @param names
     * @return
     */
    List<StreamerWorkDO> batchSelect(@Param("names") List<String> names);
}
