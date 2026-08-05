# Android TV Performance Audit

## Findings

1. **High: channel loads and refreshes lack cancellation, de-duplication, and
   request-version checks.** Startup, lifecycle resume, last-play restoration,
   filter changes, and channel changes can issue overlapping full-list calls.
   Slow stale responses can overwrite the current filter result. Consolidate
   the request key into `flatMapLatest`, or cancel the prior job and validate a
   generation key before committing results. Do not refresh the full list on
   every channel selection.

2. **High within the update workflow: APK cancellation does not stop the
   blocking OkHttp call, and progress is emitted for every 8 KiB.** Bind
   coroutine cancellation to `Call.cancel()`, check cancellation in the copy
   loop, preserve `CancellationException`, write a temporary partial file, and
   throttle UI progress by percentage or 100-250 ms.

3. **Medium, pending device measurement: API 31+ fullscreen playback applies a
   60 dp blur to a fullscreen scaled image.** This requires a large render
   effect/offscreen layer and can pressure low-end TV GPUs and memory. Reuse the
   low-resolution pre-blurred bitmap strategy on all API levels and cache by
   image URL. Static layer caching means continuous per-frame cost is not proven.

4. **Medium: the 500 ms progress ticker runs for the whole ViewModel lifetime.**
   It keeps waking while no channel is selected or the Activity is stopped, and
   progress is observed at the root screen scope. Start it only while needed,
   stop or reduce it without UI subscribers, and collect progress in the player
   subtree. Compose may skip the grid, so full-grid recomposition is not claimed.

5. **Medium-low startup risk: ViewModel creation immediately connects the media
   service and constructs ExoPlayer while content and update requests start.**
   Lazily connect on auto-play/first control, while retaining reconnection to an
   existing background session. Delay update checking until after first content.

6. **Medium-low: persistent playback errors rebuild the media source every three
   seconds.** The retry is capped at 60 seconds, but permanent failures can still
   rebuild the HLS tracker roughly 20 times. Classify retryable errors and use
   network-aware exponential backoff with jitter.

7. **Low: unrelated DataStore writes re-decode both favorites JSON values.**
   Distinct the raw stored strings before decoding, cache/share decoded flows,
   and avoid encoding/writing unchanged snapshots. Favorite refresh also scales
   by distinct source/province and should use TTL caching or a per-channel API.

## Validation

- `./gradlew testDebugUnitTest lintDebug`: successful.
- Unit tests: no test source (`NO-SOURCE`).
- Lint: 0 errors, 17 warnings; none invalidates the findings above.
- Production source was not changed.

## Dynamic Follow-up

- Count cold-start and rapid-filter API calls with MockWebServer or an OkHttp
  event listener, including deliberately reversed response order.
- Measure cold startup with Macrobenchmark/Perfetto before and after lazy media
  initialization and delayed update checking.
- Compare fullscreen frame timing and GPU memory for RenderEffect versus a
  cached 256/512 px pre-blurred bitmap on representative API 31+ TV hardware.
- Cancel a large APK download and verify socket closure and file-size stability.
- Use Compose recomposition counts to verify the exact progress invalidation scope.
