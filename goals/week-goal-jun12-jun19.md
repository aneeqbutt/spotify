# Refactor Dashboard into Multi-Page Website & Implement Session/Crash Stability Fixes (Jun 12 - Jun 19)

---

## Problem Statement

The current frontend is a single-page dashboard where all functionality — tasks, sessions, devices, run logs — is crammed into one scrollable page. As the feature set grows this becomes hard to navigate, hard to maintain, and does not reflect a production-quality product. Users have no clear mental model of where things live and the UI provides no visual hierarchy between concerns.

In parallel, two weeks of production testing exposed a set of crash and stability issues across all three layers (APK, backend, session scheduler) that were silently failing: the session scheduler could not recover from a backend crash mid-session, the stop button did not actually stop Spotify playback on the phone, like actions were saving whole albums instead of individual tracks, and accessibility zombie bindings on One UI 8 / Android 16 were preventing the service from ever starting.

Both tracks need to be completed this week to bring the project to a stable, demo-ready state.

---

## Solution Approach

- Redesign the frontend from a single-page dashboard into a proper multi-page React app with dedicated pages: Tasks, Sessions, Devices, and Run Logs
- Add a persistent navigation sidebar/navbar so users can move between pages without scrolling
- Move the task creation form, task list, and Run Now panel to a dedicated Tasks page
- Move the session scheduler form and session history to a dedicated Sessions page with a visible Stop button per running session
- Move device list and device status to a dedicated Devices page
- Fix the session crash-recovery bug — scheduler scan loop now restarts sessions stuck in "running" status after a backend crash
- Fix the stop session flow — CANCEL_COMMAND now reaches the APK and pauses Spotify even after commandDone() has already fired (playback monitor case)
- Fix the like-track bug in Play Album and Play Playlist — executor now always navigates to the full player before liking, preventing the album-level heart from being tapped instead of the track-level heart
- Fix the accessibility zombie binding on Samsung One UI 8 — removed WRITE_SECURE_SETTINGS self-grant, hardened onServiceConnected, made recovery notification-driven only
- Add session end-time enforcement — backend sends CANCEL_COMMAND to the APK when a session's end_time is reached so Spotify stops playing automatically
- Fix the inter-iteration cooldown overrun — replace plain asyncio.sleep with interruptible sleep so sessions end on time

---

## Acceptance Criteria

**Functional behavior:**
The frontend must have at least three distinct pages (Tasks, Sessions, Devices) reachable from a persistent navigation element. Each page must show only its relevant UI with no content from other pages bleeding in. The Sessions page must show a red Stop button for any running or scheduled session, and tapping Stop must pause Spotify on the phone within 3 seconds. A running session that reaches its end_time must stop automatically with no manual intervention. Liking songs via Play Album or Play Playlist must add individual tracks to Liked Songs, not the whole album. A backend restart mid-session must cause the session to auto-resume within 30 seconds.

**Performance metrics:**
Page navigation must be instant (client-side routing, no full reload). Session stop-to-Spotify-pause latency must be under 3 seconds on a local WiFi network. Backend crash recovery must trigger within one scheduler scan cycle (≤ 30 seconds). Like action on a playing track must complete within 15 seconds including the full-player navigation.

**Tests written:**
Manual end-to-end test: create session → confirm running → tap Stop → confirm Spotify pauses and session shows CANCELLED. Manual test: run Play Album with 3 likes → confirm 3 individual songs added to Liked Songs, not the album. Manual test: kill backend mid-session → restart backend → confirm session resumes within 30 seconds.

**Reviewed & deployed:**
All changes committed to the master branch and deployed to local dev environment. APK rebuilt and reinstalled on Samsung S21 FE via adb install -r. PR link submitted Friday with Loom walkthrough of the multi-page UI and the stop-session demo.

---

## Checkboxes

- [ ] Set up React Router and migrate UI into dedicated Tasks, Sessions, and Devices pages with persistent navigation
- [ ] Add Stop button on Sessions page — calls backend stop endpoint and pauses Spotify on the phone
- [ ] Fix session crash recovery — scheduler resumes "running" sessions after a backend restart
- [ ] Fix stop-session flow — CANCEL_COMMAND reaches the APK and pauses Spotify even after commandDone() has fired
- [ ] Fix like-track bug — executor navigates to full player before liking so individual tracks are saved, not the whole album
- [ ] Rebuild and reinstall APK with all stability fixes on Samsung S21 FE

---

## Daily Progress

**Jun 12:**
Completed all APK and backend stability fixes — CANCEL_COMMAND flow, like-track bug fix, crash recovery scan loop, end-time enforcement, cancelled status. Identified and fixed the post-commandDone race condition where cancel() was a no-op because currentExecutor was already null. Frontend multi-page redesign scoped and planned.

