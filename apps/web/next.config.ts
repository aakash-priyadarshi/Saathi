import type { NextConfig } from 'next';
const config: NextConfig = {
  transpilePackages: ['@saathi/types', '@saathi/ui', '@saathi/protocol', '@saathi/validation'],
  async rewrites() {
    return [
      {
        source: '/api/v1/public/reports',
        destination: `${process.env.API_INTERNAL_URL ?? 'http://localhost:4000'}/api/v1/public/reports`,
      },
      {
        source: '/api/v1/public/:path*',
        destination: `${process.env.PUBLIC_API_INTERNAL_URL ?? process.env.API_INTERNAL_URL ?? 'http://localhost:4000'}/api/v1/public/:path*`,
      },
      {
        source: '/api/:path*',
        destination: `${process.env.API_INTERNAL_URL ?? 'http://localhost:4000'}/api/:path*`,
      },
    ];
  },
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'Referrer-Policy', value: 'no-referrer' },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'Permissions-Policy', value: 'camera=(self), microphone=(self), geolocation=()' },
          {
            key: 'Content-Security-Policy',
            value:
              "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: http://localhost:9000 https:; media-src 'self' http://localhost:9000 https:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
          },
        ],
      },
    ];
  },
};
export default config;
