FROM node:22-bookworm-slim AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates && rm -rf /var/lib/apt/lists/*
RUN corepack enable && corepack prepare pnpm@10.27.0 --activate
ARG API_INTERNAL_URL=http://api:4000
ARG NEXT_PUBLIC_PLATFORM_NAME=SWARM
ARG NEXT_PUBLIC_TURNSTILE_SITE_KEY=0x4AAAAAAFQ64yEFzML8_xSE
ENV API_INTERNAL_URL=${API_INTERNAL_URL}
ENV NEXT_PUBLIC_PLATFORM_NAME=${NEXT_PUBLIC_PLATFORM_NAME}
ENV NEXT_PUBLIC_TURNSTILE_SITE_KEY=${NEXT_PUBLIC_TURNSTILE_SITE_KEY}
COPY . .
RUN pnpm install --frozen-lockfile
RUN pnpm db:generate && pnpm build

FROM node:22-bookworm-slim AS runtime
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends openssl ca-certificates ffmpeg && rm -rf /var/lib/apt/lists/*
RUN corepack enable && corepack prepare pnpm@10.27.0 --activate
COPY --from=build --chown=node:node /app /app
RUN mkdir -p /app/.data && chown node:node /app/.data
USER node
ENV NODE_ENV=production
EXPOSE 3000 4000
CMD ["pnpm", "--filter", "@saathi/api", "start"]
