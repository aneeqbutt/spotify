import { useState, useEffect, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  getMe, listDevices, getDevice, listTasks, createTask, deleteTask,
  runNow, getRun, listSessions, createSession, stopSession, deleteDevice,
} from '../api'

// ── Helpers ────────────────────────────────────────────────────────────────────
function fmtTime(iso) {
  if (!iso) return '—'
  const s = iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z'
  return new Date(s).toLocaleString(undefined, {
    month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}
function nowLocalIso() {
  const d = new Date(), pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
function offsetLocalIso(ms) {
  const d = new Date(Date.now() + ms), pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
function maxLocalIso() {
  const d = new Date(); d.setFullYear(d.getFullYear() + 1)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T23:59`
}

// ── Action config ───────────────────────────────────────────────────────────────
const ACTION_GROUPS = [
  {
    group: 'Playback', chipClass: 'playback',
    actions: [
      { value: 'SEARCH_AND_PLAY',    label: 'Search & Play',  needsQuery: true, hasLikeSkip: true, hasAddToPlaylist: true },
      { value: 'PLAY_FROM_ALBUM',    label: 'Play Album',     needsQuery: true, hasLikeSkip: true },
      { value: 'PLAY_FROM_PLAYLIST', label: 'Play Playlist',  needsQuery: true, hasLikeSkip: true },
    ],
  },
  {
    group: 'Social', chipClass: 'social',
    actions: [
      { value: 'FOLLOW_ARTIST',   label: 'Follow Artist',   needsQuery: true },
      { value: 'FOLLOW_PLAYLIST', label: 'Follow Playlist', needsQuery: true },
    ],
  },
  {
    group: 'Library', chipClass: 'library',
    actions: [
      { value: 'CREATE_PLAYLIST', label: 'Create Playlist', needsQuery: true },
    ],
  },
]
const ACTION_MAP = Object.fromEntries(
  ACTION_GROUPS.flatMap(g => g.actions.map(a => [a.value, { ...a, chipClass: g.chipClass, group: g.group }]))
)
const PLAYBACK_ACTIONS = new Set(['SEARCH_AND_PLAY', 'PLAY_FROM_ALBUM', 'PLAY_FROM_PLAYLIST'])

// ── Shared atoms ───────────────────────────────────────────────────────────────
function StatusBadge({ status }) {
  const map = {
    online: 'badge-green', offline: 'badge-red',
    RUNNING: 'badge-yellow', SUCCESS: 'badge-green',
    FAILED: 'badge-red', TIMED_OUT: 'badge-red',
    scheduled: 'badge-muted', running: 'badge-yellow',
    done: 'badge-muted', cancelled: 'badge-red', failed: 'badge-red',
  }
  return <span className={`badge ${map[status] || 'badge-muted'}`}>{status}</span>
}

function PulseDot({ status }) {
  const cls = status === 'online' ? '' : status === 'RUNNING' || status === 'running' ? 'yellow' : 'muted'
  return <span className={`pulse-dot ${cls}`} />
}

function ActionChip({ actionType }) {
  const info = ACTION_MAP[actionType]
  return <span className={`action-chip ${info?.chipClass || ''}`}>{info?.label || actionType}</span>
}

function LogLine({ event }) {
  const cls = { STEP_STARTED: 'log-start', STEP_OK: 'log-ok', STEP_FAILED: 'log-fail', COMMAND_DONE: 'log-done' }
  const time = new Date(event.timestamp).toLocaleTimeString()
  return (
    <div>
      <span className="log-time">{time}</span>
      <span className={cls[event.event_type] || ''}>
        [{event.event_type}] {event.step_name || event.step_id || ''}
        {event.reason_code ? ` — ${event.reason_code}` : ''}
      </span>
    </div>
  )
}

function Toast({ message, onDone }) {
  useEffect(() => { const t = setTimeout(onDone, 2800); return () => clearTimeout(t) }, [onDone])
  return (
    <div className="toast">
      <span>✓</span>
      {message}
    </div>
  )
}

// ── Sidebar ────────────────────────────────────────────────────────────────────
const NAV_ITEMS = [
  { id: 'overview', icon: '▦', label: 'Overview' },
  { id: 'tasks',    icon: '◈', label: 'Tasks' },
  { id: 'sessions', icon: '◷', label: 'Sessions' },
  { id: 'devices',  icon: '◉', label: 'Devices' },
  { id: 'runlog',   icon: '◎', label: 'Run Log' },
]

function Sidebar({ activePage, setActivePage, user, logout, onlineCount, activeSessionCount, isLive }) {
  const badges = {
    devices:  onlineCount > 0  ? { count: onlineCount,        green: true }  : null,
    sessions: activeSessionCount > 0 ? { count: activeSessionCount, green: false } : null,
    runlog:   isLive             ? { count: '●',               green: true }  : null,
  }
  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <div className="brand-icon-wrap">♫</div>
        <div>
          <div className="brand-name">SpotifyBot</div>
          <div className="brand-sub">Automation</div>
        </div>
      </div>

      <nav className="sidebar-nav">
        {NAV_ITEMS.map(item => {
          const badge = badges[item.id]
          return (
            <button
              key={item.id}
              className={`nav-item ${activePage === item.id ? 'nav-item-active' : ''}`}
              onClick={() => setActivePage(item.id)}
            >
              <span className="nav-icon">{item.icon}</span>
              <span className="nav-label">{item.label}</span>
              {badge && (
                <span className={`nav-badge ${badge.green ? 'nav-badge-green' : ''}`}>
                  {badge.count}
                </span>
              )}
            </button>
          )
        })}
      </nav>

      <div className="sidebar-footer">
        {user && <span className="sidebar-user">@{user.username}</span>}
        <button className="btn btn-ghost btn-sm" onClick={logout} style={{ padding: '5px 10px', fontSize: 12 }}>
          Sign out
        </button>
      </div>
    </aside>
  )
}

// ── Overview page ──────────────────────────────────────────────────────────────
function OverviewPage({ devices, tasks, sessions, activeRun, sessionRuns, setActivePage }) {
  const onlineCount        = devices.filter(d => d.status === 'online').length
  const activeSessionCount = sessions.filter(s => ['scheduled','running'].includes(s.status)).length
  const isLive             = activeRun?.status === 'RUNNING' || sessionRuns.some(r => r.status === 'RUNNING')
  const lastRun            = activeRun || null

  return (
    <div className="page-content">
      <div className="page-header">
        <h1>Overview</h1>
        <p>Your Spotify automation platform at a glance</p>
      </div>

      {/* Stat cards */}
      <div className="stats-grid">
        <StatCard icon="◈" label="Total Tasks" value={tasks.length} color="blue"
          onClick={() => setActivePage('tasks')} />
        <StatCard icon="◉" label="Devices Online" value={`${onlineCount} / ${devices.length}`} color="green"
          pulse={onlineCount > 0} onClick={() => setActivePage('devices')} />
        <StatCard icon="◷" label="Active Sessions" value={activeSessionCount} color="yellow"
          onClick={() => setActivePage('sessions')} />
        <StatCard icon="◎" label="Run Status" value={isLive ? 'Live' : 'Idle'} color={isLive ? 'green' : 'muted'}
          pulse={isLive} onClick={() => setActivePage('runlog')} />
      </div>

      {/* Devices quick view */}
      <div className="col gap-16">
        <div className="card">
          <div className="card-header">
            <span style={{ fontSize: 14 }}>◉</span>
            <div>
              <div className="card-title">Connected Devices</div>
              <div className="card-subtitle">{onlineCount} online right now</div>
            </div>
            <span className="spacer" />
            <button className="btn btn-ghost btn-sm" onClick={() => setActivePage('devices')}>View all →</button>
          </div>
          {devices.length === 0 ? (
            <div className="empty-state"><div className="empty-state-icon">📱</div><p>No devices registered yet</p></div>
          ) : (
            <div className="col gap-8">
              {devices.slice(0, 3).map(d => (
                <div key={d.device_id} className={`device-card ${d.status}`}>
                  <PulseDot status={d.status} />
                  <div className="col gap-4" style={{ flex: 1 }}>
                    <strong style={{ fontSize: 13 }}>{d.device_id}</strong>
                    <span style={{ fontSize: 11, color: 'var(--muted)' }}>
                      v{d.app_version} · last seen {d.last_seen ? new Date(d.last_seen).toLocaleString() : 'never'}
                    </span>
                  </div>
                  <StatusBadge status={d.status} />
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Recent sessions quick view */}
        <div className="card">
          <div className="card-header">
            <span style={{ fontSize: 14 }}>◷</span>
            <div>
              <div className="card-title">Recent Sessions</div>
              <div className="card-subtitle">{activeSessionCount} active</div>
            </div>
            <span className="spacer" />
            <button className="btn btn-ghost btn-sm" onClick={() => setActivePage('sessions')}>View all →</button>
          </div>
          {sessions.length === 0 ? (
            <div className="empty-state"><div className="empty-state-icon">🗓</div><p>No sessions yet</p></div>
          ) : (
            <div className="col gap-8">
              {sessions.slice(0, 4).map(s => (
                <div key={s.id} className="row" style={{
                  padding: '10px 14px', background: 'var(--bg2)',
                  borderRadius: 'var(--radius)', border: '1px solid var(--border)',
                }}>
                  <PulseDot status={s.status} />
                  <div className="col gap-4" style={{ flex: 1 }}>
                    <span style={{ fontSize: 13, fontWeight: 600 }}>Session #{s.id}</span>
                    <span style={{ fontSize: 11, color: 'var(--muted)' }}>
                      {fmtTime(s.start_time)} → {fmtTime(s.end_time)}
                    </span>
                  </div>
                  <StatusBadge status={s.status} />
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function StatCard({ icon, label, value, color, pulse, onClick }) {
  const glowColors = {
    green: 'rgba(29,185,84,0.25)', blue: 'rgba(72,149,239,0.2)',
    yellow: 'rgba(255,209,102,0.2)', muted: 'transparent',
  }
  return (
    <div className="stat-card" onClick={onClick} style={{ cursor: onClick ? 'pointer' : 'default' }}>
      <div className={`stat-card-icon ${color}`}>{icon}</div>
      <div className="stat-card-value">{value}</div>
      <div className="stat-card-label row gap-8">
        {pulse && <PulseDot status="online" />}
        {label}
      </div>
      <div className="stat-card-glow" style={{ background: `radial-gradient(circle, ${glowColors[color] || 'transparent'} 0%, transparent 70%)` }} />
    </div>
  )
}

// ── Tasks page ──────────────────────────────────────────────────────────────────
function TasksPage({
  tasks, devices,
  taskName, setTaskName, actionType, setActionType,
  searchQuery, setSearchQuery, playlistName, setPlaylistName,
  likeCount, setLikeCount, skipCount, setSkipCount,
  formError, formLoading, handleCreateTask,
  runLoading, runError, setRunError,
  selectedDevice, setSelectedDevice,
  deleteError, setDeleteError, deletingId,
  handleRunNow, handleDeleteTask,
  expandedGroups, toggleGroup,
}) {
  const onlineDevices = devices.filter(d => d.status === 'online')
  const grouped = {}
  ACTION_GROUPS.forEach(g => { grouped[g.group] = [] })
  tasks.forEach(task => {
    const cat = ACTION_GROUPS.find(g => g.actions.some(a => a.value === task.action_type))
    if (cat) grouped[cat.group].push(task)
  })

  return (
    <div className="page-content">
      <div className="page-header">
        <h1>Tasks</h1>
        <p>Create and manage individual automation tasks</p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.4fr', gap: 24, alignItems: 'start' }}>
        {/* Create task form */}
        <div className="card">
          <div className="card-header">
            <span>✦</span>
            <div className="card-title">New Task</div>
          </div>
          {formError && <div className="alert alert-error" style={{ marginBottom: 16 }}>{formError}</div>}
          <form onSubmit={handleCreateTask} className="col gap-16">
            <div className="form-group">
              <label>Task name</label>
              <input className="form-control" value={taskName}
                onChange={e => setTaskName(e.target.value)}
                placeholder="e.g. Play Blinding Lights" required />
            </div>

            <div className="form-group">
              <label>Action type</label>
              <select className="form-control" value={actionType}
                onChange={e => { setActionType(e.target.value); setPlaylistName('') }}>
                {ACTION_GROUPS.map(g => (
                  <optgroup key={g.group} label={g.group}>
                    {g.actions.map(a => <option key={a.value} value={a.value}>{a.label}</option>)}
                  </optgroup>
                ))}
              </select>
            </div>

            {ACTION_MAP[actionType]?.needsQuery && (
              <div className="form-group">
                <label>
                  {actionType === 'FOLLOW_ARTIST'      ? 'Artist name'
                  : actionType === 'FOLLOW_PLAYLIST'   ? 'Playlist name'
                  : actionType === 'PLAY_FROM_ALBUM'   ? 'Album name'
                  : actionType === 'PLAY_FROM_PLAYLIST'? 'Playlist name'
                  : actionType === 'CREATE_PLAYLIST'   ? 'New playlist name'
                  : 'Song name'}
                </label>
                <input className="form-control" value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  placeholder={
                    actionType === 'FOLLOW_ARTIST'      ? 'e.g. Taylor Swift'
                    : actionType === 'FOLLOW_PLAYLIST'  ? 'e.g. Peaceful Piano'
                    : actionType === 'PLAY_FROM_ALBUM'  ? 'e.g. Starboy'
                    : actionType === 'PLAY_FROM_PLAYLIST'? "e.g. Today's Top Hits"
                    : actionType === 'CREATE_PLAYLIST'  ? 'e.g. Chill Vibes'
                    : 'e.g. Blinding Lights'
                  } required />
              </div>
            )}

            {ACTION_MAP[actionType]?.hasAddToPlaylist && (
              <div className="form-group">
                <label>Add to playlist <span style={{ color: 'var(--muted)', fontWeight: 400 }}>(optional)</span></label>
                <input className="form-control" value={playlistName}
                  onChange={e => setPlaylistName(e.target.value)}
                  placeholder="e.g. My Favourites — leave blank to just play" />
              </div>
            )}

            {ACTION_MAP[actionType]?.hasLikeSkip && (
              <div className="grid-2">
                <div className="form-group">
                  <label>Songs to like</label>
                  <input className="form-control" type="number" min="0" max="50"
                    value={likeCount} onChange={e => setLikeCount(Math.max(0, parseInt(e.target.value) || 0))} />
                </div>
                <div className="form-group">
                  <label>Songs to skip</label>
                  <input className="form-control" type="number" min="0" max="50"
                    value={skipCount} onChange={e => setSkipCount(Math.max(0, parseInt(e.target.value) || 0))} />
                </div>
              </div>
            )}

            <button type="submit" className="btn btn-green w-full" disabled={formLoading}>
              {formLoading ? 'Saving…' : '+ Save Task'}
            </button>
          </form>
        </div>

        {/* Task list */}
        <div className="col gap-16">
          {/* Device selector + errors */}
          <div className="card" style={{ padding: '14px 20px' }}>
            <div className="row gap-12">
              <span style={{ fontSize: 12, color: 'var(--muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.6px' }}>
                Run on device
              </span>
              <span className="spacer" />
              {onlineDevices.length > 0 ? (
                <select className="form-control" style={{ width: 'auto' }}
                  value={selectedDevice} onChange={e => setSelectedDevice(e.target.value)}>
                  {onlineDevices.map(d => <option key={d.device_id} value={d.device_id}>{d.device_id}</option>)}
                </select>
              ) : (
                <span style={{ fontSize: 12, color: 'var(--red)' }}>⚠ No device online</span>
              )}
            </div>
          </div>

          {(deleteError || runError) && (
            <div className="alert alert-error">
              {deleteError || runError}
              <span className="spacer" />
              <button style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer' }}
                onClick={() => { setDeleteError(''); setRunError('') }}>✕</button>
            </div>
          )}

          {tasks.length === 0 && (
            <div className="empty-state">
              <div className="empty-state-icon">◈</div>
              <p>No tasks yet — create one on the left.</p>
            </div>
          )}

          {ACTION_GROUPS.map(g => g.group)
            .filter(group => grouped[group]?.length > 0)
            .map(group => {
              const open = !!expandedGroups[group]
              const count = grouped[group].length
              const g = ACTION_GROUPS.find(ag => ag.group === group)
              return (
                <div key={group} className="accordion">
                  <button type="button" className="accordion-header" onClick={() => toggleGroup(group)}>
                    <span className={`action-chip ${g.chipClass}`} style={{ fontSize: 10, padding: '2px 7px' }}>{group}</span>
                    <span className="accordion-label">{group} Tasks</span>
                    <span className="accordion-count">{count} task{count !== 1 ? 's' : ''}</span>
                    <span className={`accordion-chevron ${open ? 'open' : ''}`}>▼</span>
                  </button>
                  {open && (
                    <div className="accordion-body col gap-0">
                      {grouped[group].map(task => (
                        <div key={task.id} className="task-row" style={{ borderRadius: 0, border: 'none', borderBottom: '1px solid var(--border)' }}>
                          <div className="col gap-4" style={{ flex: 1 }}>
                            <strong style={{ fontSize: 13 }}>{task.task_name}</strong>
                            <div className="row gap-8">
                              <ActionChip actionType={task.action_type} />
                              {task.search_query && (
                                <span style={{ fontSize: 11, color: 'var(--muted)' }}>"{task.search_query}"</span>
                              )}
                              {task.action_params?.likes > 0 && (
                                <span style={{ fontSize: 11, color: 'var(--muted)' }}>♥ {task.action_params.likes}</span>
                              )}
                              {task.action_params?.skips > 0 && (
                                <span style={{ fontSize: 11, color: 'var(--muted)' }}>⏭ {task.action_params.skips}</span>
                              )}
                            </div>
                          </div>
                          <button className="btn btn-green btn-sm"
                            onClick={() => handleRunNow(task.id)}
                            disabled={runLoading || onlineDevices.length === 0}>
                            {runLoading ? '…' : '▶ Run'}
                          </button>
                          <button className="btn btn-danger btn-sm btn-icon"
                            onClick={() => handleDeleteTask(task.id, task.task_name)}
                            disabled={deletingId === task.id}
                            title="Delete task">
                            {deletingId === task.id ? '…' : '✕'}
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )
            })
          }
        </div>
      </div>
    </div>
  )
}

// ── Sessions page ──────────────────────────────────────────────────────────────
function SessionsPage({
  sessions, tasks, devices,
  sessDevice, setSessDevice, sessTaskPick, setSessTaskPick,
  sessTaskList, addTaskToSession, removeSessionTask, moveSessionTask,
  sessStart, setSessStart, sessEnd, setSessEnd,
  sessError, setSessError, sessLoading,
  handleScheduleSession, handleStopSession, handleQuickSession,
  expandedSessions, toggleSessionGroup,
}) {
  const activeSessions = sessions.filter(s => ['scheduled','running'].includes(s.status))
  const recentSessions = sessions.filter(s => ['done','failed','cancelled'].includes(s.status)).slice(0, 5)

  return (
    <div className="page-content">
      <div className="page-header">
        <h1>Sessions</h1>
        <p>Schedule tasks to run automatically in a loop between a start and end time</p>
      </div>

      {/* Quick session banner */}
      <div className="quick-session-banner">
        <div>
          <div className="quick-session-title">⚡ Quick Session</div>
          <div className="quick-session-sub">All tasks · first online device · starts in 2 min · runs 50 min</div>
        </div>
        <button className="btn btn-green"
          onClick={handleQuickSession}
          disabled={sessLoading || tasks.length === 0 || !devices.some(d => d.status === 'online')}>
          {sessLoading ? 'Scheduling…' : 'Create Session'}
        </button>
      </div>

      {sessError && (
        <div className="alert alert-error" style={{ marginBottom: 16 }}>
          {sessError}
          <span className="spacer" />
          <button style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer' }}
            onClick={() => setSessError('')}>✕</button>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1.1fr 1fr', gap: 24, alignItems: 'start' }}>
        {/* Schedule form */}
        <div className="card">
          <div className="card-header">
            <span>🕒</span>
            <div className="card-title">Schedule Session</div>
          </div>
          <form onSubmit={handleScheduleSession} className="col gap-16">
            {/* Task sequence */}
            <div className="form-group">
              <label>Task sequence <span style={{ color: 'var(--muted)', fontWeight: 400 }}>(executed in order each loop)</span></label>
              <div className="row gap-8">
                <select className="form-control" value={sessTaskPick}
                  onChange={e => setSessTaskPick(e.target.value)} style={{ flex: 1 }}>
                  <option value="">— pick a task to add —</option>
                  {tasks.map(t => (
                    <option key={t.id} value={t.id}>
                      {t.task_name} ({ACTION_MAP[t.action_type]?.label || t.action_type})
                    </option>
                  ))}
                </select>
                <button type="button" className="btn btn-green btn-sm"
                  onClick={addTaskToSession} disabled={!sessTaskPick}>+ Add</button>
              </div>

              {sessTaskList.length > 0 ? (
                <div className="col gap-8 mt-8">
                  {sessTaskList.map((t, i) => (
                    <div key={i} className="seq-item">
                      <div className="seq-num">{i + 1}</div>
                      <div className="col gap-4" style={{ flex: 1 }}>
                        <strong style={{ fontSize: 12 }}>{t.task_name}</strong>
                        <ActionChip actionType={t.action_type} />
                      </div>
                      <button type="button" className="btn btn-ghost btn-sm btn-icon"
                        onClick={() => moveSessionTask(i, -1)} disabled={i === 0}>↑</button>
                      <button type="button" className="btn btn-ghost btn-sm btn-icon"
                        onClick={() => moveSessionTask(i, 1)} disabled={i === sessTaskList.length - 1}>↓</button>
                      <button type="button" className="btn btn-danger btn-sm btn-icon"
                        onClick={() => removeSessionTask(i)}>✕</button>
                    </div>
                  ))}
                </div>
              ) : (
                <p style={{ fontSize: 12, color: 'var(--muted)', marginTop: 8 }}>
                  No tasks added. Example: Search &amp; Play → Follow Artist
                </p>
              )}
            </div>

            {/* Device */}
            <div className="form-group">
              <label>Device</label>
              <select className="form-control" value={sessDevice}
                onChange={e => setSessDevice(e.target.value)} required>
                <option value="">— select a device —</option>
                {devices.map(d => (
                  <option key={d.device_id} value={d.device_id}>{d.device_id} ({d.status})</option>
                ))}
              </select>
            </div>

            {/* Times */}
            <div className="grid-2">
              <div className="form-group">
                <div className="row gap-6" style={{ marginBottom: 4 }}>
                  <label style={{ margin: 0 }}>Start time</label>
                  <button type="button" className="btn btn-ghost btn-xs"
                    onClick={() => setSessStart(nowLocalIso())}>Now</button>
                </div>
                <input type="datetime-local" className="form-control"
                  value={sessStart} max={maxLocalIso()} onChange={e => setSessStart(e.target.value)} />
              </div>
              <div className="form-group">
                <div className="row gap-6" style={{ marginBottom: 4 }}>
                  <label style={{ margin: 0 }}>End time</label>
                  <button type="button" className="btn btn-ghost btn-xs"
                    onClick={() => setSessEnd(offsetLocalIso(5 * 60 * 1000))}>+5m</button>
                  <button type="button" className="btn btn-ghost btn-xs"
                    onClick={() => setSessEnd(offsetLocalIso(60 * 60 * 1000))}>+1h</button>
                </div>
                <input type="datetime-local" className="form-control"
                  value={sessEnd} max={maxLocalIso()} onChange={e => setSessEnd(e.target.value)} />
              </div>
            </div>

            <button type="submit" className="btn btn-green w-full"
              disabled={sessLoading || sessTaskList.length === 0}>
              {sessLoading ? 'Scheduling…' : `Schedule Session (${sessTaskList.length} task${sessTaskList.length !== 1 ? 's' : ''})`}
            </button>
          </form>
        </div>

        {/* Session list */}
        <div className="col gap-16">
          {sessions.length === 0 && (
            <div className="empty-state">
              <div className="empty-state-icon">🗓</div>
              <p>No sessions yet — schedule one.</p>
            </div>
          )}

          {[
            { key: 'active', label: 'Active / Upcoming', items: activeSessions, showStop: true },
            { key: 'recent', label: 'Recent',             items: recentSessions, showStop: false },
          ].filter(g => g.items.length > 0).map(g => {
            const open = !!expandedSessions[g.key]
            return (
              <div key={g.key} className="accordion">
                <button type="button" className="accordion-header" onClick={() => toggleSessionGroup(g.key)}>
                  <span className="accordion-label">{g.label}</span>
                  <span className="accordion-count">{g.items.length} session{g.items.length !== 1 ? 's' : ''}</span>
                  <span className={`accordion-chevron ${open ? 'open' : ''}`}>▼</span>
                </button>
                {open && (
                  <div className="accordion-body">
                    {g.items.map(s => (
                      <div key={s.id} className="session-row">
                        <PulseDot status={s.status} />
                        <div className="col gap-4" style={{ flex: 1 }}>
                          <strong style={{ fontSize: 13 }}>Session #{s.id}</strong>
                          <span style={{ fontSize: 11, color: 'var(--muted)' }}>
                            {s.task_ids?.length ?? 1} task{(s.task_ids?.length ?? 1) !== 1 ? 's' : ''} · {s.device_id}
                          </span>
                          <span style={{ fontSize: 11, color: 'var(--muted)' }}>
                            {fmtTime(s.start_time)} → {fmtTime(s.end_time)}
                          </span>
                        </div>
                        <StatusBadge status={s.status} />
                        {g.showStop && ['running','scheduled'].includes(s.status) && (
                          <button className="btn btn-danger btn-sm" onClick={() => handleStopSession(s.id)}>
                            ■ Stop
                          </button>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

// ── Devices page ───────────────────────────────────────────────────────────────
function DevicesPage({ devices, deletingDeviceId, deviceDeleteError, setDeviceDeleteError, handleDeleteDevice }) {
  const online  = devices.filter(d => d.status === 'online')
  const offline = devices.filter(d => d.status !== 'online')

  return (
    <div className="page-content">
      <div className="page-header">
        <h1>Devices</h1>
        <p>Manage connected Android devices running SpotifyBot</p>
      </div>

      {deviceDeleteError && (
        <div className="alert alert-error" style={{ marginBottom: 20 }}>
          {deviceDeleteError}
          <span className="spacer" />
          <button style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer' }}
            onClick={() => setDeviceDeleteError('')}>✕</button>
        </div>
      )}

      {/* Online devices */}
      {online.length > 0 && (
        <>
          <div className="section-title row gap-8">
            <PulseDot status="online" /> Online — {online.length} device{online.length !== 1 ? 's' : ''}
          </div>
          <div className="col gap-12 mt-8" style={{ marginBottom: 28 }}>
            {online.map(d => <DeviceCard key={d.device_id} d={d}
              deletingDeviceId={deletingDeviceId} handleDeleteDevice={handleDeleteDevice} />)}
          </div>
        </>
      )}

      {/* Offline devices */}
      {offline.length > 0 && (
        <>
          <div className="section-title row gap-8">
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: 'var(--muted)', display: 'inline-block' }} />
            Offline — {offline.length} device{offline.length !== 1 ? 's' : ''}
          </div>
          <div className="col gap-12 mt-8">
            {offline.map(d => <DeviceCard key={d.device_id} d={d}
              deletingDeviceId={deletingDeviceId} handleDeleteDevice={handleDeleteDevice} />)}
          </div>
        </>
      )}

      {devices.length === 0 && (
        <div className="empty-state">
          <div className="empty-state-icon">📱</div>
          <p>No devices registered yet.</p>
          <p style={{ marginTop: 8 }}>Open the SpotifyBot app on your phone to get started.</p>
        </div>
      )}
    </div>
  )
}

function DeviceCard({ d, deletingDeviceId, handleDeleteDevice }) {
  return (
    <div className={`device-card ${d.status}`}>
      <PulseDot status={d.status} />
      <div className="col gap-4" style={{ flex: 1 }}>
        <strong style={{ fontSize: 14 }}>{d.device_id}</strong>
        <div className="row gap-12">
          <span style={{ fontSize: 11, color: 'var(--muted)' }}>v{d.app_version}</span>
          <span style={{ fontSize: 11, color: 'var(--muted)' }}>
            Last seen: {d.last_seen ? new Date(d.last_seen).toLocaleString() : 'never'}
          </span>
        </div>
      </div>
      <StatusBadge status={d.status} />
      <button
        className="btn btn-danger btn-sm"
        onClick={() => handleDeleteDevice(d.device_id)}
        disabled={deletingDeviceId === d.device_id}
        title="Remove device"
      >
        {deletingDeviceId === d.device_id ? '…' : 'Remove'}
      </button>
    </div>
  )
}

// ── Run Log page ───────────────────────────────────────────────────────────────
function RunLogPage({ activeRun, sessionRuns, logRef, sessLogRef }) {
  const isLive = activeRun?.status === 'RUNNING' || sessionRuns.some(r => r.status === 'RUNNING')

  return (
    <div className="page-content">
      <div className="page-header">
        <div className="row gap-12">
          <div>
            <h1>Run Log</h1>
            <p>Live step-by-step execution output from your device</p>
          </div>
          <span className="spacer" />
          {isLive && (
            <div className="live-indicator">
              <PulseDot status="online" />
              Live
            </div>
          )}
        </div>
      </div>

      {/* Manual Run Now log */}
      {activeRun && (
        <div className="card" style={{ marginBottom: 24 }}>
          <div className="card-header">
            <span>▶</span>
            <div>
              <div className="card-title">Manual Run</div>
              <div className="card-subtitle">{activeRun.run_id?.slice(0, 16)}…</div>
            </div>
            <span className="spacer" />
            <StatusBadge status={activeRun.status} />
          </div>
          <div className="run-log" ref={logRef}>
            {activeRun.events.length === 0 && (
              <span style={{ color: 'var(--muted)' }}>Waiting for device…</span>
            )}
            {activeRun.events.map((ev, i) => <LogLine key={i} event={ev} />)}
            {activeRun.status === 'RUNNING' && (
              <div style={{ color: 'var(--muted)', marginTop: 4 }}>⏳ executing…</div>
            )}
            {activeRun.status === 'TIMED_OUT' && (
              <div style={{ color: 'var(--red)', marginTop: 4 }}>
                ⏰ Run timed out — device may have disconnected mid-command.
              </div>
            )}
          </div>
        </div>
      )}

      {/* Session log */}
      {sessionRuns.length > 0 && (
        <div className="card" style={{ marginBottom: 24 }}>
          <div className="card-header">
            <span>◷</span>
            <div>
              <div className="card-title">Session Log</div>
              <div className="card-subtitle">{sessionRuns.length} task{sessionRuns.length !== 1 ? 's' : ''} executed</div>
            </div>
          </div>
          <div className="run-log" ref={sessLogRef}>
            {sessionRuns.map((run, idx) => (
              <div key={run.run_id}>
                <div style={{
                  display: 'flex', gap: 8, alignItems: 'center',
                  marginBottom: 6, paddingBottom: 6,
                  borderBottom: '1px solid var(--border)',
                }}>
                  <span style={{ color: 'var(--muted)', fontSize: 10, minWidth: 20 }}>#{idx + 1}</span>
                  <span style={{ fontSize: 11, fontWeight: 600 }}>
                    {ACTION_MAP[run.action_type]?.label || run.action_type}
                  </span>
                  <StatusBadge status={run.status} />
                  <span style={{ color: 'var(--muted)', fontSize: 10, marginLeft: 'auto' }}>
                    {run.run_id?.slice(0, 8)}…
                  </span>
                </div>
                {run.events.length === 0 && (
                  <span style={{ color: 'var(--muted)', fontSize: 12 }}>Waiting for device…</span>
                )}
                {run.events.map((ev, i) => <LogLine key={i} event={ev} />)}
                {run.status === 'RUNNING' && (
                  <div style={{ color: 'var(--muted)', marginTop: 2 }}>⏳ executing…</div>
                )}
                {idx < sessionRuns.length - 1 && <div style={{ height: 12 }} />}
              </div>
            ))}
          </div>
        </div>
      )}

      {!activeRun && sessionRuns.length === 0 && (
        <div className="card">
          <div className="run-log-empty">
            <div style={{ fontSize: 32, marginBottom: 12, opacity: 0.3 }}>◎</div>
            <p>Idle — click ▶ Run on a task, or wait for a scheduled session to start.</p>
            <p style={{ marginTop: 8, fontSize: 12 }}>Step-by-step logs will appear here in real time.</p>
          </div>
        </div>
      )}
    </div>
  )
}

// ── Main dashboard shell ───────────────────────────────────────────────────────
export default function DashboardPage() {
  const navigate = useNavigate()
  const [activePage, setActivePage] = useState('overview')

  // Data
  const [user, setUser]       = useState(null)
  const [devices, setDevices] = useState([])
  const [tasks, setTasks]     = useState([])
  const offlineTimers         = useRef({})

  // Task form
  const [taskName, setTaskName]             = useState('')
  const [actionType, setActionType]         = useState('SEARCH_AND_PLAY')
  const [searchQuery, setSearchQuery]       = useState('')
  const [playlistName, setPlaylistName]     = useState('')
  const [likeCount, setLikeCount]           = useState(0)
  const [skipCount, setSkipCount]           = useState(0)
  const [formError, setFormError]           = useState('')
  const [formLoading, setFormLoading]       = useState(false)
  const [toast, setToast]                   = useState('')

  // Delete
  const [deleteError, setDeleteError]           = useState('')
  const [deletingId, setDeletingId]             = useState(null)
  const [deletingDeviceId, setDeletingDeviceId] = useState(null)
  const [deviceDeleteError, setDeviceDeleteError] = useState('')

  // Run
  const [activeRun, setActiveRun]         = useState(null)
  const [runLoading, setRunLoading]       = useState(false)
  const [runError, setRunError]           = useState('')
  const [selectedDevice, setSelectedDevice] = useState('')
  const pollRef = useRef(null)
  const logRef  = useRef(null)

  // Task accordions
  const [expandedGroups, setExpandedGroups] = useState({ Playback: true })
  const toggleGroup = g => setExpandedGroups(p => ({ ...p, [g]: !p[g] }))

  // Sessions
  const [sessions, setSessions]           = useState([])
  const [sessDevice, setSessDevice]       = useState('')
  const [sessTaskPick, setSessTaskPick]   = useState('')
  const [sessTaskList, setSessTaskList]   = useState([])
  const [sessStart, setSessStart]         = useState(nowLocalIso)
  const [sessEnd, setSessEnd]             = useState(() => offsetLocalIso(5 * 60 * 1000))
  const [sessError, setSessError]         = useState('')
  const [sessLoading, setSessLoading]     = useState(false)
  const [sessionRuns, setSessionRuns]     = useState([])
  const sessLogRef                        = useRef(null)

  const [expandedSessions, setExpandedSessions] = useState({ active: true, recent: false })
  const toggleSessionGroup = k => setExpandedSessions(p => ({ ...p, [k]: !p[k] }))

  // ── Load initial data ────────────────────────────────────────────────────────
  useEffect(() => {
    const load = async () => {
      try {
        const [me, devs, tks] = await Promise.all([getMe(), listDevices(), listTasks()])
        setUser(me)
        setDevices(devs)
        setTasks(tks)
        if (devs.length > 0) {
          const first = devs.find(d => d.status === 'online') || devs[0]
          setSelectedDevice(first.device_id)
          setSessDevice(first.device_id)
        }
      } catch {
        navigate('/login', { replace: true })
      }
    }
    load()
  }, [navigate])

  // ── Sessions polling ─────────────────────────────────────────────────────────
  const loadSessions = useCallback(async () => {
    try { setSessions(await listSessions()) } catch {}
  }, [])

  useEffect(() => {
    loadSessions()
    const id = setInterval(loadSessions, 15_000)
    return () => clearInterval(id)
  }, [loadSessions])

  // ── SSE ───────────────────────────────────────────────────────────────────────
  useEffect(() => {
    const token = localStorage.getItem('access_token')
    if (!token) return
    const host = window.location.hostname
    const es = new EventSource(`http://${host}:8000/events/stream?token=${encodeURIComponent(token)}`)

    es.onmessage = (e) => {
      try {
        const event = JSON.parse(e.data)

        if (event.type === 'device_status') {
          const { device_id, status } = event
          if (status === 'online') {
            clearTimeout(offlineTimers.current[device_id])
            delete offlineTimers.current[device_id]
            setDevices(prev => {
              const exists = prev.some(d => d.device_id === device_id)
              if (exists) return prev.map(d => d.device_id === device_id ? { ...d, status: 'online' } : d)
              getDevice(device_id)
                .then(d => setDevices(cur => cur.some(x => x.device_id === d.device_id) ? cur : [...cur, d]))
                .catch(() => {})
              return prev
            })
          } else {
            clearTimeout(offlineTimers.current[device_id])
            offlineTimers.current[device_id] = setTimeout(() => {
              delete offlineTimers.current[device_id]
              setDevices(prev => prev.map(d => d.device_id === device_id ? { ...d, status: 'offline' } : d))
            }, 5000)
          }
        }

        if (event.type === 'run_event') {
          setActiveRun(prev => {
            if (!prev || prev.run_id !== event.run_id) return prev
            return { ...prev, events: [...prev.events, event] }
          })
          setSessionRuns(prev => {
            const idx = prev.findIndex(r => r.run_id === event.run_id)
            if (idx >= 0) {
              const updated = [...prev]
              updated[idx] = { ...updated[idx], events: [...updated[idx].events, event] }
              return updated
            }
            if (event.session_id) {
              return [...prev, { session_id: event.session_id, run_id: event.run_id, action_type: '?', status: 'RUNNING', events: [event] }]
            }
            return prev
          })
        }

        if (event.type === 'run_status') {
          setActiveRun(prev => prev?.run_id === event.run_id ? { ...prev, status: event.status } : prev)
          setSessionRuns(prev => prev.map(r => r.run_id === event.run_id ? { ...r, status: event.status } : r))
        }

        if (event.type === 'session_status') {
          setSessions(prev => prev.map(s => s.id === event.session_id ? { ...s, status: event.status } : s))
        }

        if (event.type === 'session_run') {
          setSessionRuns(prev => {
            if (prev.length > 0 && prev[0].session_id !== event.session_id) {
              return [{ session_id: event.session_id, run_id: event.run_id, action_type: event.action_type, status: 'RUNNING', events: [] }]
            }
            return [...prev, { session_id: event.session_id, run_id: event.run_id, action_type: event.action_type, status: 'RUNNING', events: [] }]
          })
        }
      } catch {}
    }
    es.onerror = () => console.warn('[SSE] Connection error — browser will auto-retry')
    return () => es.close()
  }, [])

  // ── Scroll logs to bottom ────────────────────────────────────────────────────
  useEffect(() => { if (logRef.current)     logRef.current.scrollTop     = logRef.current.scrollHeight     }, [activeRun?.events])
  useEffect(() => { if (sessLogRef.current) sessLogRef.current.scrollTop = sessLogRef.current.scrollHeight }, [sessionRuns])

  // ── Polling ───────────────────────────────────────────────────────────────────
  const startPolling = useCallback((runId) => {
    if (pollRef.current) clearInterval(pollRef.current)
    let timedOutAt = null
    pollRef.current = setInterval(async () => {
      try {
        const run = await getRun(runId)
        setActiveRun(run)
        if (run.status === 'RUNNING') { timedOutAt = null; return }
        if (run.status === 'TIMED_OUT') {
          if (!timedOutAt) timedOutAt = Date.now()
          if (Date.now() - timedOutAt < 90_000) return
        }
        clearInterval(pollRef.current); pollRef.current = null
      } catch { clearInterval(pollRef.current) }
    }, 2000)
  }, [])
  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current) }, [])

  // ── Handlers ──────────────────────────────────────────────────────────────────
  const handleCreateTask = async (e) => {
    e.preventDefault(); setFormError(''); setFormLoading(true)
    try {
      const hasLikeSkip = ACTION_MAP[actionType]?.hasLikeSkip
      const hasAddToPlaylist = ACTION_MAP[actionType]?.hasAddToPlaylist
      const t = await createTask({
        task_name: taskName, action_type: actionType,
        search_query: searchQuery || null,
        action_params: PLAYBACK_ACTIONS.has(actionType)
          ? {
              play_duration_s: 86400,
              ...(hasLikeSkip && likeCount > 0 ? { likes: likeCount } : {}),
              ...(hasLikeSkip && skipCount > 0 ? { skips: skipCount } : {}),
              ...(hasAddToPlaylist && playlistName.trim() ? { playlist_name: playlistName.trim() } : {}),
            }
          : null,
      })
      setTasks(prev => [...prev, t])
      setTaskName(''); setSearchQuery(''); setPlaylistName('')
      setLikeCount(0); setSkipCount(0)
      setToast('Task saved')
    } catch (err) {
      setFormError(err.response?.data?.detail || 'Failed to create task')
    } finally { setFormLoading(false) }
  }

  const handleDeleteDevice = async (deviceId) => {
    if (!window.confirm(`Remove device "${deviceId}"?\n\nThis deletes all its runs and sessions. The APK will re-register automatically on next connect.`)) return
    setDeviceDeleteError(''); setDeletingDeviceId(deviceId)
    try {
      await deleteDevice(deviceId)
      setDevices(prev => prev.filter(d => d.device_id !== deviceId))
      if (selectedDevice === deviceId) setSelectedDevice('')
      if (sessDevice === deviceId) setSessDevice('')
    } catch (err) {
      setDeviceDeleteError(err.response?.data?.detail || `Failed to delete device "${deviceId}"`)
    } finally { setDeletingDeviceId(null) }
  }

  const handleDeleteTask = async (taskId, name) => {
    if (!window.confirm(`Delete "${name}"? This cannot be undone.`)) return
    setDeleteError(''); setDeletingId(taskId)
    try {
      await deleteTask(taskId)
      setTasks(prev => prev.filter(t => t.id !== taskId))
    } catch (err) {
      setDeleteError(err.response?.data?.detail || `Failed to delete task "${name}"`)
    } finally { setDeletingId(null) }
  }

  const handleRunNow = async (taskId) => {
    if (!selectedDevice) { setRunError('No device selected'); return }
    const task = tasks.find(t => t.id === taskId)
    if (!task) { setRunError('Task not found'); return }
    if (ACTION_MAP[task.action_type]?.needsQuery && !task.search_query?.trim()) {
      setRunError(`"${task.task_name}" has no search query — delete and recreate it`); return
    }
    setRunError(''); setRunLoading(true); setActiveRun(null)
    try {
      const result = await runNow(taskId, selectedDevice)
      setActiveRun({ run_id: result.run_id, status: 'RUNNING', events: [] })
      startPolling(result.run_id)
      setActivePage('runlog')
    } catch (err) {
      setRunError(err.response?.data?.detail || 'Failed to dispatch command')
    } finally { setRunLoading(false) }
  }

  const addTaskToSession = () => {
    if (!sessTaskPick) return
    const task = tasks.find(t => t.id === parseInt(sessTaskPick, 10))
    if (!task) return
    setSessTaskList(prev => [...prev, { id: task.id, task_name: task.task_name, action_type: task.action_type }])
    setSessTaskPick('')
  }
  const removeSessionTask = i => setSessTaskList(prev => prev.filter((_, j) => j !== i))
  const moveSessionTask = (i, dir) => {
    setSessTaskList(prev => {
      const next = [...prev]; const swap = i + dir
      if (swap < 0 || swap >= next.length) return prev
      ;[next[i], next[swap]] = [next[swap], next[i]]
      return next
    })
  }

  const handleQuickSession = async () => {
    if (tasks.length === 0)                  { setSessError('No tasks saved yet'); return }
    const online = devices.find(d => d.status === 'online')
    if (!online)                             { setSessError('No device online'); return }
    const start = new Date(Date.now() +  2 * 60 * 1000)
    const end   = new Date(Date.now() + 52 * 60 * 1000)
    setSessError(''); setSessLoading(true)
    try {
      await createSession({ device_id: online.device_id, task_ids: tasks.map(t => t.id),
        start_time: start.toISOString(), end_time: end.toISOString() })
      await loadSessions()
      setToast(`Quick Session scheduled — ${tasks.length} task(s), starts in 2 min`)
    } catch (err) {
      setSessError(err.response?.data?.detail || 'Failed to schedule quick session')
    } finally { setSessLoading(false) }
  }

  const handleScheduleSession = async (e) => {
    e.preventDefault()
    if (!sessDevice)              { setSessError('Please select a device'); return }
    if (sessTaskList.length === 0){ setSessError('Add at least one task'); return }
    const startDate = new Date(sessStart), endDate = new Date(sessEnd), now = new Date()
    if (isNaN(startDate.getTime())) { setSessError('Start time is invalid'); return }
    if (isNaN(endDate.getTime()))   { setSessError('End time is invalid');   return }
    const yr = now.getFullYear()
    if (startDate.getFullYear() > yr + 1) { setSessError(`Start year ${startDate.getFullYear()} looks wrong`); return }
    if (endDate.getFullYear() > yr + 1)   { setSessError(`End year ${endDate.getFullYear()} looks wrong`); return }
    if (endDate <= now)             { setSessError('End time must be in the future'); return }
    if (endDate <= startDate)       { setSessError('End time must be after start time'); return }
    setSessError(''); setSessLoading(true)
    try {
      await createSession({ device_id: sessDevice, task_ids: sessTaskList.map(t => t.id),
        start_time: startDate.toISOString(), end_time: endDate.toISOString() })
      setSessTaskList([]); setSessStart(nowLocalIso()); setSessEnd(offsetLocalIso(5 * 60 * 1000))
      await loadSessions()
      setExpandedSessions(p => ({ ...p, active: true }))
      setToast('Session scheduled')
    } catch (err) {
      setSessError(err.response?.data?.detail || 'Failed to schedule session')
    } finally { setSessLoading(false) }
  }

  const handleStopSession = async (id) => {
    try {
      await stopSession(id)
      setSessions(prev => prev.map(s => s.id === id ? { ...s, status: 'cancelled' } : s))
    } catch (err) { console.error('[Sessions] Stop failed:', err) }
  }

  const logout = () => { localStorage.removeItem('access_token'); navigate('/login', { replace: true }) }

  // Derived values for sidebar badges
  const onlineCount        = devices.filter(d => d.status === 'online').length
  const activeSessionCount = sessions.filter(s => ['scheduled','running'].includes(s.status)).length
  const isLive             = activeRun?.status === 'RUNNING' || sessionRuns.some(r => r.status === 'RUNNING')

  // Props bundles
  const taskPageProps = {
    tasks, devices,
    taskName, setTaskName, actionType, setActionType,
    searchQuery, setSearchQuery, playlistName, setPlaylistName,
    likeCount, setLikeCount, skipCount, setSkipCount,
    formError, formLoading, handleCreateTask,
    runLoading, runError, setRunError,
    selectedDevice, setSelectedDevice,
    deleteError, setDeleteError, deletingId,
    handleRunNow, handleDeleteTask,
    expandedGroups, toggleGroup,
  }
  const sessionPageProps = {
    sessions, tasks, devices,
    sessDevice, setSessDevice, sessTaskPick, setSessTaskPick,
    sessTaskList, addTaskToSession, removeSessionTask, moveSessionTask,
    sessStart, setSessStart, sessEnd, setSessEnd,
    sessError, setSessError, sessLoading,
    handleScheduleSession, handleStopSession, handleQuickSession,
    expandedSessions, toggleSessionGroup,
  }

  return (
    <div className="app-shell">
      {toast && <Toast message={toast} onDone={() => setToast('')} />}

      <Sidebar
        activePage={activePage}
        setActivePage={setActivePage}
        user={user}
        logout={logout}
        onlineCount={onlineCount}
        activeSessionCount={activeSessionCount}
        isLive={isLive}
      />

      <main className="main-content">
        {activePage === 'overview' && (
          <OverviewPage devices={devices} tasks={tasks} sessions={sessions}
            activeRun={activeRun} sessionRuns={sessionRuns} setActivePage={setActivePage} />
        )}
        {activePage === 'tasks' && <TasksPage {...taskPageProps} />}
        {activePage === 'sessions' && <SessionsPage {...sessionPageProps} />}
        {activePage === 'devices' && (
          <DevicesPage devices={devices} deletingDeviceId={deletingDeviceId}
            deviceDeleteError={deviceDeleteError} setDeviceDeleteError={setDeviceDeleteError}
            handleDeleteDevice={handleDeleteDevice} />
        )}
        {activePage === 'runlog' && (
          <RunLogPage activeRun={activeRun} sessionRuns={sessionRuns}
            logRef={logRef} sessLogRef={sessLogRef} />
        )}
      </main>
    </div>
  )
}
