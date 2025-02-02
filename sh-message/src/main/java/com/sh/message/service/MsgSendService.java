package com.sh.message.service;

import java.io.File;

/**
 * @Author caiwen
 * @Date 2023 12 24 22 44
 **/
public interface MsgSendService {
    void sendText(String message);

    void sendImage(File imageFile);

//    void sendTextToUser( String userId, String message );
}
