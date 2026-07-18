# VBridge Sherpa Pipeline — Implementation Handoff

> Purpose: give a coding agent enough precise repository context to implement or repair the Sherpa-based speech pipelines without inventing contracts. This document describes the code as it exists on 2026-07-18, then gives the required target behavior. Statements labeled **Current** are observations; statements labeled **Required** are implementation requirements.

## 1. Product and runtime scope

VBridge is a two-party, push-to-talk Android interpreter for Vietnamese and English. Each device:

1. captures its local speaker as 16-bit mono PCM;
2. recognizes the speaker's configured language locally with Sherpa ONNX;
3. translates the transcript locally with ML Kit;
4. sends text plus metadata through a WebSocket relay;
5. receives the other participant's translated text;
6. synthesizes that remote translation locally with Sherpa ONNX;
7. plays it while preventing microphone feedback.

There are only two supported directions:

| Direction | Source ASR | Translation | Remote TTS |
|---|---|---|---|
| `ViToEn` | Vietnamese | `vi -> en` | English |
| `EnToVi` | English | `en -> vi` | Vietnamese |

This is push-to-talk, not continuous automatic turn detection. The button press starts a turn and release finishes it. A VAD model is present, but the current orchestrator does not use it.

## 2. Repository map

The application module is `app`; package root is `com.example.demovbridge`.

| Area | File | Responsibility |
|---|---|---|
| Orchestration | `pipeline/InterpreterPipeline.kt` | Channels, stage workers, event emission, network-to-TTS path |
| UI adapter | `pipeline/MeetingViewModel.kt` | Constructs models, owns pipeline, converts events to UI state |
| Public stage events | `pipeline/PipelineEvent.kt` | In-process event contract |
| Direction | `pipeline/DirectionState.kt` | Language mapping used by ASR and ML Kit |
| VAD | `vad/SherpaVad.kt` | Silero wrapper; currently disconnected from orchestration |
| ASR | `asr/SherpaAsr.kt` | Offline transducer wrapper |
| TTS | `tts/SherpaTts.kt` | Offline VITS/Piper wrapper |
| Capture | `audio/AudioCapture.kt` | Android `AudioRecord` flow |
| Playback | `audio/AudioPlayback.kt` | Android `AudioTrack` writer |
| MT contract | `translation/Translator.kt` | Translation abstraction and result |
| MT baseline | `translation/MlKitTranslator.kt` | On-device ML Kit implementation |
| Relay | `network/VBridgeSocket.kt` | WebSocket wire serialization and reconnect |
| Diagnostics | `pipeline/PipelineDiagnostics.kt` | RTF, latency, memory, thread count |
| UI turn model | `ui/conversation/VBridgeConversation.kt` | Conversation state and Compose rendering |
| Model installer | `app/fetch_models.ps1` | Exact Sherpa model packages and asset layout |

`net/LanFallbackClient.kt`, `translation/PhoMtTranslator.kt`, `translation/Glossary.kt`, and `audio/AudioRingBuffer.kt` exist but are not connected to the production pipeline.

## 3. Toolchain and dependency versions

Do not silently change these while implementing the pipeline.

| Component | Version/configuration |
|---|---|
| Gradle wrapper | `9.4.1` |
| Android Gradle Plugin | `8.7.3` |
| Kotlin / Compose compiler plugin | `2.0.21` |
| Java/Kotlin bytecode target | Java 11 / JVM 11 |
| Gradle daemon toolchain | Java 21 |
| compile SDK / target SDK / min SDK | 35 / 35 / 24 |
| App version | `versionCode=1`, `versionName=1.0` |
| Sherpa ONNX Android | `com.github.k2-fsa:sherpa-onnx:v1.13.3` |
| ML Kit Translate | `17.0.3` |
| kotlinx-coroutines-play-services | `1.8.1` |
| OkHttp | `4.12.0` |
| DataStore Preferences | `1.1.1` |
| Supported native ABIs | `arm64-v8a`, `armeabi-v7a` |

