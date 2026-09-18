# MCP configuration hot-reload contract (Issue #39) — implementation record

> **Status (2026-09-10): historical implementation record.** Current `MCPRepository`, `MCPProvider`, Tool Registry and tests are authoritative. References in old PR discussions to concurrent runtime/package migrations are no longer current planning constraints.

## Purpose

The behavior introduced for Issue #39 was intended to keep persisted global MCP server configuration, active MCP client sessions, and registered remote tools synchronized without requiring an app restart.

## Source of truth

`MCPRepository` owns the Android view of MCP server configuration and publishes state. `MCPProvider` consumes that state to create client sessions and register remote tools under the existing Tool Registry/runtime permission model.

Effective global configuration mutations should notify/reload the provider; no-op writes should not reconnect healthy sessions unnecessarily. A supported disk refresh that observes a real configuration change should republish state and reload the provider.

Per-session MCP selection/enablement is not the same thing as global transport configuration and should not create an unnecessary global reconnect.

## Failure semantics

Persisting configuration and reloading runtime connections are distinct stages. A reload failure after a successful persistent write must not be falsely reported as if the storage write rolled back. Re-initialization must detach stale callbacks/consumers so old repository instances cannot mutate the current provider lifecycle.

## Current runtime/security relationship

MCP configuration hot reload does not own the Direct Ubuntu runtime, Root infrastructure, network compatibility proxy, Android storage contract, or Root authorization policy.

External MCP tools must still pass through the canonical Tool Registry/permission boundary. A configuration reload cannot make centrally denied capabilities available. Generic Root execution remains denied; MCP output must never become input to `DirectRootRunner` or arbitrary `su -c`.

The guest loopback HTTP/CONNECT network helper is unrelated to MCP server/client hot reload and must not become an MCP transport tunnel or command channel.

## Verification

Current repository tests should cover effective-change reloads, no-op writes, external supported refresh, callback failure semantics, stale repository detachment, and Tool Registry/session consistency. Canonical Android CI remains authoritative for compile/unit/lint/package results. Live third-party MCP interoperability requires explicit integration/device evidence when claimed.
