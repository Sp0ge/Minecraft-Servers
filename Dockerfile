FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY start.py season.py /opt/
ENV ROLE=pvp
WORKDIR /data
ENTRYPOINT ["python3", "/opt/start.py"]