The repository adds Sherpa's Maven repository and JitPack. Packaging resolves duplicate `libonnxruntime.so` and `libsherpa-onnx-jni.so` using `pickFirst`.

Required Android permissions are `RECORD_AUDIO` and `INTERNET`.

## 4. Model inventory and exact paths

All model paths below are Android asset-relative unless explicitly described as filesystem paths.

| Stage | Asset path(s) | Upstream package |
|---|---|---|
| VAD | `vad/silero_vad.onnx` | Sherpa release asset `asr-models/silero_vad.onnx` |
| Vietnamese ASR | `asr-vi/encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt` | `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09`; encoder/joiner are int8 |
| English ASR | `asr-en/encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt` | `sherpa-onnx-zipformer-small-en-2023-06-26`; encoder/joiner are int8 |
| English TTS | `tts-en/vits.onnx`, `tokens.txt`, `espeak-ng-data/` | `vits-piper-en_US-libritts_r-medium` |
| Vietnamese TTS | `tts-vi/vits.onnx`, `tokens.txt`, `espeak-ng-data/` | `vits-piper-vi_VN-vais1000-medium` |

Piper requires a real filesystem path for `dataDir`. `MeetingViewModel` recursively copies each `espeak-ng-data` directory from assets into:

- `<context.filesDir>/tts-en/espeak-ng-data`
- `<context.filesDir>/tts-vi/espeak-ng-data`

The ONNX model and tokens remain asset paths. Do not configure a lexicon for these Piper models; `lexiconPath` must remain empty.

## 5. Core data contracts

### 5.1 Direction

```kotlin
enum class Direction(val asrLang: String, val mlkitSource: String, val mlkitTarget: String) {
    ViToEn("vi", "vi", "en"),
    EnToVi("en", "en", "vi")
}
```

The direction must be snapshotted at turn start. Changing the UI direction during processing must not alter an already queued turn.

### 5.2 Internal pending turn

```kotlin
data class PendingAudioTurn(
    val turnId: String,             // UUID generated once at press/start
    val pcm: ShortArray,            // mono signed PCM16
    val direction: Direction,       // snapshot at turn start
    val speechEndedAtMs: Long       // elapsed realtime, not wall-clock epoch
)
```

### 5.3 Translation abstraction

```kotlin
data class TranslationResult(
    val text: String,
    val latencyMs: Long,
    val modelName: String,
    val confidence: Float? = null
)

interface Translator {
    suspend fun translate(text: String, direction: Direction): TranslationResult
}
```

`MlKitTranslator` lazily owns one translator per direction, downloads a model if needed, and runs translation from a suspend function. Empty input currently returns empty output and zero latency.

### 5.4 In-process pipeline event schema

Every timestamp is `SystemClock.elapsedRealtime()` in milliseconds. It is monotonic and device-local; never treat it as Unix time or compare values originating from different devices.

```text
SpeechStarted(turnId, tStart)
SpeechEnded(turnId, tEnd, pcm)
Transcribed(turnId, text, direction, tAsrDone, isLocal=true)
Translated(turnId, sourceText, translatedText, direction, tMtDone,
           speakerName=null, isLocal=true)
SpokenReady(turnId, pcm, tTtsDone, isLocal=true)
PlaybackStarted(turnId, tPlaybackStarted, isLocal=false)
PlaybackCompleted(turnId, tPlaybackCompleted, isLocal=false)
Failed(turnId, stage, message, usedFallback)
```

`Failed.stage` is one of `Vad`, `Asr`, `Translation`, `Network`, or `Tts`. There is currently no `Capture` or `Playback` stage. If the contract is extended, update all exhaustive `when` consumers and tests.

Semantics:

- `turnId` is the stable correlation key through capture, ASR, MT, relay, remote TTS, UI, and telemetry.
- `isLocal=true` means the event represents work originating from this device's speaker.
- Remote relay messages become local `Translated(..., isLocal=false)` events, followed by remote `SpokenReady`, `PlaybackStarted`, and `PlaybackCompleted` events.
- Local translations are sent over the relay but are not spoken on the originating device.
- `pcm` in public events is expensive. Preserve it only if diagnostics/UI truly consume it; otherwise a later contract revision should avoid exposing full audio arrays.

