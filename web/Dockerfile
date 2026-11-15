# The API address is baked in at build time, because Next.js inlines NEXT_PUBLIC_ variables.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
RUN npm run build && npm prune --omit=dev

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules node_modules
COPY --from=build /app/.next .next
COPY --from=build /app/public public
USER node
EXPOSE 3000
CMD ["node_modules/.bin/next", "start", "-p", "3000"]
