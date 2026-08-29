# Documentation

## MVP feature specification

The full feature breakdown, one document per module:

| Module | Document | Tracking |
| --- | --- | --- |
| 1 — Capture & Memory Core | [features/01-capture-and-memory-core.md](features/01-capture-and-memory-core.md) | [#2](https://github.com/alexandre-machado/audio-blackbox/issues/2) |
| 2 — Android Integration & Background Survival | [features/02-android-integration.md](features/02-android-integration.md) | [#3](https://github.com/alexandre-machado/audio-blackbox/issues/3), [#4](https://github.com/alexandre-machado/audio-blackbox/issues/4) |
| 3 — Export Engine | [features/03-export-engine.md](features/03-export-engine.md) | [#5](https://github.com/alexandre-machado/audio-blackbox/issues/5) |
| 4 — User Interface | [features/04-user-interface.md](features/04-user-interface.md) | [#6](https://github.com/alexandre-machado/audio-blackbox/issues/6), [#7](https://github.com/alexandre-machado/audio-blackbox/issues/7) |

## Testing

[testing/tiers.md](testing/tiers.md) — the three test tiers (JVM unit, emulator-based
instrumented, scripted S25 smoke), what each covers, and the single command to run each one.
See also [development/running-on-device.md](development/running-on-device.md) for connecting to
the S25 in the first place.

## Decisions

- **Design system** — Material 3 (stable 1.4.0 line) themed with the avionics/cockpit
  brand palette in `ui/theme/Color.kt`. Originally decided as stock-Material-3-only in
  [#9](https://github.com/alexandre-machado/audio-blackbox/issues/9); superseded by
  [#220](https://github.com/alexandre-machado/audio-blackbox/issues/220) once PR
  [#186](https://github.com/alexandre-machado/audio-blackbox/pull/186) shipped the
  avionics theme and the owner ratified it as the system of record. `docs/design/model.html`
  is the living prototype for this theme (read-only reference, not authoritative text).
