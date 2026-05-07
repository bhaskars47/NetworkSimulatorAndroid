# Network Simulator — Android VPN-based Network Interceptor

An Android APK that uses `VpnService` to intercept your target app's traffic on-device — no laptop, no proxy server, no root required — and applies configurable network degradation in real time.

## What it does

```
Your App  →  TUN interface (this APK)  →  PacketProcessor  →  Real Internet
                                            ↳ drop?
                                            ↳ delay?
                                            ↳ throttle?
```

Four built-in profiles:

| Profile | Latency | Jitter | Loss | Bandwidth |
|---|---|---|---|---|
| Slow 3G | 300 ms | ±50 ms | 1 % | 100 kbps |
| High Latency | 1 000 ms | ±200 ms | 0.5 % | unlimited |
| Packet Loss | 50 ms | ±10 ms | 20 % | unlimited |
| Custom | slider | slider | slider | slider |

The VPN is scoped to a **single target app** via `addAllowedApplication()` — every other app on the device is unaffected.

---

## Project structure

```
app/src/main/
├── kotlin/com/networksimulator/
│   ├── MainActivity.kt              UI — profile cards, sliders, toggle
│   ├── ui/
│   │   └── MainViewModel.kt         ViewModel — VPN state, config
│   ├── model/
│   │   ├── NetworkProfile.kt        Data class for one network condition
│   │   └── SimulationConfig.kt      Config passed to the VPN service
│   └── vpn/
│       ├── NetworkSimulatorVpnService.kt   Core VpnService subclass
│       ├── PacketProcessor.kt              Drop / delay / throttle logic
│       ├── TcpConnectionTracker.kt         TCP connection state table
│       └── packet/
│           ├── IpPacket.kt          IPv4 header parser
│           ├── TcpPacket.kt         TCP segment parser
│           └── UdpPacket.kt         UDP datagram parser
└── AndroidManifest.xml
```

---

## Requirements

- Android Studio Hedgehog (2023.1) or later
- Android Gradle Plugin 8.3+
- minSdk 26 (Android 8.0 Oreo)
- Kotlin 1.9

---

## How to build

1. Open `NetworkSimulator/` in Android Studio — it will auto-sync Gradle.
2. Connect a device or start an emulator (API 26+).
3. Run → the app installs as **Network Simulator**.

---

## How to use

1. **Pick a profile** — tap one of the four cards.
2. **Enter a target package** — type the package name of the app you want to test (e.g. `com.example.myapp`). Leave blank to intercept all apps.
3. **Tap "Start Simulation"** — Android will ask permission to create a VPN; tap **OK**.
4. A persistent notification shows while the simulation runs. Tap **Stop** there or in the app to end it.

---

## Architecture notes

### VPN tunnel
`NetworkSimulatorVpnService` calls `VpnService.Builder.establish()` which returns a `ParcelFileDescriptor` for the TUN interface. Raw IPv4 packets flow in via `FileInputStream(fd)` and responses are written back via `FileOutputStream(fd)`.

### Simulation pipeline (`PacketProcessor`)
Each packet goes through three gates:
1. **Drop gate** — `Random.nextFloat() * 100 < packetLossPercent`
2. **Latency gate** — `delay(latencyMs ± jitterMs)` (Kotlin coroutine suspend)
3. **Throttle gate** — token-bucket counting bytes in 1-second windows

### UDP forwarding
Each UDP datagram is forwarded via a short-lived `DatagramChannel` whose underlying socket is protected with `VpnService.protect()`. This prevents the forwarding socket from looping back through the VPN tunnel.

### TCP forwarding
`TcpConnectionTracker` maintains a `ConcurrentHashMap` of 4-tuples to `SocketChannel` instances. SYN packets open a new protected `SocketChannel`; subsequent data segments are forwarded through the existing channel. FIN/RST closes it.

> **Note:** The current TCP implementation is intentionally simplified — it does not reconstruct full TCP headers for the return path. A production-grade version would maintain per-connection sequence/ACK numbers and build correct TCP segments. This is the natural next step for extending the project.

### Restricting to one app
```kotlin
builder.addAllowedApplication("com.example.targetapp")
builder.addDisallowedApplication(packageName)  // exclude ourselves
```
If `targetPackageName` is blank, `addAllowedApplication` is skipped and all traffic is intercepted.

---

## Extending the project

- **Add IPv6 support** — add `addAddress("fd00::1", 128)` and `addRoute("::", 0)`, then handle IPv6 headers in `IpPacket`.
- **Per-request logging** — add a `LogRepository` LiveData that the VPN service posts to; the UI can display a live packet log.
- **Profile hot-swap** — call `processor.profile = newProfile` from the service while running; `PacketProcessor` is designed for this.
- **Production TCP proxy** — replace `TcpConnectionTracker` with a full TCP state machine (seq/ack tracking, window management, proper header reconstruction).
- **Embed in monitoring app** — the service can be moved to any app; just copy the `vpn/` package and add the manifest entries.
