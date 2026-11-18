# Innova 3215RS investigation — phase 0/1 report

Status of every claim in this document is tagged:

- **CONFIRMED** — seen on the real adapter and vehicle (adapter test log, 2026-11-18, 2013 Traverse, engine running).
- **STATIC** — read from code (this app's source/APK, or the RepairSolutions2 analysis provided by the user). Not proven on hardware.
- **REFERENCE** — general GM/GMLAN/ISO knowledge (GMW3110, ISO 15765, ISO 14229). Must be verified on this vehicle before use.
- **UNKNOWN** — not yet tested.

---

## 1. The existing APK

| Item | Finding |
|---|---|
| File | `app-debug.apk`, 9.96 MB, intact archive |
| Package / version | `com.obdscanner.app` 0.1.0 (1), minSdk 26, targetSdk 35 |
| Signing | APK Signature Scheme v2 only, **Android Debug** certificate — a debug build, not for store release |
| Build | Commit `4b31254` (contains the native-probe strings, lacks the multi-prompt fix of `cd23c49`) — one build behind the branch |
| Code | Kotlin + Jetpack Compose; 718 app classes in `com.obdscanner.app` (UI, transports) and `com.obdscanner.core` (protocol engine) |
| Permissions | BLUETOOTH/ADMIN (≤ API 30), BLUETOOTH_CONNECT, BLUETOOTH_SCAN (neverForLocation), COARSE/FINE location (≤ API 30), INTERNET (Wi‑Fi adapters only) |

### How the app actually talks to the 3215RS (CONFIRMED)

```
UI ─► ObdViewModel ─► ObdClient (ELM327 commands, parsing, OBD/UDS/GMLAN logic)
                         │
                         ▼
                 ElmChannel interface
     ┌──────────────┬───────────────┬────────────────────┐
 BleElmChannel   StreamElmChannel  InnovaNativeChannel   SimulatedElmChannel
 (BLE GATT)      (classic BT/Wi‑Fi) (native frames→ELM)   (demo/tests)
```

- On the 3215RS the working path is **`BleElmChannel` carrying ELM327 text**: ASCII commands written to `FFF2`, replies arriving as notifications on `FFF1`.
- `InnovaVci` / `InnovaNativeChannel` (the RepairSolutions2‑style binary protocol: `AE …` requests, `EA …` replies) are compiled in, but **the dongle has never answered them**: firmware‑version and AUTOLINK frames got zero bytes back in three separate test runs. So the native layer is never selected. It stays in the app as a dormant path guarded by a read-only allowlist.
- So the claim "the dongle doesn't speak ELM327" is **contradicted by the hardware**. It answers `ATI` with `ELM327 v1.3a` and returns real engine data.

### What is verified vs. not (as of this log)

| Feature | Status | Evidence |
|---|---|---|
| BLE connect, service discovery, MTU 200 | CONFIRMED | log |
| Live data (Mode 01), ~30 PIDs | CONFIRMED | screenshots: RPM 623, coolant, trims, O2, MAF… |
| Supported‑PID bitmaps, 3 modules answer | CONFIRMED | 7E8 `BFFFB993`, 7EB `80400001`, 7EA `80000001` |
| Stored codes (Mode 03) | CONFIRMED | `7EB 02 43 00` (no codes) |
| Readiness (Mode 01 PID 01) | CONFIRMED data; app showed the wrong module (fixed in `cd23c49`) | |
| VIN (Mode 09/02) | CONFIRMED data; parse failed (fixed in `cd23c49`) | |
| Battery voltage (`ATRV`) | CONFIRMED | 14.6 V |
| Freeze frame, pending/permanent codes, clear codes | UNKNOWN on this car | not exercised in the log |
| Mode 06 | was disabled (protocol misdetected), fixed in `cd23c49` — UNKNOWN | |
| Module scan | **likely broken on this car**: discovery requires a reply to `3E00`, and the ECM gave `NO DATA` | log |
| ID explorer (service $22) | UNKNOWN | |
| GMLAN `$A9` DTC read | STATIC/experimental | never run |

---

## 2. BLE protocol map (CONFIRMED unless noted)

Device name `CodeReader31C`, LE address (random/public not recorded).

| Service | Characteristic | Properties | Role |
|---|---|---|---|
| `1800` Generic Access | `2A00` Device Name, `2A01` Appearance | read | standard |
| `1801` Generic Attribute | `2A05` Service Changed | read (as reported) | standard |
| **`FFF0`** vendor | **`FFF1`** | notify | adapter → phone (ELM text) |
| | **`FFF2`** | write, writeNoResp | phone → adapter (ELM text) |
| `180A` Device Information | `2A25` serial, `2A28` software rev, `2A27` hardware rev, `2A29` manufacturer, `2A24` model | read | **never read until now** — added in this commit (read-only), results appear in the next adapter test |

- MTU negotiated: **200** (requested 185 → adapter returned 200).
- Framing on FFF1/FFF2: plain ASCII, commands end with `\r`, every reply ends with `\r\r>`.
- No other writable characteristic exists, so there is **no separate service for firmware/DFU** exposed while in normal mode (no DFU UUIDs seen).

## 3. ELM327 emulation behaviour (CONFIRMED)

| Command | Reply | Meaning |
|---|---|---|
| `ATI`, `ATZ` | `ELM327 v1.3a` | identifies as an ELM327 1.3a (old command set: no `ATCEA`, no `ATCSM`) |
| `AT@1` | `OBDII to RS232 Interpreter` | stock clone string |
| `AT@2` | `?` | not supported |
| `STI` | `ELM327 v1.3a` | not an STN chip |
| `STDI`, `ATDP`, `ATCS`, `ATPPS` | `OK` | **answers OK to commands it doesn't implement** |
| `ATDPN` | `A0` | no protocol number available |
| `ATH1`/`ATH0`, `ATSH7E0`, `ATSH7E2` | honoured | headers and physical addressing work (7E2 got only 7EA's answer) |
| `0100` (broadcast) | one answer **per module, each ending with its own `>`** | non-standard; fixed in `cd23c49` |
| Multi-frame answers | first frame without PCI, then `21…`, `22…` lines keeping the sequence byte | non-standard; parser fixed in `cd23c49` |
| `3E00` → ECM | `NO DATA` | see §6 — GMLAN TesterPresent is the single byte `3E`; inconclusive |
| `1A90`, `22F190` | answers seen, but the old build mixed replies between commands | must be re-tested on `cd23c49` |

**Key consequence:** because unsupported commands still return `OK`, a command's support can only be proven **by its effect**, never by the `OK`. The capability tester (phase 2) must test behaviour, not acknowledgements.

## 4. Hardware and firmware capability report

| Question | Answer | Status |
|---|---|---|
| VCI identification | ELM327 v1.3a emulation; real model/firmware/hardware revision pending the 180A read | CONFIRMED / pending |
| Diagnostic protocols | 11‑bit CAN confirmed on the Traverse. The RS2 analysis lists VPW, PWM, ISO 9141, KWP2000 and CAN 250/500 (standard/extended) | CONFIRMED (CAN) / STATIC (others) |
| **Single-wire CAN (GMLAN 33.3k, DLC pin 1)** | not in the ELM327 command set, and not in the RS2 protocol list. Whether pin 1 is even wired is unknown | STATIC — strong indication it's unavailable |
| Arbitrary requests to arbitrary 11‑bit IDs | yes, through `ATSH` + hex data, with the adapter doing ISO‑TP | CONFIRMED for 7E0/7E2 |
| Service filtering by the VCI | unknown. `3E00` silence could be the module or the VCI | UNKNOWN — test plan T5 |
| Raw CAN (`ATCAF0`), monitor mode (`ATMA`), receive filters (`ATCF/ATCM/ATCRA`) | unknown — they all answer `OK` either way | UNKNOWN — test plan T4 |
| Periodic tester-present in background | ELM 1.3a has no periodic-message command; must be sent by the app between requests | REFERENCE |
| Native protocol (`AE/EA`) | no reply on FFF1/FFF2 in 3 runs | CONFIRMED (no reply) |
| Firmware update mechanism | RS2 contains an UPGRADE command (0x07) and bootloader commands, so an official in-app updater exists. Signing is unknown | STATIC |

## 5. GM compatibility — 2013 Chevrolet Traverse

REFERENCE (to verify): this platform uses GMLAN:

- **High-speed GMLAN**, 500 kbps, DLC pins 6/14: powertrain and chassis (ECM, TCM, typically EBCM, plus the BCM as a gateway).
- **Low-speed GMLAN**, single wire 33.3 kbps, DLC pin 1: body and comfort modules (typically HVAC, radio, IPC, and others that vary by build).
- Physical addressing: request `0x7E0`/`0x7E2` (ECM/TCM), and GMLAN `0x241–0x25F` requests → `0x641–0x65F` responses (and `0x541–0x55F` for unsolicited frames). Functional requests go to `0x101` with extended address `0xFE`.
- Services: `$1A` ReadDataByIdentifier, `$22` ReadDataByParameterIdentifier, `$A9` ReadDiagnosticInformation (DTCs with failure-type byte), `$AA` ReadDataByPacketIdentifier, `$3E` TesterPresent (no sub-function), `$04` clear, `$AE` DeviceControl (outputs), `$27` SecurityAccess, `$10/$28/$A5/$34/$36/$3B` programming and writing.

Observed (CONFIRMED): three modules answer OBD requests on high-speed CAN — 7E8 (engine), 7EA and 7EB (identity to be read with `$1A`). The app currently labels 7EA "Hybrid/ABS"; that label is generic and not right for GM (on GM, 7E2/7EA is normally the TCM). It will be replaced by the module's own ID.

**Reachability verdict**

| Module | Network (REFERENCE) | Reachable with 3215RS? |
|---|---|---|
| ECM | HS‑GMLAN | **Yes** (CONFIRMED) |
| TCM | HS‑GMLAN | Very likely (7EA answers OBD) |
| EBCM (ABS) | HS‑GMLAN (typical) | Possible — discovery needed |
| BCM | HS + LS (gateway) | Possible on its HS side — discovery needed |
| AWD/transfer case | varies | Discovery needed |
| SDM (airbag) | varies | Discovery needed; **read-only always** |
| IPC, HVAC, radio, TPMS receiver (often BCM-integrated) | LS‑GMLAN single wire (typical) | **Likely not** — needs single-wire CAN hardware |

## 6. Bidirectional capability assessment

- **Transport:** with `ATSH` the 3215RS can deliver short GMLAN requests to high-speed modules, so frames like `$AE` *could* be sent. Multi-frame requests and the keep-alive timing are untested.
- **The real blockers are knowledge and authorisation, not bytes:**
  1. Which `$AE` control bytes drive which output is GM-proprietary. Those bytes will **not** be guessed. Unknown requests to modules are against this project's rules, and a wrong command can move an actuator.
  2. Many controls require `$27` SecurityAccess (seed/key). The app will not bypass security.
  3. Outputs need a tester-present kept alive. The ELM 1.3a can't do that in the background, so the app has to send it, and any gap ends the control.
  4. LS-GMLAN modules (HVAC and probably lighting) are likely physically unreachable (§5).
- **Plan:** an Active Test Engine that only runs **documented, verified** tests from a reviewed definition file. Each test lists its preconditions (engine off / in Park), a maximum duration, an automatic return-to-normal (`$20`), and module allowlists. SDM and EBCM are excluded from outputs entirely. A test stays at "Unsupported" until it is verified on the car.

## 7. Firmware assessment

- An official update path exists inside RepairSolutions2 (STATIC). Whether the images are signed is UNKNOWN, and it doesn't need to be known.
- **Firmware modification is not necessary** for anything that can be fixed in software. The ELM interface already allows arbitrary single-frame requests to 11‑bit IDs. The hard limits (no single-wire CAN transceiver, no proprietary control data, security access) are **not** solved by firmware changes.
- This app never sends upgrade, bootloader, reboot, database-write or ID-write commands (enforced in `InnovaVci.assertSafe`, which has a unit test). If the dongle needs an update, use RepairSolutions2.

## 8. Proposed architecture (keep everything that works)

| Layer | Existing | Change |
|---|---|---|
| Innova BLE transport | `BleElmChannel` | keep; add GATT inspector data (done: 180A) |
| VCI protocol layer | `InnovaVci`, `InnovaNativeChannel` | keep dormant; document as unverified |
| CAN / ISO-TP | the ELM does ISO-TP; `ElmResponseParser` | keep; add `CanSession` (header, filters, flow control, tester-present timer) |
| GM diagnostic engine | parts of `ObdClient` | new `GmDiagnostics` (`$1A`, `$22`, `$A9`, `$04` per module) |
| Module discovery | `scanModules` (3E00-based) | **fix**: identify with read-only `$1A`/`$22`. Any reply, including a negative one, counts as present |
| DTC / PID databases | `DtcDescriptions`, `Pids`, `ManufacturerPids` | add GM failure-type bytes; only PIDs verified on this car get "Confirmed" |
| Safety policy | `InnovaVci.assertSafe` (dongle side) | new `ServicePolicy` for the vehicle side: read-only mode by default; clearing and active tests are separate explicit modes; safety-critical modules are protected |
| Capability tester | adapter test | new VCI Investigation screen: GATT inspector, device info, behaviour-based command tests, protocol analyzer, exportable report |
| Status tags | — | every feature is shown as Confirmed / Tested / Experimental / Unsupported |

## 9. Testing plan (your scanner + your Traverse)

Use the newest build each time. Engine running unless noted. Share **Vehicle → Run adapter test** after each step.

| # | Test | What it proves |
|---|---|---|
| T1 | Connect, run the adapter test | 180A device info (model, firmware and hardware revision); multi-prompt fix; VIN, protocol and readiness are correct |
| T2 | Connect with **key on, engine off**, then with the engine running | Whether the old `7F0111` was an ignition-state effect |
| T3 | Read codes, pending codes, freeze frame, Mode 06 | Verifies the remaining generic features |
| T4 | Capability tester (next build): `ATCAF0` + a known request, `ATCRA`, `ATMA` for 2 s, `ATSP` variants | Which ELM features really work (behaviour, not `OK`) |
| T5 | Read-only identity per address: `$1A 90` / `$1A B0` at 7E0/7E2 and 0x241–0x25F; `$22 F190` | Which modules exist on HS-GMLAN, and whether the VCI filters GM services |
| T6 | GMLAN `$A9` DTC read per found module | Full-system code read |
| T7 | Look into the dongle's plug: is there a metal contact in **pin 1** (top row, far left with the wide side up)? Photo is enough | Dongles usually only populate the pins they use. An empty pin 1 proves no single-wire GMLAN |

Each step only reads. Nothing in T1–T7 writes to a module or to the dongle.

## 10. Open questions

1. Why did the old builds get `7F0111` while this one works? (T2)
2. What does 180A report as firmware/hardware revision? (T1)
3. Does the VCI drop any GMLAN services? (T5)
4. Are 7EA/7EB the TCM and another powertrain module? (T5)
5. Is pin 1 populated on the 3215RS plug? (T7)
