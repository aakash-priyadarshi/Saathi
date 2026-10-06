# Generated voice fixture

`voice-qa.m4a` is a one-second 440 Hz sine tone, generated locally with the existing FFmpeg dependency (`lavfi sine`, AAC at 32 kbit/s). It contains no microphone recording or user material. It is packaged only in the test APK and verifies durable AAC voice-note transfer, not microphone recording UX or live call quality. Photo fixtures are generated as a 32×32 solid-color PNG at test runtime.
