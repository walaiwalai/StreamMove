package com.sh.config.mapper;

import com.sh.config.model.dao.CommonParamDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommonParamMapper {
    @Select("SELECT id, gmt_create AS gmtCreate, gmt_modified AS gmtModified, "
            + "`key`, `value`, env FROM stream_common_param WHERE env = #{env}")
    List<CommonParamDO> selectByEnv(@Param("env") String env);

    @Select("SELECT id, gmt_create AS gmtCreate, gmt_modified AS gmtModified, "
            + "`key`, `value`, env FROM stream_common_param")
    List<CommonParamDO> selectAll();
}