### 5.5 WebSocket wire schema

Endpoint:

```text
{VBRIDGE_RELAY_URL}/room/{roomId}
```

Default Gradle property is `wss://vbridge-relay.herokuapp.com`; it is compiled into `BuildConfig.VBRIDGE_RELAY_URL`. Allowed schemes are `ws://` and `wss://`.

Only one inbound/outbound message type exists:

```json
{
  "type": "translation",
  "eventId": "UUID string",
  "roomId": "room code",
  "speakerId": "stable participant UUID",
  "speakerName": "display name",
  "sourceLanguage": "vi",
  "targetLanguage": "en",
  "sourceText": "Xin chao",
  "translatedText": "Hello",
  "startedAt": 123456789,
  "endedAt": 123457321,
  "latencyMs": 532,
  "confidence": null
}
```

Field rules:

| Field | Type | Rule |
|---|---|---|
| `type` | string | exactly `translation` |
| `eventId` | string | source turn UUID; idempotency/deduplication key |
| `roomId` | string | receiver discards messages for a different current room |
| `speakerId` | string | receiver ignores its own ID to prevent echo |
| `speakerName` | string | displayed for the remote turn |
| language fields | string | exactly `vi` or `en`; source and target must be opposites |
| text fields | string | UTF-8 JSON strings; use a JSON encoder, never string interpolation |
| `startedAt`, `endedAt` | integer | currently sender-local elapsed realtime milliseconds |
| `latencyMs` | integer | translator-reported latency only, not full pipeline latency |
| `confidence` | number/null | optional; serialized explicitly as JSON null when absent |

The current receiver infers direction solely from `sourceLanguage`: `vi` means `ViToEn`; every other value becomes `EnToVi`. **Required:** validate both language fields explicitly and reject unsupported or inconsistent pairs.

## 6. Sherpa wrapper configurations

### 6.1 Audio capture contract

`AudioCapture` uses:

- `AudioRecord`
- source `MediaRecorder.AudioSource.VOICE_RECOGNITION`
- 16,000 Hz
- mono input
- signed PCM 16-bit
- internal buffer `2 * AudioRecord.getMinBufferSize(...)`
- emitted chunk size 480 samples = 30 ms at 16 kHz
- Android `NoiseSuppressor` enabled when available
- capture coroutine thread priority set to `THREAD_PRIORITY_AUDIO`

The flow owns and releases `AudioRecord`. Cancellation exits collection and triggers cleanup.

### 6.2 VAD configuration

Current `SherpaVad` configuration:

```text
model                 vad/silero_vad.onnx (caller-supplied)
sampleRate            16000 Hz
minSilenceDuration    0.5 s
minSpeechDuration     0.25 s
threshold             0.5
windowSize            512 samples (32 ms at 16 kHz)
numThreads            1
```

Input PCM16 is normalized with `sample / 32768.0f`. `process()` buffers chunks only while `vad.isSpeechDetected()` is true and returns a `List<ShortArray>` when detection flips false.

**Critical current mismatch:** capture emits 480-sample chunks while Silero is configured for 512-sample windows. More importantly, `InterpreterPipeline` never calls `vad.process()` at all. The current turn boundary is button release and all pressed audio goes directly to ASR.

### 6.3 ASR configuration

Each language owns a long-lived `OfflineRecognizer` configured as an offline transducer:

```text
encoder/decoder/joiner asset paths: language-specific
tokens asset path: language-specific
numThreads: 2
debug: false
input sample rate: 16000 Hz
```

For each utterance:

1. normalize PCM16 to float using `sample / 32768.0f`;
2. create a fresh recognizer stream;
3. `acceptWaveform(floatSamples, 16000)`;
4. `recognizer.decode(stream)`;
5. return `recognizer.getResult(stream).text`.

