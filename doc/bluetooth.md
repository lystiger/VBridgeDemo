VBridge Bluetooth Peer-to-Peer Transport Implementation
Goal

Implement offline phone-to-phone communication for VBridge using Bluetooth Classic (RFCOMM) while preserving the existing speech pipeline.

The Bluetooth transport must become another implementation of TranslationTransport, allowing the rest of the application (ASR, Translation, TTS, UI, Diagnostics) to remain unchanged.

This feature is intended for hackathon demonstrations where Internet, Wi-Fi, or relay servers are unavailable.

Current Architecture

Current pipeline:

Audio
    │
    ▼
VAD
    │
    ▼
ASR
    │
    ▼
Translation
    │
    ▼
TranslationTransport
    │
    ▼
Remote phone

Currently:

TranslationTransport
        │
        ▼
VBridgeSocket (WebSocket)

Target:

TranslationTransport
        │
 ┌──────┴──────────────┐
 │                     │
 ▼                     ▼
WebSocket         Bluetooth RFCOMM

The pipeline should not know whether the message is being transported through WebSocket or Bluetooth.

Requirements
Functional

The app shall support three connectivity modes.

Solo
Bluetooth
Room

Behavior:

Solo

No network transport.

Translation stays on the current device.

Bluetooth

Two Android phones communicate directly over Bluetooth Classic.

Workflow:

Phone A

Speak
↓

ASR

↓

Translation

↓

Bluetooth

↓

Phone B

Phone B

Receive TranslationEvent

↓

Conversation UI

↓

TTS

↓

Play translated speech
Room

Keep the existing WebSocket implementation.

No regression.

Transport Layer

Create

network/bluetooth/

containing

BluetoothTranslationTransport.kt
BluetoothConnectionManager.kt
BluetoothEventCodec.kt
BluetoothDiscovery.kt
BluetoothConnectionState.kt
VBridgeBluetoothProtocol.kt
BluetoothTranslationTransport

Implement

class BluetoothTranslationTransport :
    TranslationTransport

Responsibilities

send TranslationEvent
receive TranslationEvent
emit NetworkEvents
expose connection state
disconnect
destroy resources

This should mirror the existing VBridgeSocket implementation.

Bluetooth Protocol

Use

Bluetooth Classic RFCOMM

NOT BLE.

Reasons

bidirectional socket
much simpler API
no MTU limitations
no characteristic management
ideal for JSON messages
Service UUID

Create

object VBridgeBluetoothProtocol

with

SERVICE_NAME

SERVICE_UUID

UUID may be any fixed UUID.

Example

5e2bb4e6-52f6-4aa8-b8ce-688da36d1958
Message Format

Bluetooth should transmit exactly the same TranslationEvent used by WebSocket.

Use newline-delimited JSON.

Example

{"type":"translation", ...}

followed by

\n

Never rely on socket packet boundaries.

BluetoothEventCodec

Extract JSON serialization from VBridgeSocket into a reusable codec.

Functions

encode(event)

decode(raw)

Bluetooth and WebSocket should both use this codec.

No duplicated JSON construction.

Connection Model

One device acts as Host.

One device joins.

Host

listenUsingRfcommWithServiceRecord()

↓

accept()

Client

createRfcommSocketToServiceRecord()

↓

connect()

After connection both become peers.

No additional host/client logic is required.

BluetoothConnectionManager

Responsibilities

create server socket
create client socket
discovery
reconnect
receive loop
send
disconnect
cleanup

Everything must run on Dispatchers.IO.

Receive Loop

Receive loop should

InputStream

↓

BufferedReader

↓

readLine()

↓

BluetoothEventCodec.decode()

↓

emit NetworkEvent.TranslationReceived

Malformed JSON should not crash the application.

Emit

NetworkEvent.Error

instead.

Sending

Use

OutputStream.write()

flush()

Protect writes with

Mutex

to prevent interleaving when multiple coroutines send simultaneously.

Interpreter Pipeline

DO NOT modify the pipeline architecture.

InterpreterPipeline already depends on

TranslationTransport

Bluetooth should satisfy that interface.

Pipeline must remain transport-agnostic.

Connectivity Mode

Replace

Solo
Room

with

Solo
Bluetooth
Room

When Bluetooth mode is selected

the transport switches to

BluetoothTranslationTransport

without affecting

ASR
Translation
UI
TTS
Delegating Transport

Instead of constructing InterpreterPipeline directly with VBridgeSocket,

introduce

DelegatingTranslationTransport

which forwards calls to the active transport.

Supported transports

SoloTransport

BluetoothTranslationTransport

VBridgeSocket

Switching connectivity mode should not require rebuilding the pipeline.

Permissions

Support Android 12+

Manifest must include

BLUETOOTH_CONNECT

BLUETOOTH_SCAN

BLUETOOTH_ADVERTISE

Older Android versions should continue supporting

BLUETOOTH

BLUETOOTH_ADMIN

ACCESS_FINE_LOCATION

Runtime permission handling is required.

UI

Settings page

Connectivity

○ Solo

○ Bluetooth

○ Room

Bluetooth page

Host Conversation

Join Conversation

Paired Devices

Nearby Devices

Connection Status

Status examples

Waiting

Scanning

Connecting

Connected

Disconnected
Preferred Demo Flow

To maximize hackathon reliability




Pair both phones in Android Settings before demo.




Host phone

Host Conversation



Second phone

Join Conversation

↓

Select paired device



Both display

Bluetooth Connected



Disable Wi-Fi

Disable Mobile Data

Conversation should still function.

Error Handling

Recover gracefully from

peer disconnect
Bluetooth disabled
malformed JSON
socket timeout
permission denial

The application must never crash.

Testing
Functional
Pair two physical Android phones
Host connects successfully
Client connects successfully
Translation appears on remote phone
Remote TTS speaks
Local microphone locks during remote playback
Conversation continues in both directions
Offline

Disable

Wi-Fi
Mobile Data

Verify

phone-to-phone conversation still works.

Failure

Disconnect Bluetooth during conversation.

Expected

connection state updates
transport disconnects cleanly
application remains usable
Non-Goals

Do NOT

stream raw microphone audio
perform Bluetooth voice calls
replace Sherpa ASR
replace ML Kit
modify Conversation UI architecture
duplicate InterpreterPipeline

Only transport TranslationEvent objects.

Deliverables
BluetoothTranslationTransport implementation
BluetoothConnectionManager
BluetoothEventCodec
Bluetooth discovery UI
Runtime permissions
Connectivity mode switching
Two-phone offline conversation
No regression to existing Room mode
Success Criteria

A successful implementation demonstrates:

Two Android phones communicate directly via Bluetooth Classic.
Internet is completely disabled.
One phone performs ASR and translation locally.
The translated TranslationEvent is transmitted over Bluetooth.
The receiving phone renders the conversation bubble and performs TTS playback.
Existing Room mode continues to function without modification.
The InterpreterPipeline remains transport-agnostic and requires no Bluetooth-specific logic.