# 前端镜像：Vite 构建 + Nginx 托管（build context = 仓库根目录，才能拿到 deploy/nginx/default.conf）
FROM node:22-alpine AS build
WORKDIR /src/web
RUN corepack enable
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

FROM nginx:1.27-alpine
COPY --from=build /src/web/dist /usr/share/nginx/html
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