Recognition is synchronous/blocking and therefore must never execute on the main thread. Current orchestration runs it in a `Dispatchers.Default` pipeline worker.

### 6.4 TTS configuration

Each language owns a long-lived `OfflineTts` with:

```text
model: tts-{lang}/vits.onnx (asset)
tokens: tts-{lang}/tokens.txt (asset)
dataDir: copied filesystem espeak-ng-data path
lexicon: empty
numThreads: 2
debug: false
speaker/voice id: 0
```

`generate(text, sid=0)` returns float audio. Current conversion multiplies by 32767, clamps to signed 16-bit, and returns only `ShortArray`.

**Critical contract loss:** Sherpa's generated audio includes its own sample rate, but `SherpaTts.generate()` discards it. `AudioPlayback` is hard-coded to 16 kHz. Piper models may not output 16 kHz. **Required:** return an audio value containing both samples and sample rate, and configure/resample playback correctly.

Suggested contract:

```kotlin
data class SynthesizedAudio(val pcm: ShortArray, val sampleRate: Int)
```

## 7. Current pipeline topology

```text
LOCAL PUSH-TO-TALK
UI press
  -> AudioCapture Flow<ShortArray>
  -> aggregate all chunks until cancellation/release
  -> asrIn (capacity 4, SUSPEND)
  -> language-specific Sherpa offline ASR
  -> mtIn (capacity 8, SUSPEND)
  -> ML Kit translation
  -> WebSocket translation frame
  -> local Translated event/UI

REMOTE MESSAGE
WebSocket translation frame
  -> validate room
  -> reject own speakerId
  -> deduplicate eventId
  -> remote Translated event/UI
  -> ttsIn (capacity 8, DROP_OLDEST)
  -> target-language Sherpa TTS
  -> AudioPlayback
```

The pipeline uses one `CoroutineScope(Dispatchers.Default + SupervisorJob())`. It launches one long-lived collector/worker for network input, ASR, MT, and TTS. Consequently each stage processes serially, while different stages may overlap.

Backpressure contracts:

| Queue | Capacity | Overflow | Consequence |
|---|---:|---|---|
| `asrIn` | 4 | suspend sender | completed capture waits if ASR is behind |
| `mtIn` | 8 | suspend sender | ASR worker waits if MT is behind |
| `ttsIn` | 8 | drop oldest | older remote speech can disappear silently |
| public `_events` | extra 64 | emit suspends when necessary | all transition events should retain order per emitter |

The TTS drop policy is dangerous because the UI receives `Translated` before enqueue and may show a remote turn that is never spoken. **Required:** choose and document one behavior: suspend with bounded backpressure, or emit a specific dropped/cancelled event. Do not silently discard a conversational turn.

## 8. State machine

The ViewModel exposes:

```text
Idle
Recording
ProcessingAsr
Translating
Sending       (declared but currently never assigned)
PlayingRemoteTts
Error(message)
```

Expected local state transitions:

```text
Idle -> Recording -> ProcessingAsr -> Translating -> Sending -> Idle
```

Expected remote playback transitions:

```text
Idle -> PlayingRemoteTts -> Idle
```

Current behavior skips `Sending`: a successful local `Translated` event moves directly from `Translating` to `Idle`. Any `Failed` event also moves to `Idle`, while marking the matching UI turn as `Error` if present. Initialization errors use the global `MeetingState.Error`.

Conversation UI turn statuses are separate:

```text
Transcribing -> Translating -> Complete
                         \-> Error
```

`retryTurn()` is currently empty even though the UI exposes Retry.

## 9. Lifecycle, ownership, and cleanup

`MeetingViewModel` owns the pipeline, socket, and diagnostics. Initialization occurs in `viewModelScope`, with model construction wrapped in `Dispatchers.IO`.

Cleanup paths:

- `MainActivity.onDestroy()` calls `viewModel.stopPipeline()`.
- `MeetingViewModel.onCleared()` calls `pipeline.stop()`, `network.destroy()`, and `diagnostics.stop()`.
- `InterpreterPipeline.stop()` cancels its whole scope and stops playback.

