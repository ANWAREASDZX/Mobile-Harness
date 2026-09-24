# Security Policy

This document explains the security-relevant design decisions in Mobile Harness, how to report vulnerabilities, and the boundaries of the sandbox model. It reflects the current release; see the [audit roadmap](https://github.com/techjarves/Mobile-Harness) issues for planned hardening work.

## Reporting a Vulnerability

Please use [GitHub Security Advisories](https://github.com/techjarves/Mobile-Harness/security/advisories/new) to report vulnerabilities privately. Do not open public issues for exploitable defects. We aim to respond within 7 days and follow coordinated disclosure with a 90-day window.

## Security Model in One Paragraph

All coding agents run inside a PRoot-based Ubuntu 20.04 ARM64 guest that lives in the app's private storage. PRoot maps filesystems and identities in **user space** — it is a compatibility/translational layer, not a security container. It confines guest processes to the app's own data directory (no root, no other apps' storage, no system writes), and Android's standard app sandbox remains the outer boundary. Treat the guest as "a folder with a toolchain", not as a hardened VM.

## Known Design Decisions

### 1. Agent tool permissions follow a user-selected autonomy mode (v1.1.0)

**This is the most important thing to understand before using Mobile Harness.**

Three modes are selectable in Settings → Agent permissions, and the choice applies to all three agents:

- **Approve risky actions (default, including for upgrades from v1.0.x):** Claude Code runs with no auto-allow list; destructive and network commands (`rm -rf`, `git push`, `sudo`, `curl`, …) are surfaced to you as an approval card with a 60-second auto-deny timeout, and the network tools `WebFetch`/`WebSearch` are denied outright. DeepSeek Harness runs with `DSH_PERMISSION_MODE=default` and Antigravity without `--dangerously-skip-permissions`, so tool calls those CLIs cannot ask about headlessly are **denied, not auto-approved**.
- **Approve everything:** every Claude Code tool call waits for your approval card; dsh and agy behave as in the careful mode above.
- **Fully autonomous (explicit opt-in):** the historical v1.0.x behavior — Claude Code auto-allows the workspace tools, dsh runs `danger-full-access`, agy runs with `--dangerously-skip-permissions`. Fastest, and the most dangerous: a prompt injection can run any command inside the sandbox.

The approval channel itself is **fail-closed**: a corrupt or unreadable permission request, a dead bridge, or a timeout always results in `deny` — never `allow`.

Consequence regardless of mode: treat **imported repositories** and pasted prompts from unknown sources as untrusted. A README, a test fixture, or a tool output can carry prompt-injection instructions that the agent may act on — in careful modes it will ask you (verify what you approve!), in the autonomous mode it will simply act. Your provider API key is exposed to the guest environment (see §3 below), so a prompt-injected command could exfiltrate it.

### 2. Read-only Android system binds inside the guest

The guest binds `/system`, `/apex`, `/vendor`, and `/product` from the host Android OS **read-only**. This is required because the ARM64 Android build toolchain (notably `aapt2`) resolves Bionic's `/system/bin/linker64` and, on newer Android releases, APEX libraries from the host OS partitions — the guest cannot build Android apps without them.

Impact: guest processes (and therefore the agents) can **read** Android system files, but cannot write them; PRoot confines all writes to the app sandbox. This is a deliberate, bounded architectural trade-off, documented here so it is a decision rather than an accident.

### 3. Provider API keys reach the guest via environment variables

The active provider key is passed in the process environment of the agent process (visible via `/proc/<pid>/environ` to other processes inside the same guest). This is how the official CLIs accept credentials and is a known limitation of the current architecture. A local signing proxy that keeps keys inside the Android Keystore is the planned fix.

### 4. Local gateway on loopback, tokened per session (v1.1.0)

For OpenAI-protocol providers, a tiny local gateway listens on `127.0.0.1` (ephemeral port) to translate the wire format. Since v1.1.0 every gateway URL carries a random 128-bit path token (`/t/<token>/…`) that other apps on the device must present or receive `403`; request bodies are capped at 2 MB, header lines at 16 KB, and connections run on a small fixed thread pool. The residual limitation: like every loopback listener on Android, the port itself is reachable from other apps on the same device during an active session — but without the token they can neither use the gateway nor the provider key behind it.

## What Is Already Hardened

- Provider keys at rest are encrypted with **AES-256-GCM** keys held in **Android Keystore** (hardware-backed where available).
- All runtime bundle downloads (rootfs, agents, toolchains) are verified against **SHA-256/SHA-512 checksums pinned in the app** before extraction; interrupted downloads resume rather than restart.
- In-app self-updates verify the **signing certificate, versionCode, and SHA-256** of the downloaded APK before offering installation.
- Archive extraction enforces path-traversal protection with a canonical-prefix check, and rejects symlink escapes.
- The project preview WebView blocks all non-loopback navigation and requests, with file/content access explicitly disabled.
- Agent permission requests travel through a fail-closed bridge: unreadable, malformed, or timed-out requests are denied, and the default mode never writes an always-allow decision into the guest.
- The release build is shrunk and optimized with R8, and debug/verbose logging (including any raw agent output) is stripped from release logcat.
