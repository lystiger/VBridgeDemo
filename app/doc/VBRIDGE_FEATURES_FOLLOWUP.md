# VBridge — Follow-Up Tickets (base commit `f48997b`)

> **For CLI coding agents.** Companion to `app/doc/VBRIDGE_FEATURES.md`. That brief's Phase 1 + 3.1 are
> already merged; this file covers what's left. Base everything on commit **`f48997b`**. Same rules:
> execute tickets in order, exact file paths, code sketches are *sketches* (make them compile), verify every
> acceptance checkbox without asking.

---

## 0. Current state at `f48997b` — DO NOT REDO

Already merged and verified — do not reimplement these:
- `InterpreterPipeline`: send-failure guarded by `transport.isRelayActive`; injectable `elapsedRealtimeMs`
  clock; `internal suspend fun enqueueAudioTurn(...)` test hook.
- `TranslationTransport.isRelayActive` (interface) + `VBridgeSocket` override + `NoOpTransport`.
- `MeetingViewModel`: `enum ConnectivityMode { Solo, Room }`, `enum MtEngine { OnDevice, Remote }`,
  `_mode`/`mode`, `_mtEngine`/`mtEngine`, `setConnectivityMode(...)` (works), `setMtEngine(...)` (**stub — see
  FR-1**).
- `MainScreen` renders `VBridgeConversation`; `ChatListScreen.kt` and the legacy `VBridgeViewModel` deleted.
- `InterpreterPipelineTest`: relay-inactive-no-Failed test; `FakeTransport` now has `isRelayActive`/`sendResult`.
- `translation/FallbackTranslator.kt` and `translation/LanServerTranslator.kt` **exist but are dead code**
  (the pipeline never receives them — see FR-1).

**Invariants (unchanged):** on-device path always works with zero network; `MlKitTranslator` is the
guaranteed fallback; don't break existing `PipelineEvent` fields; keep ASR/MT/TTS off the main thread.

---

## FR-1 — Make MT-engine switching actually work  🔴 critical (fixes dead code)

**Why:** `translator` is a constructor param of `InterpreterPipeline`, so `setMtEngine` flipping a
`StateFlow` does nothing — `initializePipeline()` hardcodes `MlKitTranslator()`. `FallbackTranslator` /
`LanServerTranslator` are never reached. Fix by mutating the translator in place (mirror how Solo/Room
mutates the socket instead of rebuilding).

**Files:** new `translation/DelegatingTranslator.kt`; `app/build.gradle.kts`; `pipeline/MeetingViewModel.kt`.

### 1a. Add a LAN endpoint config (none exists today)

`app/build.gradle.kts` — mirror the existing relay field:

```kotlin
val lanUrl = providers.gradleProperty("VBRIDGE_LAN_URL").getOrElse("http://REPLACE_WITH_LAN_HOST:8000")
buildConfigField("String", "VBRIDGE_LAN_URL", "\"$lanUrl\"")
```

### 1b. Runtime-swappable translator

`translation/DelegatingTranslator.kt` — owns the leaf translators, does fallback inline, single `close()`:

```kotlin
class DelegatingTranslator(
    private val onDevice: Translator,
    private val remote: Translator? = null
) : Translator {
    @Volatile var useRemote: Boolean = false
    override suspend fun translate(text: String, direction: Direction): TranslationResult {
        val r = remote
        return if (useRemote && r != null)
            runCatching { r.translate(text, direction) }
                .getOrElse { onDevice.translate(text, direction).copy(modelName = "MLKit (fallback)") }
        else onDevice.translate(text, direction)
    }
    override fun close() { onDevice.close(); remote?.close() }
}
```

> This subsumes `FallbackTranslator` for the runtime-switch path. Either delete `FallbackTranslator.kt`, or
> keep it only for a static Remote-only build — but ensure exactly **one** owner calls `close()` on the leaf
> translators (avoid double-closing `MlKitTranslator`).

### 1c. Wire it

`pipeline/MeetingViewModel.kt`, in `initializePipeline()`:

```kotlin
private var switchableTranslator: DelegatingTranslator? = null
// ...
val mlkit = MlKitTranslator()
val lan = LanServerTranslator(LanFallbackClient(BuildConfig.VBRIDGE_LAN_URL))
val delegating = DelegatingTranslator(onDevice = mlkit, remote = lan)
switchableTranslator = delegating
this.translator = delegating
// pass `delegating` as the `translator` arg to InterpreterPipeline(...)
```

Replace the `setMtEngine` stub:

```kotlin
fun setMtEngine(engine: MtEngine) {
    switchableTranslator?.useRemote = (engine == MtEngine.Remote)
    _mtEngine.value = engine
}
```

**Acceptance:**
- [ ] `Remote` + LAN reachable → completed turn's `TranslationResult.modelName == "LAN"`.
- [ ] `Remote` + LAN unreachable → turn still completes via `"MLKit (fallback)"`, no dropped turn, no `Error`.
- [ ] Toggling engine mid-session changes behavior with **no** pipeline recreation.
- [ ] `OnDevice` (default) is byte-for-byte the current behavior.
- [ ] `MlKitTranslator` is closed exactly once on `onCleared()`.

---

## FR-2 — Fix `LanServerTranslator` response parsing

**File:** `translation/LanServerTranslator.kt`

Today it treats the LAN response as a bare string. Confirm the server's actual shape first; if it returns
JSON (e.g. `{"translation": "..."}` or `{"text": "..."}`), parse it. Keep the throw-on-empty so
`DelegatingTranslator` can fall back.

```kotlin
val body = client.translate(text, direction.mlkitSource, direction.mlkitTarget)
if (body.isBlank()) throw IllegalStateException("LAN translate empty/failed")
val out = runCatching { JSONObject(body).optString("translation").ifBlank { JSONObject(body).getString("text") } }
    .getOrDefault(body.trim()) // tolerate a bare-string server
if (out.isBlank()) throw IllegalStateException("LAN translate parse failed")
```

**Acceptance:**
- [ ] Given the server's real response, returns the correct translated text.
- [ ] Malformed/empty response throws → `DelegatingTranslator` falls back to on-device.

---

## FR-3 — Floor control (make half-duplex visible)

**Why:** the only turn guard is `InterpreterPipeline.isMutedForPlayback`, which silently no-ops
`startRecording()` while remote TTS plays — users think the mic is broken.

**Files:** `pipeline/MeetingViewModel.kt`, `MainActivity.kt`.

`MeetingViewModel`:

```kotlin
enum class Floor { Open, LocalSpeaking, RemoteSpeaking }
private val _floor = MutableStateFlow(Floor.Open)
val floor: StateFlow<Floor> = _floor.asStateFlow()
```

