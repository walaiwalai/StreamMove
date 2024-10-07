FROM stream-base:latest
MAINTAINER caiwen 'caiwenwqc@163.com'

VOLUME /tmp

WORKDIR /home/admin/stream
ENV APP_HOME=/home/admin/stream

#COPY sh-config/target/classes/config/init.json ${APP_HOME}/init.json
#COPY sh-config/target/classes/config/streamer.json ${APP_HOME}/streamer.json
#COPY sh-start/target/sh-start-1.0-SNAPSHOT.jar ${APP_HOME}/sh-start-1.0-SNAPSHOT.jar
#
#RUN mkdir -p ${APP_HOME}/download && \
#    mkdir -p ${APP_HOME}/logs && \
#    mkdir -p ${APP_HOME}/dump && \
#    mkdir -p ${APP_HOME}/account && \
#    mkdir -p ${APP_HOME}/thumbnail

# 运行 jar 包
ENTRYPOINT ["java", "-Dfile.encoding=utf-8", "-Duser.timezone=GMT+08", "-Dspring.profiles.active=prod", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/home/admin/stream/dump/", "-Xms512m", "-Xmx512m", "-jar", "/home/admin/stream/sh-start-1.0-SNAPSHOT.jar"]