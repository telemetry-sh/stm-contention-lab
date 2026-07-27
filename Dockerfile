FROM clojure:temurin-21-tools-deps-1.12.5.1654-alpine

RUN addgroup -S -g 10001 lab \
    && adduser -S -D -H -u 10001 -G lab lab

WORKDIR /app
ENV HOME=/app
RUN chown 10001:10001 /app
USER 10001:10001

COPY --chown=10001:10001 deps.edn ./
RUN clojure -P

COPY --chown=10001:10001 src ./src
COPY --chown=10001:10001 public ./public

ENV HOST=0.0.0.0
ENV PORT=8080
ENV PUBLIC_DIR=public
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=2s --start-period=8s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["clojure"]
CMD ["-M:run"]
