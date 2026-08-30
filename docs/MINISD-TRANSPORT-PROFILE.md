# Android ↔ minisd transport profile

Issue #28 is an investigation. This document records what can be proven from the current source tree and defines the device trace still required before any transport optimization is justified.

## Static transport map

There is no JNI hop in the current Android → `minisd` request path.

The primary App path is:

```text
suspend caller
  -> MinisdClient.call(...)
  -> Dispatchers.IO
  -> Android LocalSocket
  -> filesystem Unix socket: <app files>/minis/minisd.sock
  -> 4-byte length + UTF-8 JSON request
  -> minisd
  -> 4-byte length + UTF-8 JSON response
```

`MinisdClient.call()` encodes the request, performs LocalSocket I/O and runs `MinisdProtocol.decodeResponse()` inside `withContext(Dispatchers.IO)`. The LocalSocket request is bounded by `MAX_REQUEST_BYTES`; the response is bounded by `MAX_RESPONSE_BYTES` and the socket read has the method timeout.

If the app socket is missing/unreachable, or rejects the App identity, the fallback is:

```text
MinisdClient.call(...)
  -> Dispatchers.IO
  -> su -c "/data/adb/minis/bin/minisd --call --socket /data/adb/minis/run/minisd.sock"
  -> the same JSON request on stdin
  -> fixed minisd Unix socket
  -> JSON response on stdout
```

The `su -c` command string is fixed transport/bootstrap text. Agent command argv is not interpolated into that shell string; it remains inside the framed/JSON `minisd` request.

## Call granularity

The current protocol is command-granular, not token-granular.

- `ubuntu.status`, start, stop, provision and privileged calls are one RPC per requested operation.
- `ubuntu.exec` sends the complete argv/env/cwd request and receives the completed captured result in one response.
- `UbuntuRuntime.shell()` performs one `ubuntu.exec` for `/bin/bash -lc <command>`. Its `lineCallback` iterates the already-returned output afterward; it is not a stream of repeated Android ↔ `minisd` RPCs.
- Structured keeper recovery may add a stop/status/start sequence and retry the failed exec once when `KEEPER_NAMESPACE_LOST` is proven.
- `awaitBroker()` is the most obvious polling path: during broker startup it can issue up to ten `ubuntu.status` calls, spaced 300 ms apart.

Therefore normal provider token streaming should not be assumed to imply frequent broker crossings. A device trace still has to verify whether unrelated runtime-health/tool activity overlaps a streaming turn.

## Thread placement

The transport boundary itself is explicitly off-main: `MinisdClient.call()` enters `Dispatchers.IO` before JSON encoding, socket/process I/O and response decoding.

Higher-level code can resume on its caller dispatcher after the suspend call completes. In particular, `UbuntuRuntime.shell()` performs result extraction, stdout/stderr joining and optional `lineCallback` delivery after `ubuntuExec()` returns. A device trace must distinguish that post-response work from broker transport time instead of attributing the whole shell path to IPC.

## Instrumentation added for this investigation

`MinisdTransportStats` keeps bounded aggregate counters keyed only by:

- RPC method;
- transport (`LOCAL_SOCKET`, `SU_CALL`, or no transport);
- outcome/error code;
- whether a fallback occurred.

Each bucket records call count, UTF-8 JSON request/response payload bytes, total elapsed time and maximum elapsed time.

The profiler deliberately does **not** store request JSON, command argv, environment variables, tokens, stdout or stderr. `MinisdResponse.wireBytes` carries only the decoded response byte count so the aggregation can measure payload size without retaining payload content.

These counters are process-local investigation data. They are not a telemetry upload and do not add per-call file/log writes that would contaminate the latency being measured.

## Required device profile

Issue #28 cannot be closed from static analysis alone. On a device build containing these counters, capture the following four scenarios with the same build and logging conditions:

1. Normal streaming chat with no Linux/Android tool calls.
2. A repeated-small-call workload (file/tool operations representative of actual Agent use).
3. One shell command that produces many output lines.
4. Runtime health/recovery, including broker start/recovery if reproducible.

For each scenario record:

- `MinisdTransportStats.snapshot()` before and after the scenario, then subtract counters;
- a Perfetto/System Trace containing Android frame timeline and main-thread scheduling;
- scenario start/end timestamps or trace markers sufficient to align the counter window with the frame trace;
- whether local socket or `SU_CALL` fallback was used;
- frame misses/jank during the same window;
- any material filesystem or Compose/render work visible in the trace.

Do not use `adb` as part of an automated app test. Device profiling is a manual/controlled validation step and must follow the operator's device-access rules.

## Decision rule

Do not implement batching, shared memory, DirectByteBuffer or JNI-specific changes merely because an RPC exists.

A transport optimization is justified only when the device evidence shows broker communication contributes materially to the affected frame window or end-to-end latency. Any optimization PR must include before/after measurements from the same scenario.

If the normal streaming scenario has zero broker calls, or broker work is off-main and small relative to rendering/filesystem/provider work, close the speculative transport-bottleneck hypothesis and optimize the measured hot path instead.

## Current evidence boundary

This branch provides the source-level transport/thread audit and measurement hooks. It intentionally does not claim the required device/frame trace has been collected, because no device or `adb` operation was performed while implementing this investigation. Consequently Issue #28 should remain open until the four-scenario device evidence is attached.
