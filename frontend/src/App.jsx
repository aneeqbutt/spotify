/**
 * App.jsx — Router root
 *
 * Routes:
 *   /           → redirect to /login
 *   /login      → LoginPage
 *   /register   → RegisterPage
 *   /dashboard  → DashboardPage (requires token — handled inside the page)
 */

import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import LoginPage     from './pages/LoginPage'
import RegisterPage  from './pages/RegisterPage'
import DashboardPage from './pages/DashboardPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/"          element={<Navigate to="/login" replace />} />
        <Route path="/login"     element={<LoginPage />} />
        <Route path="/register"  element={<RegisterPage />} />
        <Route path="/dashboard" element={<DashboardPage />} />
      </Routes>
    </BrowserRouter>
  )
}
