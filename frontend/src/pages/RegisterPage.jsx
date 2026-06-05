import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { register } from '../api'

export default function RegisterPage() {
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError]       = useState('')
  const [loading, setLoading]   = useState(false)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await register(username, password)
      navigate('/login')
    } catch (err) {
      setError(err.response?.data?.detail || 'Registration failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-wrap">
      <div className="login-box">
        <h1 className="login-title" style={{ color: 'var(--green)' }}>🤖 SpotifyBot</h1>
        <p className="login-sub">Create your account</p>

        {error && <div className="alert alert-error">{error}</div>}

        <form onSubmit={handleSubmit} className="col">
          <div className="form-group">
            <label>Username</label>
            <input className="form-control" type="text" value={username}
              onChange={e => setUsername(e.target.value)} placeholder="admin" required autoFocus />
          </div>
          <div className="form-group">
            <label>Password</label>
            <input className="form-control" type="password" value={password}
              onChange={e => setPassword(e.target.value)} placeholder="••••••••" required />
          </div>
          <button type="submit" className="btn btn-green mt-8" disabled={loading} style={{ width: '100%' }}>
            {loading ? 'Creating account…' : 'Create account'}
          </button>
        </form>

        <p style={{ textAlign: 'center', marginTop: 16, color: 'var(--muted)', fontSize: 13 }}>
          Already have an account? <a href="/login">Sign in</a>
        </p>
      </div>
    </div>
  )
}
