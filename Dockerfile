FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY start.py result_hooks.py /opt/
ENV ROLE=pvp
WORKDIR /data
ENTRYPOINT ["python3", "/opt/start.py"]
