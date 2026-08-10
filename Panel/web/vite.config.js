import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
// NovaPanel env vars (optional, set at build time or via .env):
//   VITE_API_URL  - REST API base URL (default: /api, same-origin)
//   VITE_WS_URL   - WebSocket URL (default: derived from window.location, port 8889)
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Dev proxy: forward same-origin /api + /ws to the NovaLink backend on :8889.
    // auth.js + api.js use same-origin /api, so the proxy makes login work in dev
    // without hard-coding the backend host. The /ws proxy also lets the WS client
    // (which derives ws://localhost:5173 from window.location in dev) reach the
    // backend's WS endpoint at 0.0.0.0:8889/ws.
    proxy: {
      '/api': { target: 'http://localhost:8889', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8889', ws: true, changeOrigin: true },
    },
  },
})
