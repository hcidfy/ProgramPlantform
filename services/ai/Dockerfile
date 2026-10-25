# ai 编排服务镜像（PRD §7.3：单 worker，≤300MB）
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    TZ=Asia/Shanghai

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# 只监听容器网络，不映射到宿主机（PRD §15.1 D6）
EXPOSE 8080

# 单 worker：不加载 embedding 模型，内存预算 300MB 以内
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--timeout-keep-alive", "30"]