Current lifecycle defects:

1. `InterpreterPipeline.stop()` sets `started=false` after permanently cancelling its only scope. A later `start()` launches into a cancelled scope and cannot restart.
2. `stop()` calls `playback.stop()` but not `playback.release()`.
3. `MlKitTranslator.close()` exists but is never invoked.
4. Sherpa wrappers expose no release/close API; confirm the library's v1.13.3 lifecycle API before adding one.
5. `AudioCapture` creates a noise suppressor but does not retain/release the effect explicitly.
6. calling `stopPipeline()` in `Activity.onDestroy()` can stop work during configuration changes even when the ViewModel should survive.

**Required:** define one-shot ownership clearly. Either make the pipeline non-restartable and remove misleading restart state, or recreate its scope/resources on restart. Every owned closeable native/audio/ML resource must be released exactly once.

## 10. Deduplication and network behavior

The current cache is a synchronized insertion-ordered `LinkedHashSet<String>` intended to keep about 200 event IDs. Remote events are rejected when:

- `speakerId == localParticipantId`; or
- the `eventId` is already cached.

Local IDs are cached before network send. Remote IDs are cached on receipt.

Current issues:

- size trimming and add are multiple operations rather than one synchronized atomic operation;
- `firstOrNull()` plus `remove()` is awkward under concurrent access;
- trimming occurs only when size is already greater than 200, allowing 201 entries;
- malformed messages are only logged; callers receive no parse/protocol event;
- `sendTranslation()` may return true merely because OkHttp accepted the frame, not because a relay/peer acknowledged it;
- reconnect uses exponential delay from 1 s to 30 s but has no jitter and no explicit terminal state;
- `onClosing` does not itself reconnect;
- reconnect can race with explicit disconnect unless all callbacks check current intent/generation.

**Required:** implement a small synchronized LRU/set abstraction with an atomic `markIfNew(id): Boolean`. Treat send as enqueue-to-socket unless an acknowledgement protocol is added; do not label it delivered.

## 11. Timing and diagnostics semantics

`LatencyTracer` derives:

| Metric | Formula |
|---|---|
| VAD/speech duration | `SpeechEnded.tEnd - SpeechStarted.tStart` (this is button-held duration, not actual VAD latency) |
| ASR | `Transcribed.tAsrDone - SpeechEnded.tEnd` |
| MT | `Translated.tMtDone - Transcribed.tAsrDone` |
| TTS | `SpokenReady.tTtsDone - Translated.tMtDone` |
| E2E | `Translated.tMtDone - SpeechEnded.tEnd` (excludes TTS/playback) |

`PipelineDiagnostics.recordAsrPerformance()` computes real-time factor as processing time divided by audio duration. Audio duration is currently estimated as `pcm.size / 16` ms, valid only for 16 kHz mono audio.

Diagnostics retain the most recent 100 latency values and expose an approximate p95. They poll PSS memory and `Thread.activeCount()` every two seconds. A 400 MB PSS ceiling is logged and highlighted in UI but not enforced.

**Required:** name timings according to their real meaning, keep all arithmetic in the same monotonic clock domain, and never calculate cross-device latency using sender elapsed-realtime timestamps.

## 12. High-priority implementation defects

The coding agent must address or explicitly defer every item below.

### P0 — correctness

1. **VAD is dead code.** Decide whether push-to-talk is authoritative. If yes, remove VAD from the live constructor and do not advertise VAD timings. If VAD must trim turns, adapt 480-sample capture chunks into exact 512-sample VAD frames, retain remainder samples, include sensible pre-roll/post-roll, flush on button release, and emit only one utterance per turn unless multi-utterance behavior is explicitly designed.
2. **TTS sample rate is discarded.** Preserve `audio.sampleRate`; play at that rate or resample with a tested implementation.
3. **Playback completion is false.** `AudioTrack.write()` completion only means samples were copied, not played. Current code emits completion after an arbitrary 500 ms. Wait for the playback head/marker or calculate and await the remaining duration, while handling cancellation.
4. **Capture failures can disappear.** `startRecording()` has `try/finally` but no `catch` that emits `Failed`; `PipelineEvent.Stage` cannot represent capture/playback.
5. **Blank ASR is translated and sent.** Treat blank/whitespace transcripts as a terminal no-speech/empty-transcript outcome; do not relay them.
6. **Network failure discards successful local translation UI.** Current code emits only `Failed(Network)` and skips `Translated`, even though translation succeeded. Preserve translated text and separately represent delivery status.

