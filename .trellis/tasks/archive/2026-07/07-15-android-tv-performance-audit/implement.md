# Audit Plan

- [x] Inventory all production code and locate recurring work, polling, retries,
      collection transforms, image decoding, and blocking calls.
- [x] Trace startup, ViewModel initialization, source changes, channel loading,
      playback, progress updates, and foreground/background transitions.
- [x] Inspect Compose screens/components for broad state subscriptions, unstable
      inputs, eager work, and animation/layout invalidation.
- [x] Inspect data/network/player layers for request fan-out, missing cancellation
      or caching, excess allocations, and unbounded recovery loops.
- [x] Inspect manifest and release/build configuration for performance-sensitive
      defaults.
- [x] Run relevant Gradle verification and consolidate only evidence-backed
      findings in severity order.
- [x] Record residual risks and targeted profiling recommendations.
