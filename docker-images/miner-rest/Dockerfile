FROM maven:3.6.0-jdk-11-slim
ENV HOME=/home/usr/app
RUN mkdir -p $HOME
WORKDIR $HOME
ADD ./miner/pom.xml $HOME
RUN ["/usr/local/bin/mvn-entrypoint.sh", "mvn", "verify", "clean", "--fail-never"]
ADD ./miner $HOME
RUN ["mvn", "package"]
EXPOSE 8080
CMD ["java", "-jar", "./target/docker-app.jar"]