# 1단계: Vite로 정적 파일 빌드 (API 주소는 비워 두어 같은 도메인의 /api로 요청한다)
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# 2단계: Nginx가 정적 파일을 제공하고 /api 는 백엔드 컨테이너로 넘긴다
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
