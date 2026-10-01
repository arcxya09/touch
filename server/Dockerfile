FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY server/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home touch && mkdir /data && chown touch:touch /data
COPY server/ .
COPY version.properties .
ARG TOUCH_VERSION=""
ARG TOUCH_BUILD=local
ENV TOUCH_VERSION=$TOUCH_VERSION TOUCH_BUILD=$TOUCH_BUILD
USER touch
ENV DATA_DIR=/data
EXPOSE 8000
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 1 --limit-concurrency 160 --proxy-headers --forwarded-allow-ips '*' --no-access-log"]
