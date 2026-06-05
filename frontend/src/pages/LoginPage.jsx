/**
 * LoginPage.jsx
 *
 * Renders a centered login form.
 * On success: stores JWT in localStorage → navigates to /dashboard.
 * On load: if token already exists → redirect immediately (already logged in).
 */

import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { login } from '../api'

export default function LoginPage() {
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError]       = useState('')
  const [loading, setLoading]   = useState(false)

  // Already logged in? Skip straight to dashboard
  useEffect(() => {
    if (localStorage.getItem('access_token')) {
      navigate('/dashboard', { replace: true })
    }
  }, [navigate])

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const data = await login(username, password)
      localStorage.setItem('access_token', data.access_token)
      navigate('/dashboard', { replace: true })
    } catch (err) {
      setError(err.response?.data?.detail || 'Login failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-wrap">
      <div className="login-box">
        {/* Header */}
        <h1 className="login-title" style={{ color: 'var(--green)' }}>🤖 SpotifyBot</h1>
        <p className="login-sub">Spotify Automation Platform</p>

        {/* Error banner */}
        {error && <div className="alert alert-error">{error}</div>}

        {/* Form */}
        <form onSubmit={handleSubmit} className="col">
          <div className="form-group">
            <label>Username</label>
            <input
              className="form-control"
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              placeholder="admin"
              required
              autoFocus
            />
          </div>

          <div className="form-group">
            <label>Password</label>
            <input
              className="form-control"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder="••••••••"
              required
            />
          </div>

          <button
            type="submit"
            className="btn btn-green mt-8"
            disabled={loading}
            style={{ width: '100%' }}
          >
            {loading ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p style={{ textAlign: 'center', marginTop: 16, color: 'var(--muted)', fontSize: 13 }}>
          No account?{' '}
          <a href="/register">Register</a>
        </p>
      </div>
    </div>
  )
}
