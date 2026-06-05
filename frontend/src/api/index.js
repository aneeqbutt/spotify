/**
 * api/index.js — Centralised API calls
 *
 * Every function returns the response data directly (not the axios response).
 * Errors propagate — callers handle them with try/catch.
 */

import client from './client'

// ── Auth ─────────────────────────────────────────────────────────────────────

export const login = (username, password) =>
  client.post('/auth/login', { username, password }).then(r => r.data)

export const register = (username, password) =>
  client.post('/auth/register', { username, password }).then(r => r.data)

export const getMe = () =>
  client.get('/auth/me').then(r => r.data)

// ── Devices ──────────────────────────────────────────────────────────────────

export const listDevices = () =>
  client.get('/devices/').then(r => r.data)

export const getDevice = (deviceId) =>
  client.get(`/devices/${deviceId}`).then(r => r.data)

export const deleteDevice = (deviceId) =>
  client.delete(`/devices/${deviceId}`).then(r => r.data)

// ── Tasks ─────────────────────────────────────────────────────────────────────

export const listTasks = () =>
  client.get('/tasks/').then(r => r.data)

export const createTask = (payload) =>
  client.post('/tasks/', payload).then(r => r.data)

export const deleteTask = (taskId) =>
  client.delete(`/tasks/${taskId}`)

// ── Commands ──────────────────────────────────────────────────────────────────

export const runNow = (taskId, deviceId) =>
  client.post('/commands/run', { task_id: taskId, device_id: deviceId }).then(r => r.data)

export const getRun = (runId) =>
  client.get(`/commands/runs/${runId}`).then(r => r.data)

export const listRuns = () =>
  client.get('/commands/runs').then(r => r.data)

// ── Sessions ──────────────────────────────────────────────────────────────────

export const createSession = (payload) =>
  client.post('/sessions/', payload).then(r => r.data)

export const listSessions = () =>
  client.get('/sessions/').then(r => r.data)

export const stopSession = (sessionId) =>
  client.post(`/sessions/${sessionId}/stop`).then(r => r.data)
