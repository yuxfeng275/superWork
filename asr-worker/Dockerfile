FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MODEL_DIR=/models

# 构建期可替换源（默认官方源，不传参即维持原行为）：
#   APT_MIRROR           Debian 镜像主机名，如 mirrors.aliyun.com（国内慢链路）
#   PIP_INDEX_URL        PyPI 索引，如 https://mirrors.aliyun.com/pypi/simple/
#   PIP_EXTRA_INDEX_URL  torch CPU 轮子索引（默认 pytorch 官方）
#   PIP_FIND_LINKS       本地 wheelhouse 目录（配合 COPY wheels/，用于慢链路下预置 torch/torchaudio）
ARG APT_MIRROR=""
ARG PIP_INDEX_URL="https://pypi.org/simple"
ARG PIP_EXTRA_INDEX_URL="https://download.pytorch.org/whl/cpu"
ARG PIP_FIND_LINKS=""

# slim 镜像没有 ffmpeg，转码是每个请求的前置步骤
RUN if [ -n "$APT_MIRROR" ]; then \
      for f in /etc/apt/sources.list /etc/apt/sources.list.d/debian.sources; do \
        [ -f "$f" ] && sed -i "s|deb.debian.org|$APT_MIRROR|g; s|security.debian.org|$APT_MIRROR|g" "$f"; \
      done; \
    fi \
    && apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt ./
# wheels/ 为可选离线 wheelhouse（仓库内仅占位 .gitkeep）；requirements 里的官方 extra-index 由构建参数接管
COPY wheels/ /wheels/
RUN sed '/^--extra-index-url/d' requirements.txt > requirements.docker.txt \
    && PIP_EXTRA=""; [ -n "$PIP_FIND_LINKS" ] && PIP_EXTRA="--find-links=$PIP_FIND_LINKS"; \
       pip install --no-cache-dir -r requirements.docker.txt \
         --index-url "$PIP_INDEX_URL" --extra-index-url "$PIP_EXTRA_INDEX_URL" $PIP_EXTRA

COPY app.py transcribe.py ./

# 模型权重的默认落盘目录；部署时挂载卷（compose: ./asr-models:/models）
RUN mkdir -p /models

EXPOSE 8790

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8790"]
