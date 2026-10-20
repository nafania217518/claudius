import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  // M1: proxy the browser's /api calls to the Spring backend (WebFlux on 8080).
  server: {
    // Allow the Docker-based MCP browser (reaches us via host.docker.internal).
    allowedHosts: ['host.docker.internal'],
    proxy: {
      // If the backend is down, fail fast instead of hanging the dev server:
      // socket inactivity + outgoing-request timeouts, and log proxy errors.
      '/api': {
        // CLAUDIUS_API picks another backend, e.g. when Docker holds 8080.
        target: process.env.CLAUDIUS_API ?? 'http://localhost:8080',
        changeOrigin: true,
        // M12: proxy the /api/ws WebSocket too.
        ws: true,
        // 10 min: a cold model can sit >30 s between SSE chunks; 30 s cut the stream.
        timeout: 600_000,
        proxyTimeout: 600_000,
        configure: (proxy) => {
          proxy.on('error', (err) => {
            console.error('[proxy] /api → backend:', err.message)
          })
        },
      },
    },
  },
})
