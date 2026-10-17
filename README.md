# Bot Lab

An instrumented Android "arena" app that measures what an ordinary app can tell about how it is being operated —
by a human, by `adb input`, or by an accessibility-service-driven agent. It embeds a real anti-abuse SDK
(freeRASP, Talsec's community edition) alongside its own lightweight behaviour analysis, so the numbers are
checks any app could run, not a lab-only metric.

> **Status: experimental.** Split out of [android-ai-harness](../android-ai-harness) on 2026-10-16, where it was
> built to answer "what does our harness reveal?" for that project's safety story. It has no dependency on that
> project to build or run; the `<queries>` entry in its manifest that names `dev.egarcia.harness` is just so Bot
> Lab can detect whether that specific app's accessibility service is installed and enabled, nothing more.

## What it measures

- **Accessibility actions** (`ACTION_CLICK`, `FOCUS`, `SET_TEXT`): no touch events, no keyboard events, no
  `adb input` constant-timing fingerprints.
- **Accessibility gestures** (`dispatchGesture`): the accessibility-gesture flag, a virtual device id, constant
  tap durations, perfectly straight swipes.
- **`adb input`**: a visible input-device signature of its own.
- **freeRASP** (production-mode config): root/emulator/debuggable/sideload/ADB detection, malware scan results
  for every sideloaded app, hook/automation detection — run against Bot Lab itself to see what it reports (or
  doesn't).
- Any app can list enabled accessibility services, and — given a package name — whether a specific
  installed-but-disabled service exists, with no special permission.

## Project stance: measure and disclose, never camouflage

Bot Lab's only purpose is measurement. It does not hide the accessibility service, spoof input metadata, clone
a person's typing or touch profile, fake human timing/pacing, or otherwise try to defeat the very checks it
measures. See the parent project's `docs/non-goals-detection-bypass.md` and ADR 0006 for the reasoning (not
duplicated here to avoid drift — read it there).

## Build and run

Requirements: JDK 17, Android SDK (API 36), an emulator or phone.

```bash
./gradlew testDebugUnitTest assembleDebug
adb install -r build/outputs/apk/debug/botlab-debug.apk
```

Headless control from a computer (the `ControlReceiver`, DUMP-gated):

```bash
adb shell am broadcast -a dev.egarcia.botlab.RESET -n dev.egarcia.botlab/.ControlReceiver
adb shell am broadcast -a dev.egarcia.botlab.SAVE -n dev.egarcia.botlab/.ControlReceiver
adb shell am broadcast -a dev.egarcia.botlab.RASP -n dev.egarcia.botlab/.ControlReceiver
```

## License

MIT — see [LICENSE](LICENSE). Third-party components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
