# Physical device verification worksheet

Owner currently has Android phones only. All physical rows start **NOT RUN**. Do not mark a browser viewport emulation as Android hardware verification.

Confirmed first device: **Samsung Galaxy S24, Android 16** (reported by the owner). Record the second phone's model, Android version, browser version, GMS availability and security patch when testing. No second device model or hardware capability has been measured. Check `FEATURE_WIFI_AWARE`/`isAvailable()` and Wi-Fi Direct support on the device; the S24 name alone is not evidence of interoperable Aware operation.

For this S24 test camera/microphone denial and later grant, Android Nearby devices permissions for native probes, Samsung battery saver and app sleep settings, foreground/background and screen lock. Any OS settings change is a user action through the normal settings UI. Saathi must explain a denial and retain local work, never silently change radios or battery policy.

Use operator HTTPS for the PWA; `http://192.168…` is not a production or secure-origin substitute. Preload/prepare both devices online, retain the secure origin, then disconnect WAN/cellular while leaving the local network available. Keep a third carrier device for the multi-hop test when possible.

| Test                                                             | Models / OS / browser / app | Success / attempts | Median / p95 latency | Verified throughput | Range / battery | Result  |
| ---------------------------------------------------------------- | --------------------------- | ------------------ | -------------------- | ------------------- | --------------- | ------- |
| Android browser pair, same Wi-Fi, WAN disconnected               | —                           | — / 20             | —                    | —                   | —               | NOT RUN |
| Android browser pair, phone hotspot                              | —                           | — / 20             | —                    | —                   | —               | NOT RUN |
| Native pair, local-only hotspot                                  | —                           | — / 20             | —                    | —                   | —               | NOT RUN |
| Native pair, Nearby Connections                                  | —                           | — / 20             | —                    | —                   | —               | NOT RUN |
| Native pair, Wi-Fi Aware                                         | —                           | — / 20             | —                    | —                   | —               | NOT RUN |
| Native pair, Wi-Fi Direct                                        | —                           | — / 20             | —                    | —                   | —               | NOT RUN |
| Native BLE small-event fallback                                  | —                           | — / 20             | —                    | —                   | —               | NOT RUN |
| Browser ↔ native, shared LAN                                     | —                           | — / 20             | —                    | —                   | —               | NOT RUN |
| Internet disappears/returns; event reaches canonical server once | —                           | —                  | —                    | —                   | —               | NOT RUN |
| Peer leaves range/returns; saved message survives                | —                           | —                  | —                    | —                   | —               | NOT RUN |
| Foreground → background → lock → battery saver                   | —                           | —                  | —                    | —                   | —               | NOT RUN |
| 1 MiB image, interrupted/resumed; checksum matches               | —                           | —                  | —                    | —                   | —               | NOT RUN |
| Audio/video call, permission denial, adaptive video pause        | —                           | —                  | —                    | —                   | —               | NOT RUN |
| A authors → B retains → C uploads → receipt returns to A         | —                           | —                  | —                    | —                   | —               | NOT RUN |
| Every iPhone/native/iOS pairing combination                      | iPhone unavailable          | —                  | —                    | —                   | —               | NOT RUN |

Also test AP client isolation, VPN, invalid/expired pairing, denied camera/mic, insufficient disk space, revoked volunteer/device, duplicate carriers, altered signatures and stale quantity edits. For battery record a one-hour idle baseline and foreground transfer/discovery consumption at fixed settings. Device behavior is allowed to fail honestly; the UI must preserve saved work and explain the next supported action.