Drive from branches already in `handlePipelineEvent`:
- `SpeechStarted` → `LocalSpeaking`
- `PlaybackStarted(isLocal = false)` → `RemoteSpeaking`
- `Translated(isLocal = true)` and `PlaybackCompleted(isLocal = false)` → `Open`
- `Failed` → `Open` (don't let the floor get stuck)

`MainScreen`: collect `floor`; when `floor != Open`, disable `RecordingMicFAB` and show a caption
("It's their turn…" for `RemoteSpeaking`, "Listening…" for `LocalSpeaking`).

**Acceptance:**
- [ ] While a remote turn is spoken, the mic FAB is visibly disabled + captioned (not silently dead).
- [ ] Floor returns to `Open` after remote playback or on `Failed`.
- [ ] Local recording still blocked during remote playback (existing `isMutedForPlayback` behavior intact).

---

## FR-4 — Hands-on (PTT) vs hands-free capture mode

**Why:** FAB is tap-to-toggle, but `EmptyState` says "Hold the button and speak." Add a real mode; no
pipeline change needed (`startRecording()`/`stopRecording()` already suit press/release).

**Files:** `pipeline/MeetingViewModel.kt`, `ui/components/MainDashboard.kt`, `MainActivity.kt`,
`ui/conversation/VBridgeConversation.kt`.

`MeetingViewModel`: `enum class CaptureMode { HandsOn, HandsFree }` + `_captureMode`/`captureMode` +
`setCaptureMode(...)`.

`RecordingMicFAB` (new signature):

```kotlin
@Composable
fun RecordingMicFAB(
    isRecording: Boolean,
    enabled: Boolean = true,            // wire to floor == Open
    captureMode: CaptureMode,
    onToggle: () -> Unit,               // HandsFree
    onPressStart: () -> Unit,           // HandsOn
    onPressEnd: () -> Unit,
)
```

Inside: `HandsFree` → keep `LargeFloatingActionButton(onClick = onToggle)`. `HandsOn` → hold gesture:

```kotlin
Modifier.pointerInput(enabled, captureMode) {
    if (!enabled) return@pointerInput
    detectTapGestures(onPress = {
        onPressStart()
        tryAwaitRelease()   // suspends until finger lifts
        onPressEnd()
    })
}
```

`MainScreen` wires `onPressStart = viewModel::startRecording`, `onPressEnd = viewModel::stopRecording`,
`onToggle` = the existing start/stop-toggle logic, `enabled = floor == Floor.Open`.

Fix the copy contradiction: pass a mode-aware hint into `VBridgeConversation` (e.g. `captureHint: String`)
used by `EmptyState`, or make `EmptyState` copy generic ("Tap or hold the mic and speak").

**Acceptance:**
- [ ] `HandsOn`: audio captured only while held; one release → exactly one turn.
- [ ] `HandsFree`: current tap-start/tap-stop unchanged.
- [ ] Empty-state copy matches the active mode.
- [ ] Holding while `floor == RemoteSpeaking` does nothing (FAB `enabled = false`), unless FR-6 is on.

---

## FR-5 — Expose the toggles + fix the badge label

**Why:** `ConnectivityMode`, `MtEngine`, and (after FR-4) `CaptureMode` exist in the ViewModel but no UI
reaches them. Also, in Solo mode the `TopBar` badge shows "Offline", conflating intentional solo operation
with a dropped connection.

**Files:** `MainActivity.kt` (`MainScreen`, `VBridgeTopBar` `onSettingsClick`), `ui/components/TopChatBar.kt`.

- Wire the existing (currently TODO) Settings icon to open a `ModalBottomSheet` with three controls:
  Connectivity (Solo/Room), Translation engine (On-device/Remote), Capture (Hands-on/Hands-free) — e.g.
  `SegmentedButton` rows calling `setConnectivityMode` / `setMtEngine` / `setCaptureMode`.
- Badge label: derive from mode first, then connection:

```kotlin
val (connText, connColor) = when {
    mode == ConnectivityMode.Solo -> "Solo" to Color(0xFF64748B)   // neutral, not an error
    connectionState is NetworkEvent.Connected -> "Connected" to Color(0xFF22C55E)
    connectionState is NetworkEvent.Connecting -> "Connecting…" to Color(0xFFFFC107)
    connectionState is NetworkEvent.Error -> "Error" to Color.Red
    else -> "Disconnected" to Color.Gray
}
```

**Acceptance:**
- [ ] All three modes are switchable from the UI and persist for the session.
- [ ] Solo mode shows a neutral "Solo" badge, never "Offline"/red.
- [ ] Switching Room→Solo→Room reconnects the relay correctly (calls `setConnectivityMode`).

---

## FR-6 — (Optional) Barge-in

**File:** `MainActivity.kt` (+ small `MeetingViewModel` helper).

- [ ] Long-press the mic during `Floor.RemoteSpeaking` → `playback.stop()`, reset floor to `Open`, start local
      capture. Gate behind a setting; default **off**.

---

## PR slicing & global DoD

1. **PR1 — FR-1** (+FR-2): makes the Remote engine real. Highest value, unblocks the dead code.
2. **PR2 — FR-3**: floor control.
3. **PR3 — FR-4**: capture mode.
4. **PR4 — FR-5**: toggle UI + badge fix (depends on FR-1/3/4 existing in the VM).
5. FR-6: on request only.

**Global definition of done:**
- [ ] `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` pass.
- [ ] Invariants (Section 0) hold.
- [ ] Airplane-mode smoke test: Solo + Hands-on + On-device interprets and speaks locally, badge reads "Solo".
- [ ] LAN smoke test: Remote engine returns `"LAN"` when the server is up, `"MLKit (fallback)"` when it's down.
