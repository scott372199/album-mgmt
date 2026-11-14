# 前端构建阶段：Vite 把 React 控制台（登录 + 七域）打成静态资源。
# 生产镜像以前只打包 jar，导致 cx.chenyouyi.top 根路径 404、没有登录页和 UI。
# Frontend stage: Vite builds the React console (login + seven domains).
# The image used to ship only the jar, so the root path returned 404 and no UI existed.
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY app/build/libs/*.jar app.jar

# 与 API 同一 origin 提供登录页与七域 UI；Spring 的 /api/** Controller
# 优先级高于静态资源，/actuator/** 同理，互不冲突。
# Serve the UI from the same origin as the API; Spring's /api/** controllers
# and /actuator/** take precedence over static resources.
COPY --from=web /web/dist/ /app/static/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.web.resources.static-locations=file:/app/static/"]
