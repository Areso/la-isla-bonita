FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :server:installDist :client:installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home isla \
    && mkdir /data && chown isla /data
COPY --from=build /src/server/build/install/server /opt/isla
# CLI client, for `kubectl exec -it <pod> -- isla` (defaults to localhost:8080)
COPY --from=build /src/client/build/install/client /opt/isla-client
RUN ln -s /opt/isla-client/bin/client /usr/local/bin/isla
ENV HOST=0.0.0.0 \
    PORT=8080 \
    ISLA_DB=/data/game.db
# The client shares the pod's memory limit with the server; keep it small
ENV CLIENT_OPTS=-Xmx64m
USER 10001
WORKDIR /data
EXPOSE 8080
ENTRYPOINT ["/opt/isla/bin/server"]
