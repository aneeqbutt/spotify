import axios from 'axios'

// Resolve the backend base URL dynamically.
//
// When the frontend is accessed from a phone, tablet, or another machine on
// the same LAN (e.g. http://192.168.1.4:5173), the env var still says
// "localhost:8000" which is unreachable from that machine.  Instead, use the
// hostname the browser used to reach this page — it is always the machine
// running the backend — and append the backend port.
//
// Explicit VITE_API_BASE_URL overrides this (useful for a dedicated deploy).
function resolveApiBase() {
  const envUrl = import.meta.env.VITE_API_BASE_URL
  if (envUrl && !envUrl.includes('localhost') && !envUrl.includes('127.0.0.1')) {
    return envUrl  // explicitly set to a non-localhost address — trust it
  }
  // Auto-derive from the page's hostname so any machine on the LAN works
  const host = window.location.hostname
  const port = 8000
  return `http://${host}:${port}`
}

const client = axios.create({
  baseURL: resolveApiBase(),
})

// Attach JWT token to every request automatically
client.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// On 401 — token expired, redirect to login
client.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('access_token')
      window.location.href = '/login'
    }
    return Promise.reject(err)
  }
)

export default client
