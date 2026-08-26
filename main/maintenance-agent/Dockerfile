ARG PYTHON_BASE_IMAGE=docker.m.daocloud.io/library/python:3.12.11-slim-bookworm
FROM ${PYTHON_BASE_IMAGE}

ARG PIP_INDEX_URL=https://pypi.org/simple

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN groupadd --system maintenance && useradd --system --gid maintenance --home /app maintenance
WORKDIR /app
COPY --chown=maintenance:maintenance requirements.txt ./
RUN python -m pip install --no-cache-dir --timeout 120 --retries 5 \
    --index-url "${PIP_INDEX_URL}" -r requirements.txt
COPY --chown=maintenance:maintenance app.py ./
COPY --chown=maintenance:maintenance cordis ./cordis
USER maintenance
EXPOSE 8010
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8010/health', timeout=3).read()"]
CMD ["python", "app.py"]
