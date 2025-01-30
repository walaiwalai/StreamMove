package com.sh.config.mapper;

/**
 * @Author : caiwen
 * @Date: 2025/1/30
 */

import com.sh.config.model.dao.StreamerDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface StreamerMapper {
    /**
     * 根据名称查询流媒体信息
     *
     * @param name 流媒体名称
     * @return 流媒体信息
     */
    StreamerDO selectByName( @Param("name") String name );

    /**
     * 根据name批量查询
      * @param names
     * @return
     */
    List<StreamerDO> batchSelectByName( @Param("names") List<String> names );

    /**
     * 插入新的流媒体信息
     *
     * @param streamerDO 流媒体信息对象
     */
    void insert( StreamerDO streamerDO );

    /**
     * 根据名称更新流媒体信息
     *
     * @param streamerDO 流媒体信息对象
     */
    void updateByName( StreamerDO streamerDO );

    void updateLastRecordTime( @Param("name") String name, @Param("lastRecordTime") Date lastRecordTime );

    /**
     * 根据名称删除流媒体信息
     *
     * @param name 流媒体名称
     */
    void deleteByName( @Param("name") String name );
}

