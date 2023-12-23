FROM yijianguanzhu/centos-java-chinese:jre8
MAINTAINER caiwen 'caiwenwqc@163.com'

VOLUME /tmp

WORKDIR /home/admin/stream
ENV APP_HOME=/home/admin/stream

# 创建应用文件夹并移动应用jar包, 将一些获取请求的安全证书上传到security文件佳
COPY sh-config/src/main/resources/config/init.json init.json
COPY sh-config/src/main/resources/config/streamer.json streamer.json
COPY sh-start/target/sh-start-1.0-SNAPSHOT.jar stream-record.jar
COPY sh-start/target/classes/jssecacerts jssecacerts

RUN mkdir -p ${APP_HOME}/download && \
    mkdir -p ${APP_HOME}/logs && \
    mv ${APP_HOME}/jssecacerts /usr/java/latest/lib/security


## 运行jar包
ENTRYPOINT ["sh", "-c", "nohup java -Dfile.encoding=utf-8 -Xms512m -Xmx512m -jar /home/admin/stream/stream-record.jar"]
#ENTRYPOINT ["nohup", "java", "-Dfile.encoding=utf-8", "-Xms512m", "-Xmx512m", "-Xmn190m", "-Xss128k", "-jar",
#"/home/admin/sh2/stream-record.jar"]