FROM node:22-slim
WORKDIR /app
COPY package*.json ./
RUN npm install --ignore-scripts
COPY tsconfig.json src ./
RUN npm run build
EXPOSE 8787
CMD ["node", "dist/server.js"]
