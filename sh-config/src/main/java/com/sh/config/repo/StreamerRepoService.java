package com.sh.config.repo;

import com.sh.config.model.config.StreamerConfig;

import java.util.Date;
import java.util.List;

/**
 * @Author : caiwen
 * @Date: 2025/1/30
 */
public interface StreamerRepoService {
    StreamerConfig getByName( String name );

    List<StreamerConfig> getByEnv( String env );

    void updateByName( String name, StreamerConfig updated );

    void updateLastRecordTime( String name, Date lastRecordTime );

    void deleteByName( String name );

    void insert( StreamerConfig streamer, String env );
}
