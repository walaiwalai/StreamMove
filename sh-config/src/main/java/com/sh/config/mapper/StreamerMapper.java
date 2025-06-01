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
    StreamerDO selectByName(@Param("name") String name);

    /**
     * 根据env批量查询
     *
     * @param env
     * @return
     */
    List<StreamerDO> batchSelectByEnv(@Param("env") String env);

    /**
     * 更新最近上传一件
     *
     * @param name 流媒体信息
     * @param lastRecordTime 最近上传时间
     */
    void updateLastRecordTime(@Param("name") String name, @Param("lastRecordTime") Date lastRecordTime);

    /**
     * 更新流量耗费
     *
     * @param name 流媒体信息
     * @param trafficBG 当前流量耗费
     */
    void updateTrafficGB(@Param("name") String name, @Param("trafficBG") Float trafficBG);

    /**
     * 根据名称删除流媒体信息
     *
     * @param name 流媒体名称
     */
    void deleteByName(@Param("name") String name);
}

