# Security Policy

## Supported version

Security fixes target the current `main` branch. Preview builds and older commits are not maintained as separate release lines.

## Reporting a vulnerability

Use GitHub's private vulnerability-reporting channel for this repository. Do not open a public Issue for a vulnerability that may expose API keys, OAuth or MCP tokens, signing material, private device data, Root access, or a privilege-boundary bypass.

Include the affected commit, Android version and device/ROM, Root solution, reproduction steps, observed impact, and sanitized logs. Remove all secrets and unrelated personal data.

The project treats these boundaries as security-sensitive:

- Agent, Provider and MCP inputs must not gain a raw Root command or generic Root RPC path.
- `root.shell` remains local-only, structured as a trusted tool basename plus bounded arguments, and is not exposed to MCP.
- Guest processes run as the real App UID/GID with supplementary groups and Linux capabilities removed.
- Rootfs, mount, archive, backup and network payload validation fails closed.
- Sensitive tool inputs and outputs are excluded or redacted from persistent transcripts and checkpoints.

The current contract is documented in [docs/contracts/04-SECURITY-CONTRACT.md](docs/contracts/04-SECURITY-CONTRACT.md). Legal source lineage belongs in [PROVENANCE.md](PROVENANCE.md), not in vulnerability reports.