### P1 — concurrency and lifecycle

1. Make `currentDirection` thread-safe (`StateFlow`, atomic reference, or synchronized access), not a plain mutable cross-thread property.
2. Define whether another press is allowed while ASR/MT is pending. Current UI looks disabled via color but the button remains enabled unless remote TTS is playing; multiple queued turns are possible.
3. Prevent capture during playback without a check-then-act race. `isMutedForPlayback` alone does not stop a capture already running when playback begins.
4. Do not silently drop oldest TTS turns.
5. Ensure cancellation does not convert normal stop into user-visible failure and does not block indefinitely in `NonCancellable` channel send.
6. Release playback, ML Kit, audio effects, scopes, and applicable Sherpa native resources.

### P2 — contracts and maintainability

1. Add explicit protocol validation and ideally a protocol version field.
2. Replace string language fallback (`anything other than vi => English source`) with exhaustive parsing.
3. Implement retry semantics or remove the Retry action.
4. Connect `Glossary`, LAN fallback, and PhoMT only if product requirements call for them; do not imply they currently work.
5. Add unit tests. Existing tests are only Android Studio template examples.

## 13. Required target design

Keep the external behavior but separate each concern so it can be tested without Android hardware or real models.

### 13.1 Interfaces

Use narrow interfaces around blocking/native components:

```kotlin
interface SpeechRecognizer {
    suspend fun transcribe(pcm: PcmAudio): String
}

interface SpeechSynthesizer {
    suspend fun synthesize(text: String): SynthesizedAudio
}

interface AudioPlayer {
    suspend fun play(audio: SynthesizedAudio)
    fun stop()
}

interface TranslationTransport {
    val events: Flow<TransportEvent>
    suspend fun send(event: TranslationEvent): SendResult
}

data class PcmAudio(
    val samples: ShortArray,
    val sampleRate: Int = 16_000,
    val channels: Int = 1
)
```

Sherpa adapters may call synchronous JNI internally, but their public methods should be suspendable and execute on an injected bounded dispatcher. Do not create an unbounded thread pool per model or per turn.

### 13.2 Turn processing guarantees

For each local turn:

1. Generate exactly one UUID.
2. Snapshot direction and start timestamp.
3. Capture PCM16/16 kHz/mono until release.
4. Reject or trim empty/no-speech audio.
5. Run exactly one matching ASR model.
6. Reject blank transcript.
7. Translate using the snapshotted direction.
8. Publish local transcript and translation regardless of transport availability.
9. Attempt one transport send under the socket contract; expose queued/sent/failed accurately.
10. Never synthesize the local event on the same device.

For each remote event:

1. Parse and validate the entire schema.
2. Verify current room.
3. Reject self events.
4. Atomically reject duplicate `eventId`.
5. Publish the remote text turn.
6. Select TTS from `targetLanguage`, not inferred source direction.
7. Synthesize once.
8. Mute/stop capture before playback starts.
9. Play at the generated sample rate.
10. Emit completion only after audible playback ends or is explicitly cancelled.

### 13.3 Error behavior

Every failure must preserve `turnId`, exact stage, a safe user message, and a diagnostic cause/log. A failed stage must not leave the global UI permanently in Processing or Playing.

Recommended additional stages/outcomes:

```text
Capture, Vad/NoSpeech, Asr/EmptyTranscript, Translation,
Network/NotConnected, Network/Protocol, Tts, Playback, Cancelled
```

Do not catch `CancellationException` as an ordinary error. Rethrow it after cleanup.

