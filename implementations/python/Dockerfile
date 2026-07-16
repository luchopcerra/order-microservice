FROM python:3.12-slim
WORKDIR /app
RUN useradd --system --create-home app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
USER app
EXPOSE 8080
CMD ["python", "-m", "app.main"]
