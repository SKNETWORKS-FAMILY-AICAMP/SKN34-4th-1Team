# syntax=docker/dockerfile:1
FROM python:3.13-slim-bookworm AS builder

COPY --from=ghcr.io/astral-sh/uv:0.12.5 /uv /bin/uv
ENV UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential default-libmysqlclient-dev pkg-config \
    && rm -rf /var/lib/apt/lists/*
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

FROM python:3.13-slim-bookworm AS runtime

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libmariadb3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system govbiz && useradd --system --gid govbiz govbiz
COPY --from=builder /app/.venv /app/.venv
COPY --chown=govbiz:govbiz manage.py ./
COPY --chown=govbiz:govbiz config ./config
COPY --chown=govbiz:govbiz apps ./apps
USER govbiz
EXPOSE 8000
# 현재 이미지는 로컬 개발용입니다. 운영 WSGI/ASGI 배포 설정은 별도 구성합니다.
CMD ["python", "manage.py", "runserver", "0.0.0.0:8000"]