### 13.4 Queue policy

Use explicit bounded capacities. Recommended initial policy for push-to-talk:

- one active capture;
- one serial local turn processor, with at most 4 queued completed turns;
- one serial remote TTS/playback worker, with at most 8 queued turns;
- on overflow, reject the newest turn with an explicit busy/overflow event rather than silently deleting an older accepted turn.

If product behavior instead requires “latest remote speech wins,” cancel the active/old turn explicitly and emit a cancellation event. Do not use `DROP_OLDEST` without observability.

## 14. Acceptance tests

At minimum, implement deterministic tests with fakes for these cases:

1. Vietnamese turn chooses Vietnamese ASR, `vi -> en` MT, and emits the correct relay languages.
2. English turn chooses English ASR and `en -> vi` MT.
3. Direction is snapshotted: toggling after press does not change the active turn.
4. ASR receives normalized 16 kHz mono audio and a complete released utterance.
5. Empty capture and blank ASR emit an explicit terminal outcome and never call MT/network.
6. ASR failure emits one error for the same turn and does not call MT.
7. MT success is shown locally even when transport send fails.
8. A local echoed relay event is ignored.
9. A duplicate remote event is displayed/spoken once.
10. Wrong-room and invalid-language events are rejected.
11. Remote `targetLanguage=vi` selects Vietnamese TTS; `en` selects English TTS.
12. Synthesized sample rate reaches playback unchanged.
13. Playback events bracket actual player suspension: Started before play, Completed only after `play()` returns.
14. Starting playback cancels or blocks active capture, preventing acoustic feedback.
15. Queue overflow yields an observable outcome and never silently loses an accepted turn.
16. Pipeline shutdown cancels workers and releases each owned resource once.
17. Reconnect stops after explicit disconnect and does not create multiple simultaneous sockets.
18. Dedup cache remains bounded and atomic under concurrent calls.

On Android/device tests, verify:

- microphone permission denial and later grant;
- audio capture on API 24 and API 35;
- both configured ABIs or the actual supported device ABI;
- model initialization from packaged assets;
- Piper `espeak-ng-data` copy on clean install and subsequent launch;
- real TTS duration/sample rate and no clipped conversion;
- memory remains below the intended 400 MB PSS threshold during repeated turns;
- ASR real-time factor and end-to-end latency on target hardware.

## 15. Concrete coding-agent brief

Use this section as the direct task prompt:

> Implement a production-correct Sherpa speech pipeline in this repository while preserving the existing `Direction`, relay JSON fields, model asset paths, and Compose-facing conversation behavior. First inspect every file referenced in this document; do not replace the existing model families or dependency versions. Refactor behind testable interfaces, preserve TTS sample rate, make playback completion real, make direction snapshotting and deduplication thread-safe, expose capture/playback errors, handle blank/no-speech turns, and remove silent queue drops. Decide and document whether VAD trims push-to-talk audio; if enabled, frame 480-sample capture chunks into the configured 512-sample Silero windows and flush correctly. A successful local translation must remain visible even if relay delivery fails. Remote events must be strictly validated, self-filtered, deduplicated, synthesized using `targetLanguage`, and played without microphone feedback. Implement lifecycle cleanup and the acceptance tests in section 14. Do not integrate the placeholder PhoMT or LAN client unless specifically requested. Run the repository's unit tests and a debug build, report exact commands/results, and list any behavior intentionally deferred.

## 16. Non-goals and facts not to invent

- There is no documented relay acknowledgement protocol.
- There is no authentication, encryption beyond `wss`, room authorization, or participant presence schema in this repository.
- There is no working PhoMT implementation.
- There is no connected LAN fallback.
- There is no streaming/online ASR; current recognizers are offline transducers.
- There is no speaker diarization.
- There is no automatic language detection.
- There is no supported language beyond Vietnamese and English.
- There is no implemented retry behavior.
- The relay server implementation is not present, so server-side assumptions must be confirmed separately.

When a missing decision affects a public contract, stop and request that decision instead of silently inventing it.
