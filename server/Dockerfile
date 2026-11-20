# Builds the Strategos server (and the web UI) into one image. Context: the repo root.
FROM node:20-alpine AS web
WORKDIR /web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM gradle:8.7-jdk17 AS build
WORKDIR /src
COPY . .
# The server module compiles the engine from app/src/main/kotlin/{domain,framework}; the Android module is not built.
RUN gradle :server:installDist --no-daemon -x test -Pstrategos.serverOnly=true

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server /app
COPY --from=web /web/dist /app/web
EXPOSE 8080
CMD ["/app/bin/server"]
