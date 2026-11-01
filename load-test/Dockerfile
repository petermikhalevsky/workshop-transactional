# Минимальный образ для нагрузки: alpine + bash + curl.
# Собирается отдельно от приложения: docker compose --profile load up -d --build load-generator
FROM alpine:3.20

RUN apk add --no-cache bash curl coreutils

WORKDIR /load-test
COPY load.sh /load-test/load.sh
RUN chmod +x /load-test/load.sh

# ENTRYPOINT не задаём: команду передаёт docker compose (sh -c ...),
# а ручной запуск — docker compose exec load-generator /load-test/load.sh 300 20
