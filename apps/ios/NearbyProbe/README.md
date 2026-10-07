# iPhone ↔ Android Nearby probe

Answers the first transport question in `apps/ios/README.md`: can an iPhone and an
Android phone connect through Google Nearby Connections, offline? It pairs with the
Android probe in `spikes/android` (service `org.saathi.probe.v1`, point-to-point),
compares the verification code, then runs that probe's own tests: 20 round trips
and a 1 MiB checksum-verified transfer in each direction.

This is a test instrument, not the Swarm app. It uses no Swarm identity or server.

## Run

```sh
brew install xcodegen
cd apps/ios/NearbyProbe && xcodegen generate
open NearbyProbe.xcodeproj   # set your team under Signing & Capabilities, run on the iPhone
```

Android side: `cd spikes/android && ./gradlew :app:assembleDebug`, then install
`app/build/outputs/apk/debug/app-debug.apk`.

On the iPhone: Settings → Privacy & Security → Developer Mode (restart), then
after the first install, Settings → General → VPN & Device Management → trust the
developer. Allow Bluetooth, Local Network and Location when asked.

## Test matrix (record each result)

1. Both phones: Wi-Fi on, Bluetooth on, no internet (mobile data off, no router
   internet). iPhone searches, Android advertises; then swap roles.
2. Wi-Fi **off** on both (Bluetooth only).
3. A shared Wi-Fi network without internet.

For each: connected or not, the round-trip median/p95, and 1 MiB send/receive
time with "checksum VERIFIED". Google pins Nearby's iOS mediums per release; this
probe pins `google/nearby` at a fixed revision in `project.yml`.
