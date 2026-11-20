import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// `npm run dev` serves on :5173 and forwards the API to the Kotlin server on :8080.
// `npm run build` writes web/dist, which the server serves at / (one port for everything).
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080', '/mcp': 'http://localhost:8080' } },
})
