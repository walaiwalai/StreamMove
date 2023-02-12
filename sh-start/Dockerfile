FROM hub.deri.org.cn/library/oracle_jdk_1.8_251:latest
MAINTAINER caiwen 'caiwenwqc@163.com'

VOLUME /tmp

WORKDIR /home/admin/sh2
ENV APP_HOME=/home/admin/sh2

# 创建应用文件夹并移动应用jar包, 将一些获取请求的安全证书上传到security文件佳
COPY target/sh-start-1.0-SNAPSHOT.jar stream-move.jar
COPY target/classes/jssecacerts jssecacerts

#ADD ../../../target/sh-start-1.0-SNAPSHOT.jar stream-move.jar
#ADD ../classes/jssecacerts jssecacerts

RUN mkdir -p ${APP_HOME}/download && \
    mkdir -p ${APP_HOME}/logs && \
    touch ${APP_HOME}/info.json && \
    mv ${APP_HOME}/jssecacerts /usr/java/jdk/lib/security


# 运行jar包
ENTRYPOINT ["nohup", "java", "-Dfile.encoding=utf-8", "-jar","/home/admin/sh2/stream-move.jar"]