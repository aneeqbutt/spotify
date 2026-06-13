# Spotify Automation Platform — Project Brain

> Global rules (failure behavior, retry policy, global skills) live in `~/.claude/CLAUDE.md`
> This file contains only project-specific context.

---

## ⛔ File Output Rule

**NEVER write any file to `C:\Users\Aneeq\Desktop\` directly.**
All output files (specs, dumps, XMLs, logs, exports, anything) must go inside `C:\Users\Aneeq\Desktop\Sportify\` or a subfolder within it.
If unsure where a file belongs, default to `Sportify/misc/`.

---

## Project Overview

**Spotify Automation Platform** — production-shaped intern project.

| Layer | Tech |
|---|---|
| Frontend | React 18 + Vite + Axios |
| Backend | FastAPI + Python 3.8+ + SQLite + JWT + WebSocket |
| Device | Android APK (Java JDK 8) + Accessibility Service |
| Target | Spotify Android App (via Accessibility Service) |

---

## Project-Level Skills (local to this project only)

> Architecture, diagram, doc-reading skills are already global — no need to reinstall.

| Skill | Purpose | Status |
|---|---|---|
| `fastapi-python` | FastAPI backend implementation | 🔲 To install |
| `react-vite-expert` | React 18 + Vite frontend | 🔲 To install |
| `android-java` | Android APK + Accessibility Service | 🔲 To install |
| `api-authentication` | JWT auth implementation | 🔲 To install |

---

## Progress Tracker

### Pre-Implementation
- [x] Doc fully read and understood
- [x] All 8 architectural diagrams generated
- [x] Global skills installed (architecture + docs)
- [x] Global CLAUDE.md created with failure/retry rules
- [x] Implementation skills download (Week 1 + Week 2) ✅

### Milestone 1 — Week 1 ✅ COMPLETE
- [x] M1-A: Doc & architecture understanding ✅
- [x] M1-B.1: Backend setup — FastAPI + SQLite + venv ✅
- [x] M1-B.2: Frontend setup — React 18 + Vite + Axios ✅
- [x] M1-B.3: Android APK built + installed on Samsung S21 FE ✅
- [x] M1-C: First Spotify click via Accessibility Service ✅

### Milestone 2 — Week 2 ✅ COMPLETE
- [x] Phase 1: JWT auth + SQLite + WebSocket handler ✅
- [x] Phase 2: React dashboard + task form + Run Now ✅
- [x] Phase 3: APK WebSocket + DEVICE_HELLO + reconnect ✅
- [x] Phase 4: Spotify Executor (search, play, like, follow, skip) ✅
- [x] Phase 5: Stability hardening (step timeouts, reconnect cap, run expiry, heartbeat) ✅

### Post-Milestone 2 — June 1, 2026
- [x] Ad/overlay resilience — sessions no longer crash on Spotify ads or premium upsells ✅
- [x] Auto device ID generation — hardware-derived, no hardcoded IDs, works on any phone ✅
- [x] Backend auto-registers new devices on first WebSocket connect ✅
- [x] HTTP device registration removed — was racing against UDP discovery ✅
- [x] Dashboard auto-selects first online device on load ✅
- [x] Gradle build pipeline fixed (JAVA_HOME → Android Studio JBR) ✅

### June 3, 2026
- [x] WebSocket robustness — timeout 300s→90s, PONG replies, grace period 3s→10s ✅
- [x] UDP beacon fix — subnet broadcast detection without internet, refresh every 60s ✅
- [x] Boot ID — APK detects backend restart, auto-clears stale host via boot_id in beacon ✅
- [x] Stale host eviction — clears SharedPreferences after 4 consecutive failures ✅
- [x] CORS + frontend localhost — dashboard works from any LAN machine ✅
- [x] Session scheduler — cooldown 300s→90s, end_time check in run wait, offline wait 120s→20s ✅
- [x] last_seen timezone bug — Z suffix added, timestamps now show correct local time ✅
- [x] New device auto-reflect — SSE handler now fetches and appends brand-new devices ✅
- [x] APK reinstall on S21 FE (accessibility zombie debugged + fixed) ✅
- [ ] End-to-end test: Run Now → live steps → COMMAND_DONE
- [ ] 5× consecutive automation run without manual intervention
- [ ] 20–40 hour stability test

### June 11, 2026
- [x] Accessibility service zombie binding (One UI 8 / Android 16) — root cause: old build wrote Settings.Secure programmatically; recovery loop stole focus from "Allow full control" dialog ✅
- [x] Manual recovery verified: wipe setting + force-stop + toggle ON by hand → binding now genuine (Bound services populated, not empty) ✅
- [x] Code fix deployed: onServiceConnected hardened (set instance first, defer startup); restartProcessForBinding silenced (no auto-Settings-open); recovery notification-driven only ✅
- [x] Samsung anti-sleep appops added to install grant: RUN_ANY_IN_BACKGROUND, RUN_IN_BACKGROUND, START_FOREGROUND ✅
- [x] Device confirmed genuinely bound + WebSocket healthy (steady PING/PONG, backend sees device online) ✅
- [ ] APK reinstall to pick up tamed recovery (pending)

---

## 📋 Daily Report Format

**TRIGGER:** Whenever the user says "make today's report", "write today's report", or similar —
generate a plain-text `.txt` report using the exact format below and save it to
`C:\Users\Aneeq\Desktop\Sportify\daily-reports\YYYY-MM-DD.txt`.
Also update the Progress Tracker section in this CLAUDE.md.

### Format (copy exactly — plain text, no markdown)

```
Daily Progress Report – [Month Day, Year]

