FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
COPY python-agent/pyproject.toml ./pyproject.toml
COPY python-agent/src ./src
RUN pip install --no-cache-dir . && useradd --uid 10001 --no-create-home jusika
USER 10001:10001
EXPOSE 8000
# Only the fixed proxy IP may set forwarded protocol/client headers. Never trust '*'.
CMD ["uvicorn", "jusika_agent.app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--proxy-headers", "--forwarded-allow-ips", "172.30.91.2", "--no-access-log", "--limit-concurrency", "16"]
