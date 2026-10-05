FROM node:22-bookworm-slim AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates && rm -rf /var/lib/apt/lists/*
RUN corepack enable && corepack prepare pnpm@10.27.0 --activate
ARG API_INTERNAL_URL=http://api:4000
ARG NEXT_PUBLIC_PLATFORM_NAME=Saathi
ENV API_INTERNAL_URL=${API_INTERNAL_URL}
ENV NEXT_PUBLIC_PLATFORM_NAME=${NEXT_PUBLIC_PLATFORM_NAME}
COPY . .
RUN pnpm install --frozen-lockfile
RUN pnpm db:generate && pnpm build

FROM node:22-bookworm-slim AS runtime
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates && rm -rf /var/lib/apt/lists/*
RUN corepack enable && corepack prepare pnpm@10.27.0 --activate
COPY --from=build --chown=node:node /app /app
USER node
ENV NODE_ENV=production
EXPOSE 3000 4000
CMD ["pnpm", "--filter", "@saathi/api", "start"]
