import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
// NovaPanel env vars (optional, set at build time or via .env):
//   VITE_API_URL  - REST API base URL (default: /api, same-origin)
//   VITE_WS_URL   - WebSocket URL (default: derived from window.location, port 8889)
export default defineConfig({
  plugins: [react()],
})
