FROM node:22-slim
WORKDIR /app
COPY package*.json ./
# 镜像构建时 registry 偶发 504，因此加大重试与超时
RUN npm config set fetch-retries 8 \
 && npm config set fetch-retry-mintimeout 5000 \
 && npm config set fetch-retry-maxtimeout 180000 \
 && npm config set fetch-timeout 600000 \
 && npm install --ignore-scripts --registry=https://registry.npmmirror.com
COPY tsconfig.json ./
COPY src ./src
RUN npm run build
EXPOSE 8787
CMD ["node", "dist/server.js"]
