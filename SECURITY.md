# Security Policy

This document explains the security-relevant design decisions in Mobile Harness, how to report vulnerabilities, and the boundaries of the sandbox model. It reflects the current release; see the [audit roadmap](https://github.com/techjarves/Mobile-Harness) issues for planned hardening work.

## Reporting a Vulnerability

Please use [GitHub Security Advisories](https://github.com/techjarves/Mobile-Harness/security/advisories/new) to report vulnerabilities privately. Do not open public issues for exploitable defects. We aim to respond within 7 days and follow coordinated disclosure with a 90-day window.

## Security Model in One Paragraph

All coding agents run inside a PRoot-based Ubuntu 20.04 ARM64 guest that lives in the app's private storage. PRoot maps filesystems and identities in **user space** — it is a compatibility/translational layer, not a security container. It confines guest processes to the app's own data directory (no root, no other apps' storage, no system writes), and Android's standard app sandbox remains the outer boundary. Treat the guest as "a folder with a toolchain", not as a hardened VM.

## Known Design Decisions

### 1. Agent tool permissions are auto-approved (v1.0.4)

**This is the most important thing to understand before using Mobile Harness.**

- **Claude Code** is launched with generated settings and hooks that **auto-allow** tools such as `Bash`, `Edit`, `Write`, `NotebookEdit`, `Read`, `Glob`, and `Grep`, with edits accepted automatically.
- **DeepSeek Harness** runs with `DSH_PERMISSION_MODE=danger-full-access`.
- **Antigravity** runs with `--dangerously-skip-permissions`.

Consequence: any of the three agents can execute commands, edit files, and use the network **without asking you** inside the PRoot boundary. This is a deliberate product decision ("the agent must never stall on a phone"), but it means:

- Treat **imported repositories** as untrusted. A README, a test fixture, or a tool output can carry prompt-injection instructions that the agent may act on — including commands with network access.
- Treat **pasted prompts from unknown sources** the same way.
- Your provider API key is exposed to the guest environment (see §3 below), so a prompt-injected command could exfiltrate it.

An interactive approval mode is planned; until it ships, users who need a hard boundary should not import untrusted code.

### 2. Read-only Android system binds inside the guest

The guest binds `/system`, `/apex`, `/vendor`, and `/product` from the host Android OS **read-only**. This is required because the ARM64 Android build toolchain (notably `aapt2`) resolves Bionic's `/system/bin/linker64` and, on newer Android releases, APEX libraries from the host OS partitions — the guest cannot build Android apps without them.

Impact: guest processes (and therefore the agents) can **read** Android system files, but cannot write them; PRoot confines all writes to the app sandbox. This is a deliberate, bounded architectural trade-off, documented here so it is a decision rather than an accident.

### 3. Provider API keys reach the guest via environment variables

The active provider key is passed in the process environment of the agent process (visible via `/proc/<pid>/environ` to other processes inside the same guest). This is how the official CLIs accept credentials and is a known limitation of the current architecture. A local signing proxy that keeps keys inside the Android Keystore is the planned fix.

### 4. Local HTTPS-free gateway on loopback

For OpenAI-protocol providers, a tiny local gateway listens on `127.0.0.1` (ephemeral port) to translate the wire format. Like every loopback listener on Android, it is reachable from other apps on the same device during an active session. Per-request authentication tokens and body limits are planned hardening.

## What Is Already Hardened

- Provider keys at rest are encrypted with **AES-256-GCM** keys held in **Android Keystore** (hardware-backed where available).
- All runtime bundle downloads (rootfs, agents, toolchains) are verified against **SHA-256/SHA-512 checksums pinned in the app** before extraction; interrupted downloads resume rather than restart.
- In-app self-updates verify the **signing certificate, versionCode, and SHA-256** of the downloaded APK before offering installation.
- Archive extraction enforces path-traversal protection with a canonical-prefix check, and rejects symlink escapes.
- The project preview WebView blocks all non-loopback navigation and requests.
