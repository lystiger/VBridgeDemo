# VBridge — Implementation Brief: Offline/Online Mode, Turn-Taking, Hands-On Capture

> **For CLI coding agents.** This spec is self-contained. Execute phases in order. Each task lists exact
> files, the change, a code sketch (adapt to compile — sketches are not copy-paste-final), and acceptance
> criteria you can verify without asking. Do not skip acceptance criteria.

---

## 0. Context & Invariants

**Repo:** `lystiger/VBridgeDemo` — Android (Kotlin, Jetpack Compose, Material3). Package root
`com.example.demovbridge`, sources under `app/src/main/java/com/example/demovbridge/`.

**What it is:** a real-time VN↔EN speech interpreter. Pipeline: mic → VAD → ASR → MT → (relay) → TTS → playback.

**Current tech, by stage:**
- ASR/VAD/TTS: **Sherpa-ONNX, fully on-device** (`asr/SherpaAsr`, `vad/SherpaVad`, `tts/SherpaTts`).
- MT: `translation/MlKitTranslator` — **on-device** (ML Kit), the only wired translator.
- Relay: `network/VBridgeSocket` — **the only online dependency**; a room-based WebSocket that shares
  translated turns between participants. URL from `BuildConfig.VBRIDGE_RELAY_URL`.
- `translation/PhoMtTranslator` — placeholder, throws `NotImplementedError`.
- `net/LanFallbackClient` — an HTTP client to a LAN `/translation` endpoint. **Defined but unused**, and it
  is *not* a `Translator` (returns the raw response body).

**Key insight — two independent axes. Do not conflate them:**
- **Axis A — Peer relay** (`VBridgeSocket`): Solo handheld interpreter vs. two-way Room.
- **Axis B — Translation engine**: on-device (`MlKit`/`PhoMT`) vs. remote (`LanFallbackClient`/cloud).

**Hard invariants — every phase must preserve these:**
1. **On-device path always works with zero network.** Solo mode with no server reachable must still
   transcribe → translate → speak locally.
2. **`MlKitTranslator` stays the guaranteed fallback.** Never make it possible for a remote MT failure to
   drop a turn.
3. **No breaking changes to `PipelineEvent`'s existing fields.** UI and `benchmark/LatencyTracer` read them.
4. Keep everything on `Dispatchers.Default`/`IO` as today; do not move ASR/MT/TTS onto the main thread.

**Existing tests:** `app/src/test/.../pipeline/InterpreterPipelineTest.kt`. Extend, don't rewrite.

---

## Phase 1 — Offline/Online correctness (Axis A)  ⟵ do this first

### 1.1 Fix: offline turns are wrongly marked Error  🔴 bug

**Files:** `pipeline/InterpreterPipeline.kt`, `pipeline/MeetingViewModel.kt`

**Problem:** In `InterpreterPipeline` stage 3 (Translation → Network), the local `PipelineEvent.Translated`
is emitted (row → `Complete`), then `transport.send()` runs. When the relay is down, `send()` returns
`TransportSendResult.Failure` → the code emits `PipelineEvent.Failed(Stage.Network, ...)` for the **same
`turnId`**. In `MeetingViewModel.handlePipelineEvent`, the `Failed` branch overwrites that row to
`TurnStatus.Error`. **Result: every successful local translation turns red the moment you're offline.**

**Change:** a transport send failure is only a *turn* failure when the relay is supposed to be on.

```kotlin
// InterpreterPipeline stage 3, replacing the current failure emit:
val sendResult = transport.send(event)
if (sendResult is TransportSendResult.Failure && transport.isRelayActive) {
    _events.emit(
        PipelineEvent.Failed(pending.turnId, PipelineEvent.Stage.Network, sendResult.message, usedFallback = false)
    )
}
```

(`isRelayActive` is added in 1.2.)

**Acceptance:**
- [ ] With no relay reachable, speak a VN phrase in Solo mode → the local bubble shows the EN translation
      with `TurnStatus.Complete` (never `Error`) and is spoken aloud.
- [ ] With the relay reachable and then killed mid-session, a genuine send failure while in Room mode still
      surfaces a `Failed(Network)` event.

### 1.2 Add `isRelayActive` to the transport seam

**Files:** `pipeline/PipelineInterfaces.kt`, `network/VBridgeSocket.kt`

```kotlin
// PipelineInterfaces.kt — in interface TranslationTransport
val isRelayActive: Boolean
```

```kotlin
// VBridgeSocket.kt
override val isRelayActive: Boolean
    get() = socket != null && currentRoomId != null
```

**Acceptance:**
- [ ] Project compiles; every `TranslationTransport` implementor overrides `isRelayActive`.

### 1.3 `ConnectivityMode` (Solo / Room) in the ViewModel

**File:** `pipeline/MeetingViewModel.kt`

Model the mode explicitly instead of inferring it from socket state. Toggle it at runtime by
connecting/disconnecting the existing socket — **do not** swap the `transport` instance (it is a constructor
param of `InterpreterPipeline` and cannot be replaced mid-flight).

