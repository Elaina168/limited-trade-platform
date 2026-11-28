FROM node:24.13.0-bookworm-slim AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts ./
COPY frontend/src/ ./src/
COPY frontend/public/ ./public/
RUN npm run build

FROM nginx:1.30.7-alpine@sha256:caf4e1e032a20f9c3c5e28fc7f25ffd768ad8677b4975c54193075997b3a97e3

# 部署只读页面产物与共用代理配置，写入路径由 Nginx 容器管理。
COPY --from=build /build/dist/ /usr/share/nginx/html/
COPY deploy/nginx/http-common.conf /etc/nginx/trade/nginx/http-common.conf
COPY deploy/nginx-vue/api-proxy.conf deploy/nginx-vue/vue-http.conf deploy/nginx-vue/vue-server.conf /etc/nginx/trade/nginx-vue/
COPY docker/nginx/nginx.conf /etc/nginx/trade/nginx-vue/nginx.conf
RUN mkdir -p /etc/nginx/trade/nginx-vue/logs \
    && ln -s /dev/stdout /etc/nginx/trade/nginx-vue/logs/access.log

CMD ["nginx", "-p", "/etc/nginx/trade/nginx-vue/", "-c", "nginx.conf", "-g", "daemon off;"]
