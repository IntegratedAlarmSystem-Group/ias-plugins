FROM centos:7

# Install java and netcat
RUN yum update -y && \
  yum install java-1.8.0-openjdk-devel \
  wget \
  nmap-ncat \
  -y
ENV JAVA_HOME /usr/lib/jvm/java-1.8.0-openjdk

# Download plugin artifact
WORKDIR /usr/src/alma_weather_plugin
ARG version=dev
RUN echo "Using version = "${version}
RUN wget -qO /usr/src/alma_weather_plugin/alma-weather-station-plugin.jar http://artifactory.inria.cl:8081/artifactory/inria-maven/org/eso/ias/alma-weather-station-plugin/${version}/alma-weather-station-plugin-${version}.jar

# Add config file
COPY config.json /usr/src/alma_weather_plugin/

# Set up entrypoint
COPY entrypoint.sh /usr/src/alma_weather_plugin
ENTRYPOINT ["./entrypoint.sh"]
