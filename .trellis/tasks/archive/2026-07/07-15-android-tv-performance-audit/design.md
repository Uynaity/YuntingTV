# Audit Design

## Boundaries

This is a read-only performance review. Production code, dependencies, and
runtime behavior will not be modified. Trellis planning/status files may be
updated to record the audit lifecycle.

## Evidence Model

A reportable finding must identify a concrete repeated or latency-sensitive
path and connect it to CPU, allocation/GC, I/O, network, rendering, memory, or
power cost. Severity combines frequency, affected workflow, device sensitivity,
and whether degradation can become unbounded.

Potential improvements without enough evidence are kept as profiling notes,
not findings. Existing mitigations are taken into account.

## Review Areas

1. Application startup, service/lifecycle ownership, and release configuration.
2. ViewModel flow topology, coroutine cancellation, polling, and state copying.
3. Compose recomposition, lazy containers, focus events, animation, and image work.
4. Retrofit/OkHttp construction, serialization, API fan-out, caching, and mapping.
5. ExoPlayer buffering, retries, media-source creation, and background session work.
6. DataStore reads/writes and large collection transformations.

## Validation

Use source-level tracing and repository search, then run available Gradle
compile/lint/test tasks when feasible. Dynamic costs that cannot be proven in
this environment will be paired with a concrete Macrobenchmark, Perfetto,
Layout Inspector, or Android Studio profiler validation method.
