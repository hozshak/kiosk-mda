FROM node:20-alpine

WORKDIR /app

# Build-Tools für better-sqlite3 (native build)
RUN apk add --no-cache python3 make g++

COPY package.json ./
RUN npm install --omit=dev

COPY src ./src
COPY public ./public

# better-sqlite3 muss für Alpine kompiliert sein
RUN npm rebuild better-sqlite3

ENV PORT=8989
ENV HOST=0.0.0.0
ENV DATA_DIR=/data
EXPOSE 8989
VOLUME ["/data"]

CMD ["node", "src/server.js"]
