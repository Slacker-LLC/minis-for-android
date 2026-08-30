# Cold-start configuration profile

Issue #30 is an investigation. The current source does not justify a storage migration by itself; this document records the static startup audit and the trace required before changing persistence architecture.

## Current startup chain

`MinisApp.onCreate()` runs on the Android main thread and performs a mixture of small preference/cache reads, repository construction, diagnostics setup and runtime/tool registration before the UI can use the application repositories.

Relevant early work includes:

- `FastModePrefs.prime()` — one synchronous `SharedPreferences.getBoolean` used to warm a volatile process cache.
- `AutoCompactPrefs.prime()` — one synchronous `SharedPreferences.getBoolean` used to warm a volatile process cache.
- native crash-handler installation and crash-frequency scan.
- `AppLogger.init()` and launch/hang diagnostics setup.
- `AppDatabase.getInstance()` and repository construction.
- `SkillRepository`, `MCPRepository` and `MCPProvider.init()/reload()` setup.
- Soul/config-registry initialization and a large set of in-memory ToolRegistry registrations.

These operations have very different cost/risk profiles and must not be grouped under a generic “SharedPreferences is slow” diagnosis.

## Provider configuration is already off-main

The current `ProviderRepository` contains an explicit startup-stall fix. Its constructor creates an empty `MutableStateFlow` and starts the persisted provider config load on a `SupervisorJob + Dispatchers.IO` scope. The previous synchronous `SharedPreferences` + large JSON decode path is documented in source as having been removed from the constructor's main-thread critical path.

That means Issue #30 must not propose DataStore merely to solve the old provider-config stall without first showing a current regression in a trace.

## What still needs measurement

A baseline cold-start trace must separate at least these categories:

1. `Application.attachBaseContext` / ACRA initialization.
2. `Application.onCreate` before repository construction.
3. Room/database opening.
4. Repository constructors and any synchronous file/prefs/JSON work they trigger.
5. MCP startup/reload work.
6. Soul/config registry initialization.
7. MainActivity/Compose setup through first frame and fully drawn.
8. Post-first-frame background work.

For each synchronous main-thread read, record wall time and allocation cost. For asynchronous work, record whether it competes for CPU/I/O during first render even though it is not directly blocking the main thread.

## Reproducible device matrix

Use the same build type and app data state when comparing runs.

Capture at minimum:

- cold process start with an existing, non-trivial provider/MCP/skill configuration;
- warm process start with the same data;
- fresh-install/near-empty configuration as a control.

For each scenario collect multiple runs rather than relying on one launch, and report median plus a high-percentile sample for:

- process start → first frame;
- process start → fully drawn / usable main screen;
- main-thread time attributed to SharedPreferences/XML reads;
- main-thread JSON parsing;
- Room/database opening;
- repository constructors;
- MCP/skill/config setup;
- total main-thread blocked intervals above one frame budget.

Use a Perfetto/System Trace or equivalent Android startup trace that includes main-thread scheduling, frame timeline and disk I/O. Device profiling is a controlled validation step; no automated `adb` or device operation is performed by this branch.

## Decision rules

Choose the smallest fix supported by the trace:

- Repeated cheap preference reads: cache only if they are actually repeated in the measured critical path.
- Expensive synchronous parsing/I/O: move it off-main if lifecycle semantics permit.
- Non-critical startup work: defer until after first render if doing so preserves correctness.
- DataStore migration: use only when it materially improves correctness/performance and remains the single source of truth; do not create a second config store.
- If configuration storage is not a material startup contributor, close or retarget Issue #30 to the measured dominant work instead.

Any optimization PR must include before/after measurements from the same device/build/data scenario.

## Current evidence boundary

Static source review proves that the two early preference primes are synchronous, while the historically expensive provider config load is already asynchronous. It does not prove which remaining startup component dominates wall time or frame misses. Because no device or `adb` operation was allowed for this work, Issue #30 remains open until the required cold/warm startup traces are attached.
