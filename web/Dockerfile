FROM node:22-alpine
WORKDIR /app
COPY . .
ENV NODE_ENV=production PORT=8080
EXPOSE 8080
# jízdní řády se při prvním startu stáhnou a pak se každých 12 h obnovují
USER node
CMD ["node", "server.mjs"]
