FROM python:3.13-slim
RUN pip install --no-cache-dir docker==7.1.0
WORKDIR /app
COPY controller.py maintenance.py status.py rcon.py /app/
COPY loadtest /app/loadtest/
ENV PYTHONUNBUFFERED=1
CMD ["python", "/app/controller.py"]
