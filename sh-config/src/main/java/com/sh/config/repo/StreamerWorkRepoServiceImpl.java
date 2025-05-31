package com.sh.config.repo;

import com.sh.config.mapper.StreamerWorkMapper;
import com.sh.config.model.dao.StreamerWorkDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2025 05 31 09 52
 **/
@Slf4j
@Service
public class StreamerWorkRepoServiceImpl implements StreamerWorkRepoService {
    @Resource
    private StreamerWorkMapper streamerWorkMapper;

    @Override
    public StreamerWorkDO getByName(String name) {
        return streamerWorkMapper.selectByName(name);
    }

    @Override
    public List<StreamerWorkDO> getByNames(List<String> names) {
        return null;
    }
}
