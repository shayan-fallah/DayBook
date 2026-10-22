FROM python:3.12-slim
WORKDIR /app
COPY app.py foods.csv food_units.csv ./
COPY static ./static
ENV HOST=0.0.0.0 PORT=8080 DATA_DIR=/data PYTHONUNBUFFERED=1
RUN useradd --system --uid 1000 tracker && mkdir -p /data && chown tracker /data
USER tracker
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/api/me', timeout=4)"
CMD ["python3", "app.py"]
