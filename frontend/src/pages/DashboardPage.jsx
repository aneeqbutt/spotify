/**
 * DashboardPage.jsx — Main control center
 *
 * Sections:
 * 1. Device status panel — shows online/offline for each registered device
 * 2. Create task form    — name, action type, search query
 * 3. Task list           — saved tasks with Run Now button per task
 * 4. Live run log        — step events polled every 2s until COMMAND_DONE
 *
 * Error handling:
 * - All API calls wrapped in try/catch with user-visible error messages
 * - Delete task: confirmation dialog + error banner if it fails
 * - Run Now: shows device-offline error inline
 * - 401 handled globally in axios interceptor (auto-redirect to /login)
 * - TIMED_OUT run status displayed with correct badge colour
 */

import { useState, useEffect, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { getMe, listDevices, getDevice, listTasks, createTask, deleteTask, runNow, getRun,
         listSessions, createSession, stopSession, deleteDevice } from '../api'

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Parse a UTC datetime string from the API and display it in the user's
 * local timezone.  The backend sends "2026-06-02T05:00:00Z" (Z-suffixed).
 * Without the Z, browsers interpret the string as local time and show the
 * wrong hour to anyone not in UTC.
 */
function fmtSessionTime(iso) {
  if (!iso) return '—'
  // Ensure we always treat the string as UTC before localising
  const s = iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z'
  return new Date(s).toLocaleString(undefined, {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

/**
 * Returns the current local time in YYYY-MM-DDTHH:MM format for datetime-local inputs.
 * Uses local-time getters (getFullYear etc.) directly — no UTC offset math that
 * produces wrong results in non-UTC timezones.
 */
function nowLocalIso() {
  const d   = new Date()
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** Cap datetime-local inputs at 1 year from today to prevent accidental far-future years. */
function maxLocalIso() {
  const d = new Date()
  d.setFullYear(d.getFullYear() + 1)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T23:59`
}

// ── Action types ──────────────────────────────────────────────────────────────
const ACTION_TYPES = [
  { value: 'SEARCH_AND_PLAY',    label: 'Search & Play',        needsQuery: true  },
  { value: 'LIKE_CURRENT_TRACK', label: 'Like Current Track',   needsQuery: false },
  { value: 'FOLLOW_ARTIST',      label: 'Follow Artist',        needsQuery: true  },
  { value: 'SKIP_TRACK',         label: 'Skip Track',           needsQuery: false },
  { value: 'PLAY_FROM_ALBUM',    label: 'Play Album',           needsQuery: true  },
  { value: 'PLAY_FROM_PLAYLIST', label: 'Play Playlist',        needsQuery: true  },
{ value: 'FOLLOW_PLAYLIST',    label: 'Follow Playlist',      needsQuery: true  },
  { value: 'ADD_TO_PLAYLIST',    label: 'Add to Playlist',      needsQuery: true  },
  { value: 'CREATE_PLAYLIST',    label: 'Create Playlist',      needsQuery: true  },
]

// ── Status badge ──────────────────────────────────────────────────────────────
function StatusBadge({ status }) {
  const map = {
    online:     'badge-green',
    offline:    'badge-red',
    RUNNING:    'badge-yellow',
    SUCCESS:    'badge-green',
    FAILED:     'badge-red',
    TIMED_OUT:  'badge-red',
    scheduled:  'badge-muted',
    running:    'badge-yellow',
    done:       'badge-muted',
    failed:     'badge-red',
  }
  return <span className={`badge ${map[status] || 'badge-muted'}`}>{status}</span>
}

// ── Log line ──────────────────────────────────────────────────────────────────
function LogLine({ event }) {
  const classMap = {
    STEP_STARTED: 'log-start',
    STEP_OK:      'log-ok',
    STEP_FAILED:  'log-fail',
    COMMAND_DONE: 'log-done',
  }
  const time = new Date(event.timestamp).toLocaleTimeString()
  return (
    <div>
      <span className="log-time">{time}</span>
      <span className={classMap[event.event_type] || ''}>
        [{event.event_type}] {event.step_name || event.step_id || ''}
        {event.reason_code ? ` — ${event.reason_code}` : ''}
      </span>
    </div>
  )
}

// ── Toast notification ────────────────────────────────────────────────────────
// Fix 4: success feedback when a task is saved
function Toast({ message, onDone }) {
  useEffect(() => {
    const t = setTimeout(onDone, 2500)
    return () => clearTimeout(t)
  }, [onDone])
  return (
    <div style={{
      position: 'fixed', bottom: 24, right: 24, zIndex: 999,
      background: 'var(--green)', color: '#000',
      padding: '10px 20px', borderRadius: 8, fontWeight: 600,
      boxShadow: '0 4px 12px rgba(0,0,0,0.4)',
      animation: 'fadeIn 0.2s ease',
    }}>
      {message}
    </div>
  )
}

// ── Main dashboard ────────────────────────────────────────────────────────────
export default function DashboardPage() {
  const navigate = useNavigate()

  // Data
  const [user, setUser]       = useState(null)
  const [devices, setDevices] = useState([])
  const [tasks, setTasks]     = useState([])

  // Debounce timers for offline status — prevents dashboard flicker on brief disconnects
  const offlineTimers = useRef({})

  // Task form
  const [taskName, setTaskName]       = useState('')
  const [actionType, setActionType]   = useState('SEARCH_AND_PLAY')
  const [searchQuery, setSearchQuery] = useState('')
  const [formError, setFormError]     = useState('')
  const [formLoading, setFormLoading] = useState(false)
  const [toast, setToast]             = useState('')         // Fix 4

  // Action types that keep music playing after COMMAND_DONE — need a play-duration wait
  const PLAYBACK_ACTIONS = new Set(['SEARCH_AND_PLAY', 'PLAY_FROM_ALBUM', 'PLAY_FROM_PLAYLIST'])

  // Delete task
  const [deleteError, setDeleteError]     = useState('')     // Fix 2
  const [deletingId, setDeletingId]       = useState(null)   // Fix 2: loading state per task

  // Delete device
  const [deletingDeviceId, setDeletingDeviceId] = useState(null)
  const [deviceDeleteError, setDeviceDeleteError] = useState('')

  // Run
  const [activeRun, setActiveRun]         = useState(null)
  const [runLoading, setRunLoading]       = useState(false)
  const [runError, setRunError]           = useState('')
  const [selectedDevice, setSelectedDevice] = useState('')
  const pollRef = useRef(null)
  const logRef  = useRef(null)

  // Sessions
  const [sessions, setSessions]             = useState([])
  const [sessDevice, setSessDevice]         = useState('')
  const [sessTaskPick, setSessTaskPick]     = useState('')
  const [sessTaskList, setSessTaskList]     = useState([])
  const [sessStart, setSessStart]           = useState('')
  const [sessEnd, setSessEnd]               = useState('')
  const [sessError, setSessError]           = useState('')
  const [sessLoading, setSessLoading]       = useState(false)
  // Live run log for the currently executing session task
  const [sessionActiveRun, setSessionActiveRun] = useState(null)
  const sessLogRef = useRef(null)

  // ── Load initial data ───────────────────────────────────────────────────────
  useEffect(() => {
    const load = async () => {
      try {
        const [me, devs, tks] = await Promise.all([getMe(), listDevices(), listTasks()])
        setUser(me)
        setDevices(devs)
        setTasks(tks)
        if (devs.length > 0) {
          // Prefer the first online device — fall back to first device if none online
          const firstOnline = devs.find(d => d.status === 'online') || devs[0]
          setSelectedDevice(firstOnline.device_id)
          setSessDevice(firstOnline.device_id)
        }
      } catch {
        navigate('/login', { replace: true })
      }
    }
    load()
  }, [navigate])

  // ── Load sessions (on mount + every 15s) ────────────────────────────────────
  const loadSessions = useCallback(async () => {
    try {
      const data = await listSessions()
      setSessions(data)
    } catch {
      // silently ignore — sessions section shows stale data
    }
  }, [])

  useEffect(() => {
    loadSessions()
    const id = setInterval(loadSessions, 15_000)
    return () => clearInterval(id)
  }, [loadSessions])

  // ── Real-time device status via SSE ────────────────────────────────────────
  // EventSource doesn't support Authorization headers, so the JWT is passed
  // as a ?token= query param. The backend validates it before streaming.
  //
  // On a device_status event we patch ONLY the affected device's status in
  // the local state — no re-fetch needed, the update is instant.
  useEffect(() => {
    const token = localStorage.getItem('access_token')
    if (!token) return

    const host = window.location.hostname
    const url = `http://${host}:8000/events/stream?token=${encodeURIComponent(token)}`
    const es  = new EventSource(url)

    es.onmessage = (e) => {
      try {
        const event = JSON.parse(e.data)

        if (event.type === 'device_status') {
          const { device_id, status } = event
          if (status === 'online') {
            // Online → apply immediately and cancel any pending offline timer
            clearTimeout(offlineTimers.current[device_id])
            delete offlineTimers.current[device_id]
            setDevices(prev => {
              const exists = prev.some(d => d.device_id === device_id)
              if (exists) {
                // Known device — just flip its status
                return prev.map(d =>
                  d.device_id === device_id ? { ...d, status: 'online' } : d
                )
              }
              // Brand-new device — fetch its full record and append to the list.
              // We can't add it inline (no device details in the SSE event), so
              // fetch async and update state in the callback.
              getDevice(device_id)
                .then(d => setDevices(cur => {
                  // Guard: another event might have added it while we were fetching
                  if (cur.some(x => x.device_id === d.device_id)) return cur
                  return [...cur, d]
                }))
                .catch(() => {})
              return prev  // unchanged until the fetch resolves
            })
          } else {
            // Offline → debounce 5s to absorb brief reconnects / flicker
            clearTimeout(offlineTimers.current[device_id])
            offlineTimers.current[device_id] = setTimeout(() => {
              delete offlineTimers.current[device_id]
              setDevices(prev => prev.map(d =>
                d.device_id === device_id ? { ...d, status: 'offline' } : d
              ))
            }, 5000)
          }
        }

        if (event.type === 'run_event') {
          // Manual Run Now log
          setActiveRun(prev => {
            if (!prev || prev.run_id !== event.run_id) return prev
            return { ...prev, events: [...prev.events, event] }
          })
          // Session live log — same event, different state
          setSessionActiveRun(prev => {
            if (!prev || prev.run_id !== event.run_id) return prev
            return { ...prev, events: [...prev.events, event] }
          })
        }

        if (event.type === 'run_status') {
          // Manual Run Now status badge
          setActiveRun(prev => {
            if (!prev || prev.run_id !== event.run_id) return prev
            return { ...prev, status: event.status }
          })
          // Session live log status badge
          setSessionActiveRun(prev => {
            if (!prev || prev.run_id !== event.run_id) return prev
            return { ...prev, status: event.status }
          })
        }

        if (event.type === 'session_status') {
          setSessions(prev =>
            prev.map(s =>
              s.id === event.session_id ? { ...s, status: event.status } : s
            )
          )
          // Clear the session run log when the session finishes
          if (event.status === 'done' || event.status === 'failed') {
            setSessionActiveRun(prev =>
              prev?.session_id === event.session_id ? null : prev
            )
          }
        }

        if (event.type === 'session_run') {
          // Backend dispatched a new command for this session — start capturing its logs
          setSessionActiveRun({
            session_id:  event.session_id,
            run_id:      event.run_id,
            action_type: event.action_type,
            status:      'RUNNING',
            events:      [],
          })
        }

      } catch {
        // Malformed event — ignore
      }
    }

    es.onerror = () => {
      // EventSource auto-reconnects on error — no manual retry needed.
      // This fires on network hiccup or server restart; the browser will
      // keep trying with exponential backoff automatically.
      console.warn('[SSE] Connection error — browser will auto-retry')
    }

    return () => es.close()
  }, [])  // runs once after mount; token won't change without a page reload

  // Scroll run logs to bottom on new events
  useEffect(() => {
    if (logRef.current)     logRef.current.scrollTop     = logRef.current.scrollHeight
  }, [activeRun?.events])
  useEffect(() => {
    if (sessLogRef.current) sessLogRef.current.scrollTop = sessLogRef.current.scrollHeight
  }, [sessionActiveRun?.events])

  // ── Polling ─────────────────────────────────────────────────────────────────
  // We keep polling for 90s after TIMED_OUT in case a late COMMAND_DONE arrives
  // and flips the status to SUCCESS. The APK retries COMMAND_DONE for 5 minutes,
  // so a brief WS drop that caused TIMED_OUT may self-correct shortly after.
  const startPolling = useCallback((runId) => {
    if (pollRef.current) clearInterval(pollRef.current)
    let timedOutAt = null

    pollRef.current = setInterval(async () => {
      try {
        const run = await getRun(runId)
        setActiveRun(run)

        if (run.status === 'RUNNING') {
          timedOutAt = null   // still running — reset
          return
        }

        if (run.status === 'TIMED_OUT') {
          // Don't stop immediately — keep watching for 90s in case the APK
          // delivers COMMAND_DONE late and the backend flips it to SUCCESS
          if (!timedOutAt) timedOutAt = Date.now()
          if (Date.now() - timedOutAt < 90_000) return   // still within window
        }

        // Terminal status confirmed (or TIMED_OUT watch window expired)
        clearInterval(pollRef.current)
        pollRef.current = null
      } catch {
        clearInterval(pollRef.current)
      }
    }, 2000)
  }, [])

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current) }, [])

  // ── Create task ─────────────────────────────────────────────────────────────
  const handleCreateTask = async (e) => {
    e.preventDefault()
    setFormError('')
    setFormLoading(true)
    try {
      const t = await createTask({
        task_name:    taskName,
        action_type:  actionType,
        search_query: searchQuery || null,
        action_params: PLAYBACK_ACTIONS.has(actionType)
          ? { play_duration_s: 86400 }
          : null,
      })
      setTasks(prev => [...prev, t])
      setTaskName('')
      setSearchQuery('')
      setToast('✓ Task saved')
    } catch (err) {
      setFormError(err.response?.data?.detail || 'Failed to create task')
    } finally {
      setFormLoading(false)
    }
  }

  // ── Delete device ────────────────────────────────────────────────────────────
  const handleDeleteDevice = async (deviceId) => {
    if (!window.confirm(`Remove device "${deviceId}"?\n\nThis deletes all its runs and sessions from the database. If the APK is still installed and running on that phone it will re-register automatically on next connect.`)) return
    setDeviceDeleteError('')
    setDeletingDeviceId(deviceId)
    try {
      await deleteDevice(deviceId)
      setDevices(prev => prev.filter(d => d.device_id !== deviceId))
      if (selectedDevice === deviceId) setSelectedDevice('')
      if (sessDevice === deviceId) setSessDevice('')
    } catch (err) {
      setDeviceDeleteError(err.response?.data?.detail || `Failed to delete device "${deviceId}"`)
    } finally {
      setDeletingDeviceId(null)
    }
  }

  // ── Delete task ─────────────────────────────────────────────────────────────
  // Fix 2: confirmation dialog + error handling
  const handleDeleteTask = async (taskId, taskName) => {
    if (!window.confirm(`Delete "${taskName}"? This cannot be undone.`)) return

    setDeleteError('')
    setDeletingId(taskId)
    try {
      await deleteTask(taskId)
      setTasks(prev => prev.filter(t => t.id !== taskId))
    } catch (err) {
      setDeleteError(err.response?.data?.detail || `Failed to delete task "${taskName}"`)
    } finally {
      setDeletingId(null)
    }
  }

  // ── Run Now ─────────────────────────────────────────────────────────────────
  const handleRunNow = async (taskId) => {
    if (!selectedDevice) { setRunError('No device selected'); return }
    setRunError('')
    setRunLoading(true)
    setActiveRun(null)
    try {
      const result = await runNow(taskId, selectedDevice)
      setActiveRun({ run_id: result.run_id, status: 'RUNNING', events: [] })
      startPolling(result.run_id)
    } catch (err) {
      setRunError(err.response?.data?.detail || 'Failed to dispatch command')
    } finally {
      setRunLoading(false)
    }
  }

  // ── Logout ──────────────────────────────────────────────────────────────────
  const logout = () => {
    localStorage.removeItem('access_token')
    navigate('/login', { replace: true })
  }

  // ── Task sequence helpers ─────────────────────────────────────────────────────
  const addTaskToSession = () => {
    if (!sessTaskPick) return
    const task = tasks.find(t => t.id === parseInt(sessTaskPick, 10))
    if (!task) return
    setSessTaskList(prev => [...prev, { id: task.id, task_name: task.task_name, action_type: task.action_type }])
    setSessTaskPick('')
  }

  const removeSessionTask = (index) =>
    setSessTaskList(prev => prev.filter((_, i) => i !== index))

  const moveSessionTask = (index, dir) => {
    setSessTaskList(prev => {
      const next = [...prev]
      const swap = index + dir
      if (swap < 0 || swap >= next.length) return prev
      ;[next[index], next[swap]] = [next[swap], next[index]]
      return next
    })
  }

  // ── Quick Session — one click: all tasks, now+2min → now+52min, first online device ──
  const handleQuickSession = async () => {
    if (tasks.length === 0) { setSessError('No tasks saved yet — create some tasks first'); return }
    const onlineDevice = devices.find(d => d.status === 'online')
    if (!onlineDevice)    { setSessError('No device online — connect a device first'); return }

    const now   = new Date()
    const start = new Date(now.getTime() +  2 * 60 * 1000)  // now + 2 min
    const end   = new Date(now.getTime() + 52 * 60 * 1000)  // now + 52 min (50-min session)

    setSessError('')
    setSessLoading(true)
    try {
      await createSession({
        device_id:  onlineDevice.device_id,
        task_ids:   tasks.map(t => t.id),
        start_time: start.toISOString(),
        end_time:   end.toISOString(),
      })
      await loadSessions()
      setToast(`✓ Quick Session scheduled — ${tasks.length} task(s), starts in 2 min`)
    } catch (err) {
      setSessError(err.response?.data?.detail || 'Failed to schedule quick session')
    } finally {
      setSessLoading(false)
    }
  }

  // ── Schedule session ─────────────────────────────────────────────────────────
  const handleScheduleSession = async (e) => {
    e.preventDefault()
    if (!sessDevice)              { setSessError('Please select a device'); return }
    if (sessTaskList.length === 0){ setSessError('Add at least one task to the sequence'); return }
    if (!sessStart)               { setSessError('Please set a start time'); return }
    if (!sessEnd)                 { setSessError('Please set an end time'); return }

    const startDate = new Date(sessStart)   // datetime-local value → local time
    const endDate   = new Date(sessEnd)
    const now       = new Date()

    if (isNaN(startDate.getTime())) { setSessError('Start time is not a valid date'); return }
    if (isNaN(endDate.getTime()))   { setSessError('End time is not a valid date');   return }

    const currentYear = now.getFullYear()
    if (startDate.getFullYear() > currentYear + 1) {
      setSessError(`Start year ${startDate.getFullYear()} looks wrong — check the date`)
      return
    }
    if (endDate.getFullYear() > currentYear + 1) {
      setSessError(`End year ${endDate.getFullYear()} looks wrong — check the date`)
      return
    }

    if (endDate <= now)             { setSessError('End time must be in the future'); return }
    if (endDate <= startDate)       { setSessError('End time must be after start time'); return }

    const durationMins = (endDate - startDate) / 60_000
    if (durationMins < 5) {
      setSessError('Session must be at least 5 minutes long')
      return
    }
    setSessError('')
    setSessLoading(true)
    try {
      await createSession({
        device_id:  sessDevice,
        task_ids:   sessTaskList.map(t => t.id),
        start_time: startDate.toISOString(),   // UTC ISO with Z — Pydantic parses correctly
        end_time:   endDate.toISOString(),
      })
      setSessTaskList([])
      setSessStart('')
      setSessEnd('')
      await loadSessions()
    } catch (err) {
      setSessError(err.response?.data?.detail || 'Failed to schedule session')
    } finally {
      setSessLoading(false)
    }
  }

  // ── Stop session ─────────────────────────────────────────────────────────────
  const handleStopSession = async (id) => {
    try {
      await stopSession(id)
      setSessions(prev => prev.map(s => s.id === id ? { ...s, status: 'done' } : s))
    } catch (err) {
      console.error('[Sessions] Stop failed:', err)
    }
  }

  const onlineDevices = devices.filter(d => d.status === 'online')

  return (
    <>
      {/* Toast (Fix 4) */}
      {toast && <Toast message={toast} onDone={() => setToast('')} />}

      {/* Navbar */}
      <nav className="navbar">
        <span className="navbar-brand">🤖 SpotifyBot</span>
        <span className="spacer" />
        {user && <span style={{ color: 'var(--muted)' }}>{user.username}</span>}
        <button className="btn btn-ghost btn-sm" onClick={logout}>Logout</button>
      </nav>

      <div className="page">

        {/* ── Section 1: Devices ─────────────────────────────────────────── */}
        <section>
          <h2>Devices</h2>
          {deviceDeleteError && (
            <div className="alert alert-error mt-8">
              {deviceDeleteError}
              <button
                style={{ marginLeft: 12, background: 'none', border: 'none', color: 'inherit', cursor: 'pointer' }}
                onClick={() => setDeviceDeleteError('')}
              >✕</button>
            </div>
          )}
          <div className="col mt-8 gap-8">
            {devices.length === 0 && (
              <p className="empty">No devices registered yet. Open the SpotifyBot app on your phone.</p>
            )}
            {devices.map(d => (
              <div key={d.device_id} className="device-card row">
                <div className="col" style={{ gap: 2 }}>
                  <strong>{d.device_id}</strong>
                  <span style={{ color: 'var(--muted)', fontSize: 12 }}>
                    v{d.app_version} · last seen {d.last_seen ? new Date(d.last_seen).toLocaleString() : 'never'}
                  </span>
                </div>
                <span className="spacer" />
                <StatusBadge status={d.status} />
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={() => handleDeleteDevice(d.device_id)}
                  disabled={deletingDeviceId === d.device_id}
                  title="Remove device"
                  style={{ marginLeft: 8 }}
                >
                  {deletingDeviceId === d.device_id ? '…' : '✕'}
                </button>
              </div>
            ))}
          </div>
        </section>

        <hr className="divider" />

        {/* ── Section 2: Create Task ──────────────────────────────────────── */}
        <section>
          <h2>New Task</h2>
          {formError && <div className="alert alert-error mt-8">{formError}</div>}
          <form onSubmit={handleCreateTask} className="col mt-8">
            <div className="grid-2">
              <div className="form-group">
                <label>Task name</label>
                <input
                  className="form-control"
                  value={taskName}
                  onChange={e => setTaskName(e.target.value)}
                  placeholder="e.g. Play Blinding Lights"
                  required
                />
              </div>
              <div className="form-group">
                <label>Action</label>
                <select
                  className="form-control"
                  value={actionType}
                  onChange={e => setActionType(e.target.value)}
                >
                  {ACTION_TYPES.map(a => (
                    <option key={a.value} value={a.value}>{a.label}</option>
                  ))}
                </select>
              </div>
            </div>

            {ACTION_TYPES.find(a => a.value === actionType)?.needsQuery && (
              <div className="form-group">
                <label>
                  {actionType === 'FOLLOW_ARTIST'      ? 'Artist name'    :
                   actionType === 'FOLLOW_PLAYLIST'    ? 'Playlist name'  :
                   actionType === 'PLAY_FROM_ALBUM'    ? 'Album name'     :
                   actionType === 'PLAY_FROM_PLAYLIST' ? 'Playlist name'  :
                   actionType === 'ADD_TO_PLAYLIST'    ? 'Target playlist name' :
                   actionType === 'CREATE_PLAYLIST'    ? 'New playlist name'    :
                   'Search query'}
                </label>
                <input
                  className="form-control"
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  placeholder={
                    actionType === 'FOLLOW_ARTIST'      ? 'e.g. Taylor Swift'        :
                    actionType === 'FOLLOW_PLAYLIST'    ? 'e.g. Peaceful Piano'      :
                    actionType === 'PLAY_FROM_ALBUM'    ? 'e.g. Starboy'             :
                    actionType === 'PLAY_FROM_PLAYLIST' ? 'e.g. Today\'s Top Hits'   :
                    actionType === 'ADD_TO_PLAYLIST'    ? 'e.g. My Favourites'       :
                    actionType === 'CREATE_PLAYLIST'    ? 'e.g. Chill Vibes'         :
                    'e.g. Blinding Lights'
                  }
                  required
                />
              </div>
            )}

            <div>
              <button type="submit" className="btn btn-green" disabled={formLoading}>
                {formLoading ? 'Saving…' : '+ Save Task'}
              </button>
            </div>
          </form>
        </section>

        <hr className="divider" />

        {/* ── Section 3: Task list + Run Now ──────────────────────────────── */}
        <section>
          <div className="row">
            <h2>Tasks</h2>
            <span className="spacer" />
            {onlineDevices.length > 0 ? (
              <select
                className="form-control"
                style={{ width: 'auto' }}
                value={selectedDevice}
                onChange={e => setSelectedDevice(e.target.value)}
              >
                {onlineDevices.map(d => (
                  <option key={d.device_id} value={d.device_id}>{d.device_id}</option>
                ))}
              </select>
            ) : (
              <span style={{ color: 'var(--muted)', fontSize: 13 }}>
                ⚠ No device online
              </span>
            )}
          </div>

          {/* Fix 2: delete error banner */}
          {deleteError && (
            <div className="alert alert-error mt-8">
              {deleteError}
              <button
                style={{ marginLeft: 12, background: 'none', border: 'none', color: 'inherit', cursor: 'pointer' }}
                onClick={() => setDeleteError('')}
              >✕</button>
            </div>
          )}

          {runError && <div className="alert alert-error mt-8">{runError}</div>}

          <div className="col mt-8 gap-8">
            {tasks.length === 0 && (
              <p className="empty">No tasks yet — create one above.</p>
            )}
            {tasks.map(task => (
              <div key={task.id} className="card row">
                <div className="col" style={{ gap: 2 }}>
                  <strong>{task.task_name}</strong>
                  <span style={{ color: 'var(--muted)', fontSize: 12 }}>
                    {task.action_type}
                    {task.search_query ? ` · "${task.search_query}"` : ''}
                  </span>
                </div>
                <span className="spacer" />
                <button
                  className="btn btn-green btn-sm"
                  onClick={() => handleRunNow(task.id)}
                  disabled={runLoading || onlineDevices.length === 0}
                  title={onlineDevices.length === 0 ? 'No device online — open SpotifyBot app first' : 'Run on device'}
                >
                  {runLoading ? '…' : '▶ Run Now'}
                </button>
                {/* Fix 2: confirm + error handling on delete */}
                <button
                  className="btn btn-ghost btn-sm"
                  onClick={() => handleDeleteTask(task.id, task.task_name)}
                  disabled={deletingId === task.id}
                  title="Delete task"
                >
                  {deletingId === task.id ? '…' : '✕'}
                </button>
              </div>
            ))}
          </div>
        </section>

        {/* ── Section 4: Live run log (always visible) ───────────────────── */}
        <hr className="divider" />
        <section>
          <div className="row" style={{ gap: 8, alignItems: 'center', marginBottom: 8 }}>
            <h2 style={{ margin: 0 }}>Run Log</h2>

            {/* Manual Run Now badge */}
            {activeRun && (
              <>
                <StatusBadge status={activeRun.status} />
                <span style={{ color: 'var(--muted)', fontSize: 12 }}>
                  {activeRun.run_id?.slice(0, 8)}…
                </span>
              </>
            )}

            {/* Session run badge (when a session is dispatching automatically) */}
            {sessionActiveRun && !activeRun && (
              <>
                <StatusBadge status={sessionActiveRun.status} />
                <span style={{ color: 'var(--muted)', fontSize: 12 }}>
                  session · {sessionActiveRun.action_type}
                </span>
                <span style={{ color: 'var(--muted)', fontSize: 12 }}>
                  {sessionActiveRun.run_id?.slice(0, 8)}…
                </span>
              </>
            )}
          </div>

          {/* ── Idle state ── */}
          {!activeRun && !sessionActiveRun && (
            <div className="run-log" ref={logRef} style={{ color: 'var(--muted)' }}>
              Idle — click ▶ Run Now on a task, or wait for a scheduled session to start.
              Step-by-step execution logs will appear here in real time.
            </div>
          )}

          {/* ── Manual Run Now log ── */}
          {activeRun && (
            <div className="run-log" ref={logRef}>
              {activeRun.events.length === 0 && (
                <span style={{ color: 'var(--muted)' }}>Waiting for device…</span>
              )}
              {activeRun.events.map((ev, i) => <LogLine key={i} event={ev} />)}
              {activeRun.status === 'RUNNING' && (
                <div style={{ color: 'var(--muted)', marginTop: 4 }}>⏳ executing…</div>
              )}
              {activeRun.status === 'TIMED_OUT' && (
                <div style={{ color: 'var(--red,#e55)', marginTop: 4 }}>
                  ⏰ Run timed out — device may have disconnected mid-command.
                </div>
              )}
            </div>
          )}

          {/* ── Session auto-run log ── */}
          {sessionActiveRun && !activeRun && (
            <div className="run-log" ref={sessLogRef}>
              {sessionActiveRun.events.length === 0 && (
                <span style={{ color: 'var(--muted)' }}>Waiting for device…</span>
              )}
              {sessionActiveRun.events.map((ev, i) => <LogLine key={i} event={ev} />)}
              {sessionActiveRun.status === 'RUNNING' && (
                <div style={{ color: 'var(--muted)', marginTop: 4 }}>⏳ executing…</div>
              )}
            </div>
          )}

          {/* ── Both running simultaneously (rare: manual + session overlap) ── */}
          {activeRun && sessionActiveRun && (
            <div style={{ marginTop: 12, borderTop: '1px solid var(--border, #333)', paddingTop: 10 }}>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginBottom: 6 }}>
                Session auto-run · {sessionActiveRun.action_type}
              </div>
              <div className="run-log" ref={sessLogRef} style={{ maxHeight: 140 }}>
                {sessionActiveRun.events.map((ev, i) => <LogLine key={i} event={ev} />)}
                {sessionActiveRun.status === 'RUNNING' && (
                  <div style={{ color: 'var(--muted)', marginTop: 4 }}>⏳ executing…</div>
                )}
              </div>
            </div>
          )}
        </section>

        <hr className="divider" />

        {/* ── Section 5: Sessions ─────────────────────────────────────────── */}
        <section>
          <h2>Sessions</h2>
          <p style={{ color: 'var(--muted)', fontSize: 13, marginBottom: 12 }}>
            Schedule a task to run automatically in a loop between a start and end time.
          </p>

          {/* Quick Session — one-click shortcut */}
          <div style={{
            background: 'var(--surface)',
            border: '1px solid var(--border)',
            borderRadius: 8,
            padding: '14px 16px',
            marginBottom: 16,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
          }}>
            <div>
              <div style={{ fontWeight: 600, fontSize: 14 }}>Quick Session</div>
              <div style={{ color: 'var(--muted)', fontSize: 12, marginTop: 2 }}>
                All tasks · first online device · starts in 2 min · runs 50 min
              </div>
            </div>
            <button
              type="button"
              className="btn btn-green"
              onClick={handleQuickSession}
              disabled={sessLoading || tasks.length === 0 || !devices.some(d => d.status === 'online')}
              style={{ whiteSpace: 'nowrap', flexShrink: 0 }}
            >
              {sessLoading ? 'Scheduling…' : '⚡ Create Session'}
            </button>
          </div>

          {/* Create session form */}
          {sessError && <div className="alert alert-error mt-8">{sessError}</div>}
          <form onSubmit={handleScheduleSession} className="col mt-8">

            {/* Task sequence builder */}
            <div className="form-group">
              <label>Task sequence <span style={{ color: 'var(--muted)', fontWeight: 400 }}>(executed in order each loop)</span></label>
              <div className="row" style={{ gap: 8 }}>
                <select
                  className="form-control"
                  value={sessTaskPick}
                  onChange={e => setSessTaskPick(e.target.value)}
                  style={{ flex: 1 }}
                >
                  <option value="">— pick a task to add —</option>
                  {tasks.map(t => (
                    <option key={t.id} value={t.id}>
                      {t.task_name} ({t.action_type})
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="btn btn-green btn-sm"
                  onClick={addTaskToSession}
                  disabled={!sessTaskPick}
                >
                  + Add
                </button>
              </div>

              {/* Ordered task list */}
              {sessTaskList.length > 0 && (
                <div className="col mt-8 gap-4">
                  {sessTaskList.map((t, i) => (
                    <div key={i} className="card row" style={{ padding: '6px 12px', gap: 8 }}>
                      <span style={{ color: 'var(--muted)', minWidth: 20, fontSize: 12 }}>{i + 1}.</span>
                      <div className="col" style={{ gap: 0, flex: 1 }}>
                        <strong style={{ fontSize: 13 }}>{t.task_name}</strong>
                        <span style={{ color: 'var(--muted)', fontSize: 11 }}>{t.action_type}</span>
                      </div>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => moveSessionTask(i, -1)} disabled={i === 0} title="Move up">↑</button>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => moveSessionTask(i, 1)} disabled={i === sessTaskList.length - 1} title="Move down">↓</button>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => removeSessionTask(i)} title="Remove">✕</button>
                    </div>
                  ))}
                </div>
              )}
              {sessTaskList.length === 0 && (
                <p style={{ color: 'var(--muted)', fontSize: 12, marginTop: 6 }}>
                  No tasks added yet. Example: Search &amp; Play → Like → Skip → Follow Artist
                </p>
              )}
            </div>

            {/* Device + times */}
            <div className="form-group">
              <label>Device</label>
              <select
                className="form-control"
                value={sessDevice}
                onChange={e => setSessDevice(e.target.value)}
                required
              >
                <option value="">— select a device —</option>
                {devices.map(d => (
                  <option key={d.device_id} value={d.device_id}>
                    {d.device_id} ({d.status})
                  </option>
                ))}
              </select>
            </div>

            <div className="grid-2">
              <div className="form-group">
                <label>Start time <span style={{ color: 'var(--muted)', fontWeight: 400, fontSize: 12 }}>(your local time)</span></label>
                <input
                  type="datetime-local"
                  className="form-control"
                  value={sessStart}
                  max={maxLocalIso()}
                  onChange={e => setSessStart(e.target.value)}
                />
              </div>
              <div className="form-group">
                <label>End time <span style={{ color: 'var(--muted)', fontWeight: 400, fontSize: 12 }}>(your local time)</span></label>
                <input
                  type="datetime-local"
                  className="form-control"
                  value={sessEnd}
                  max={maxLocalIso()}
                  onChange={e => setSessEnd(e.target.value)}
                />
              </div>
            </div>

            <div>
              <button type="submit" className="btn btn-green" disabled={sessLoading || sessTaskList.length === 0}>
                {sessLoading ? 'Scheduling…' : `🕒 Schedule Session (${sessTaskList.length} task${sessTaskList.length !== 1 ? 's' : ''})`}
              </button>
            </div>
          </form>

          {/* Session list */}
          {sessions.length > 0 && (
            <div className="col mt-16 gap-8">
              {/* Active / upcoming */}
              {sessions.filter(s => s.status === 'scheduled' || s.status === 'running').map(s => (
                <div key={s.id} className="card row">
                  <div className="col" style={{ gap: 2 }}>
                    <strong>Session #{s.id}</strong>
                    <span style={{ color: 'var(--muted)', fontSize: 12 }}>
                      {s.task_ids?.length ?? 1} task{(s.task_ids?.length ?? 1) !== 1 ? 's' : ''} · {s.device_id}
                    </span>
                    <span style={{ color: 'var(--muted)', fontSize: 12 }}>
                      {fmtSessionTime(s.start_time)} → {fmtSessionTime(s.end_time)}
                    </span>
                  </div>
                  <span className="spacer" />
                  <StatusBadge status={s.status} />
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={() => handleStopSession(s.id)}
                    style={{ marginLeft: 8 }}
                  >
                    ■ Stop
                  </button>
                </div>
              ))}

              {/* Recent (done / failed) */}
              {sessions.filter(s => s.status === 'done' || s.status === 'failed').slice(0, 5).map(s => (
                <div key={s.id} className="card row" style={{ opacity: 0.65 }}>
                  <div className="col" style={{ gap: 2 }}>
                    <strong>Session #{s.id}</strong>
                    <span style={{ color: 'var(--muted)', fontSize: 12 }}>
                      {s.task_ids?.length ?? 1} task{(s.task_ids?.length ?? 1) !== 1 ? 's' : ''} · {s.device_id}
                    </span>
                    <span style={{ color: 'var(--muted)', fontSize: 12 }}>
                      {fmtSessionTime(s.start_time)} → {fmtSessionTime(s.end_time)}
                    </span>
                  </div>
                  <span className="spacer" />
                  <StatusBadge status={s.status} />
                </div>
              ))}
            </div>
          )}

          {sessions.length === 0 && (
            <p className="empty mt-8">No sessions yet — schedule one above.</p>
          )}
        </section>

      </div>
    </>
  )
}
