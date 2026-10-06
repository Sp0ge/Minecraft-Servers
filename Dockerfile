FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY start.py authme_config.py map_install.py /opt/
ENV ROLE=lobby
WORKDIR /data
ENTRYPOINT ["python3", "/opt/start.py"]