```kotlin
enum class ConnectivityMode { Solo, Room }   // Axis A

private val _mode = MutableStateFlow(ConnectivityMode.Room)
val mode: StateFlow<ConnectivityMode> = _mode.asStateFlow()

fun setConnectivityMode(newMode: ConnectivityMode) {
    when (newMode) {
        ConnectivityMode.Solo -> network.disconnect()          // relay off; pipeline keeps running locally
        ConnectivityMode.Room -> network.connect(config.roomId) // relay on
    }
    _mode.value = newMode
}
```

Because `disconnect()` clears `currentRoomId`/`socket`, `isRelayActive` becomes false in Solo mode, so the
1.1 guard swallows the (expected) send failures. On-device ASR/MT/TTS are untouched, so Solo still speaks
the translation aloud for the person across from you.

**Acceptance:**
- [ ] Toggling to Solo stops relay traffic; local interpret-and-speak still works end to end.
- [ ] Toggling to Room reconnects and remote turns resume arriving/speaking.

### 1.4 (Optional) `NoOpTransport` for a relay-free build variant

**File (new):** `pipeline/NoOpTransport.kt` — only if a pure-offline build flavor is wanted. Runtime toggling
is handled by 1.3; this is for compile-time exclusion of the socket.

```kotlin
class NoOpTransport : TranslationTransport {
    override val events = MutableSharedFlow<NetworkEvent>().asSharedFlow()
    override val isRelayActive get() = false
    override suspend fun send(event: TranslationEvent) = TransportSendResult.Success
    override fun disconnect() {}
    override fun destroy() {}
}
```

**Acceptance:**
- [ ] Injecting `NoOpTransport` into the pipeline runs Solo-only with no `okhttp` socket created.

### 1.5 Regression test

**File:** `app/src/test/.../pipeline/InterpreterPipelineTest.kt`

- [ ] Add a test: given a fake `TranslationTransport` where `isRelayActive == false` and `send()` returns
      `Failure`, a completed turn emits `Translated` and **no** `Failed(Network)` event.

---

## Phase 2 — Translation engine fallback (Axis B)

### 2.1 Make the LAN client a real `Translator`

**Files:** `net/LanFallbackClient.kt` (reuse), new `translation/LanServerTranslator.kt`

`LanFallbackClient` returns a raw body and swallows errors into `""`. Wrap it so failures are *thrown*
(needed for fallback to trigger) and the result is a proper `TranslationResult`.

```kotlin
class LanServerTranslator(private val client: LanFallbackClient) : Translator {
    override suspend fun translate(text: String, direction: Direction): TranslationResult {
        val start = SystemClock.elapsedRealtime()
        val out = client.translate(text, direction.mlkitSource, direction.mlkitTarget)
        if (out.isBlank()) throw IllegalStateException("LAN translate empty/failed")
        return TranslationResult(text = out, latencyMs = SystemClock.elapsedRealtime() - start, modelName = "LAN")
    }
    override fun close() {}
}
```
> Note: if the LAN endpoint returns JSON rather than a bare string, parse it here. Confirm the server's
> response shape before finalizing.

### 2.2 `FallbackTranslator` — remote-preferred, on-device-guaranteed

**File (new):** `translation/FallbackTranslator.kt`

```kotlin
class FallbackTranslator(
    private val primary: Translator,   // LanServerTranslator or (later) PhoMtTranslator
    private val onDevice: Translator   // MlKitTranslator — always succeeds
) : Translator {
    override suspend fun translate(text: String, direction: Direction): TranslationResult =
        runCatching { primary.translate(text, direction) }
            .getOrElse { onDevice.translate(text, direction).copy(modelName = "MLKit (fallback)") }
    override fun close() { primary.close(); onDevice.close() }
}
```

### 2.3 Wire an engine selector

**File:** `pipeline/MeetingViewModel.kt`

- Add `enum class MtEngine { OnDevice, Remote }` and build the `translator` accordingly:
  `OnDevice` → `MlKitTranslator()`; `Remote` → `FallbackTranslator(LanServerTranslator(...), MlKitTranslator())`.
