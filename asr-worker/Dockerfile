FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MODEL_DIR=/models

# slim 镜像没有 ffmpeg，转码是每个请求的前置步骤
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py transcribe.py ./

# 模型权重的默认落盘目录；部署时挂载卷（compose: ./asr-models:/models）
RUN mkdir -p /models

EXPOSE 8790

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8790"]