Project: Spotify Automation Platform (SpotifyBot)

WORK SESSION 1: [TOPIC IN CAPS]

Completed Tasks

[Feature or area title]

* [What was done]

* [What was done]

  * Sub-detail
  * Sub-detail

* [What was done]

  * Sub-detail

WORK SESSION 2: [TOPIC IN CAPS]

Completed Tasks

[Feature or area title]

* [What was done]

[Another feature or area title]

* [What was done]

  * Sub-detail

CHALLENGES RESOLVED

[Challenge title]

* Root cause:
  [Describe root cause]

* Fix:
  [Describe fix]

[Another challenge]

* Root cause:
  [Describe root cause]

* Fix:
  [Describe fix]

FILES CHANGED

[path/to/file]

* [What changed and why]
* [What changed and why]

[path/to/file]

* [What changed and why]

END STATE

✓ [What is now working]

✓ [What is now working]

Pending:

* [What still needs to be done]
* [What still needs to be done]
```

### Rules
- Plain text file `.txt` — no markdown, no `#` headers, no `**bold**`, no backticks.
- No divider lines of any kind between sections — just blank lines.
- Group work into sessions by topic — not by individual file edits.
- Under Completed Tasks: bold-title each feature area, use `*` bullets with sub `*` for details.
- Under Challenges Resolved: always include root cause + fix explicitly.
- Under Files Changed: list each file separately with `*` bullets below it.
- Under End State: `✓` for completed, plain `*` for pending items.
- Save to `daily-reports/YYYY-MM-DD.txt` — never to Desktop root.
- ⛔ NEVER include any entry about updating CLAUDE.md, adding report patterns, updating memory, or any internal Claude tooling / meta-actions. The report covers PROJECT work only — code, features, bugs, fixes. If Claude updated its own instructions today, that is INVISIBLE in the report.

---

## Key Contracts (Quick Reference)

### Command Payload
```json
{
  "command_id": "UUID",
  "task_id": "int",
  "action_type": "SEARCH_AND_PLAY | LIKE_CURRENT_TRACK | ...",
  "params": {},
  "issued_at": "UTC timestamp",
  "ttl_ms": 180000
}
```

### Progress Events
| Event | Direction | When |
|---|---|---|
| `STEP_STARTED` | APK → Backend | Step begins |
| `STEP_OK` | APK → Backend | Step succeeds |
| `STEP_FAILED` | APK → Backend | Step fails + reason_code |
| `COMMAND_DONE` | APK → Backend | All steps complete |

### Guardrails
- Step timeout: 10–20s
- Command TTL: 3–5 min
- Max retries per step: 2–3
- Reconnect backoff: 1s → 2s → 4s → 8s → max 30s