- `MlKitTranslator` remains the default (invariant #2).

**Acceptance:**
- [ ] With `Remote` selected and the LAN server up, `TranslationResult.modelName == "LAN"`.
- [ ] With `Remote` selected and the LAN server down, the turn still completes via `"MLKit (fallback)"`.
- [ ] `PhoMtTranslator` can be swapped in as `primary` later with zero pipeline changes.

---

## Phase 3 — Chat bubbles taking turns

### 3.1 Consolidate on one renderer

**Files:** `MainActivity.kt` (`MainScreen`), `ui/conversation/VBridgeConversation.kt`,
`ui/components/ChatListScreen.kt`

`MainScreen` currently renders `ChatHistoryList` (from `ChatListScreen.kt`), but the richer
`VBridgeConversation` already implements proper turn-taking bubbles: local turns slide in from the right
(`primaryContainer`, tail bottom-end), remote from the left (`secondaryContainer`), animated direction
header, and per-turn `Transcribing/Translating/Complete/Error` states via `TurnBody`.

- [ ] Replace the `ChatHistoryList(...)` call in `MainScreen` with
      `VBridgeConversation(turns = uiTurns, onRetry = viewModel::retryTurn)`.
- [ ] Delete the now-unused `VBridgeViewModel` inside `VBridgeConversation.kt` (the real state lives in
      `MeetingViewModel`).
- [ ] Remove `ChatListScreen.kt` / `ChatHistoryList` if nothing else references it (grep first).

### 3.2 Explicit floor control (half-duplex made visible)

**Files:** `pipeline/MeetingViewModel.kt`, `MainActivity.kt`

Today the only turn guard is `InterpreterPipeline.isMutedForPlayback`, which silently makes
`startRecording()` a no-op while remote TTS plays — the user just thinks the mic is broken. Surface it.

```kotlin
// MeetingViewModel
enum class Floor { Open, LocalSpeaking, RemoteSpeaking }
private val _floor = MutableStateFlow(Floor.Open)
val floor: StateFlow<Floor> = _floor.asStateFlow()
```

Drive it from events already emitted in `handlePipelineEvent`:
- `SpeechStarted` → `LocalSpeaking`
- `PlaybackStarted(isLocal = false)` → `RemoteSpeaking`
- `Translated(isLocal = true)` and `PlaybackCompleted(isLocal = false)` → `Open`

In `MainScreen`: when `floor != Floor.Open`, disable `RecordingMicFAB` and show a small "It's their turn…" /
"Listening…" indicator so the half-duplex behavior is legible.

**Acceptance:**
- [ ] While a remote turn is being spoken, the mic FAB is visibly disabled and labeled, not silently dead.
- [ ] Floor returns to `Open` after remote playback completes.

### 3.3 (Optional) Barge-in

- [ ] Long-press the mic during `RemoteSpeaking` → call `playback.stop()`, reset floor to `Open`, start local
      capture. Gate behind a setting; default off.

---

## Phase 4 — Hands-on (push-to-talk) capture

### 4.1 Capture-mode switch

**Files:** `MainActivity.kt` (`MainScreen`), `ui/components/MainDashboard.kt` (`RecordingMicFAB`)

There is a UX contradiction today: `EmptyState` copy says *"Hold the button and speak,"* but the FAB is a
tap-to-toggle. Introduce a real mode and fix the copy to match whatever mode is active.

```kotlin
enum class CaptureMode { HandsOn, HandsFree }  // PTT vs continuous-VAD
```

**HandsOn** — replace the FAB `onClick` with a hold gesture (no pipeline change needed; `startRecording()`
launches capture, `stopRecording()` cancels the job whose `finally` flushes VAD → ASR):

```kotlin
Modifier.pointerInput(hasPermission) {
    detectTapGestures(onPress = {
        if (!hasPermission) return@detectTapGestures
        viewModel.startRecording()
        tryAwaitRelease()          // suspends until finger lifts
        viewModel.stopRecording()  // release → VAD.flush() → ASR fires
    })
}
```

**HandsFree** — keep the current tap-to-toggle (VAD continuously auto-segments).

**Acceptance:**
- [ ] In HandsOn, audio is captured only while the FAB is held; releasing produces exactly one turn.
- [ ] In HandsFree, tap-start/tap-stop behaves as today.
- [ ] `EmptyState` copy matches the active mode.
- [ ] Holding while `floor == RemoteSpeaking` does nothing (respects `isMutedForPlayback`), unless 3.3 is on.

---

## Phase 5 — (Flagged, not scheduled) Sign / hand translation

Only if "translate hands" was meant literally (sign-language input), not "hands-on PTT". This is a **sibling
of ASR**, not a rework: a recognizer that emits text into the same `mtIn` stage, reusing MT→TTS→relay
unchanged.

- Define `interface GestureRecognizer { fun stream(): Flow<String> }` (source: MediaPipe Hands, or an
  existing SilentVoix/GloveFlow model exported to on-device).
- Feed recognized text into the pipeline at the ASR output point (produce a `Transcribed`-equivalent turn),
  so `Direction`, MT, TTS, relay, and bubble rendering all work as-is.
- Add a source toggle: Voice (ASR) ↔ Signs (GestureRecognizer).

**Do not start Phase 5 without confirmation of intent.**

---

## Suggested execution order & PR slicing

1. **PR1:** Phase 1.1–1.2 + 1.5 (bug fix + seam + test). Smallest, highest value.
2. **PR2:** Phase 1.3 (+1.4 if wanted) — Solo/Room toggle + UI badge.
3. **PR3:** Phase 3.1–3.2 — renderer consolidation + floor control.
4. **PR4:** Phase 4 — capture mode.
5. **PR5:** Phase 2 — MT fallback chain.
6. Phase 3.3 / Phase 5: only on request.

## Global definition of done
- [ ] `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` pass.
- [ ] Invariants 1–4 (Section 0) hold.
- [ ] Airplane-mode manual smoke test: Solo + HandsOn interprets and speaks locally with zero network.
