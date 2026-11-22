# Dogo

**An AI launcher for Android: set up and customize your phone by talking to it.**

Dogo is to an Android phone roughly what [Omarchy](https://omarchy.org/) is to
an Arch Linux PC: an opinionated, Google-free layer on top of the OS, where an
AI agent does the configuring for you and the community can extend it with
plugins.

> **Status: early development (pre-alpha).** This branch (`dogo/foundation`)
> contains the agent core, the on-device model layer, and the setup and options
> screens. Expect breaking changes, and don't make it your daily launcher yet.

## Built on Lawnchair

Dogo is a fork of **[Lawnchair](https://github.com/LawnchairLauncher/lawnchair)**,
the free, open-source Android launcher, which is itself based on **Launcher3**
from the [Android Open Source Project](https://source.android.com/). Almost all
of the launcher itself (home screen, app drawer, icons, gestures, Recents
integration) is Lawnchair's work. Dogo adds the AI agent on top.

Thanks to the Lawnchair team and contributors. If you like the launcher, please
consider [supporting Lawnchair on Open Collective](https://opencollective.com/lawnchair).
Lawnchair's original README is kept in
[`docs/LAWNCHAIR_README.md`](docs/LAWNCHAIR_README.md).

Dogo is an independent project. It is **not affiliated with or endorsed by**
the Lawnchair project or Google. Please report Dogo bugs
[here](https://github.com/Ex8-ca/dogo/issues), not to Lawnchair.

## What Dogo adds

- **The Dogo Ring.** Touch and hold anywhere on the home screen and a radial
  menu opens under your thumb (inspired by
  [Tenkai Launcher](https://tenkailauncher.com/)'s pies). Slide to a group,
  then to an app, and let go, all in one swipe; or release in the middle and
  tap. Apps are grouped automatically, or ask Dogo to arrange them. Three
  styles (Glow, Radar, Minimal) that take their colour from your theme.
- **Talk to it.** The centre of the Dogo Ring is a living light: tap it and
  just talk. OpenAI's Realtime voice answers out loud, and you can interrupt
  it. The light breathes, turns jade while it listens, gathers sparks while
  your Hermes does real work (web, files, memory) and glows gold when it
  speaks, like [Hermes Vessel](https://github.com/Ex8-ca/hermes-vessel). Any
  changes it suggests show up as cards to approve. The mic in Dogo Setup does
  the same. The OpenAI key can stay on your own computer
  ([`tools/realtime`](tools/realtime)), so the phone only ever holds a key that
  expires in ten minutes.
- **Conversational setup.** A chat where the agent proposes concrete changes:
  Dogo's theme, the phone's dark mode, wallpaper, folders, home-screen apps,
  hiding apps, animation speed. Each change shows up as a card with **Apply**.
  The agent sees your installed apps, so it uses real package names.
- **On-device AI.** GGUF models run locally through
  [llama.cpp](https://github.com/ggml-org/llama.cpp) (via
  [java-llama.cpp](https://github.com/kherud/java-llama.cpp)). Weights are
  downloaded at runtime, not bundled in the APK, and the catalog only offers
  models that fit your phone's RAM and storage.
- **Choose your agent.** Like Omarchy, Dogo doesn't pick a vendor for you.
  Use the on-device model, [Hermes Agent](https://github.com/NousResearch/hermes-agent)
  on your own computer, OpenRouter, Groq, Claude, Ollama, any
  OpenAI-compatible server, or a CLI agent (Claude Code, OpenCode, Codex,
  Hermes) installed in [Termux](https://termux.dev/). "Test connection" checks
  it. API keys are encrypted with an Android Keystore key, and plain http is
  only allowed to the phone itself.
- **Least-privilege system access.** Every change uses the least access that
  works. Launcher changes need nothing. Animation speed needs a one-time
  `adb pm grant`. The phone's dark mode needs
  [Shizuku](https://shizuku.rikka.app/) or root. Dogo tells you exactly what
  to set up when a change is blocked.
- **Plugins.** Add-ons are ordinary APKs with a manifest shaped like an
  Omarchy plugin's, plus a list of capabilities you approve before enabling
  them. Their actions show up in the chat like built-in ones. See
  [`docs/plugins.md`](docs/plugins.md) and the sample in
  [`plugins/sample-notes`](plugins/sample-notes).
- **Omarchy themes.** Apply any official
  [Omarchy](https://github.com/omacom/omarchy) theme, or a community theme from
  GitHub, to Dogo: light/dark mode, accent colour and wallpaper.
- **Action gates.** You choose which kinds of changes the agent may make, and
  whether each one still needs a confirmation tap.
- **Google-free by design.** Points you to [F-Droid](https://f-droid.org/) and
  [Obtainium](https://github.com/ImranR98/Obtainium) for apps, and pairs well
  with [LineageOS](https://lineageos.org/) without GApps.

## Roadmap

- AccessibilityService so the agent can read the screen and configure other apps
- More plugin kinds wired up (widgets, panels, agent context), and a Dogo
  plugin repository on F-Droid
- F-Droid release
- Later: a LineageOS-based build with Dogo as a privileged system app

The original plan is in
[`docs/plan/`](docs/plan/2026-10-07-dogo-phone-launcher.md), known issues are in
[`dogo/BLOCKERS.md`](dogo/BLOCKERS.md), and the rebranding approach is
described in [`dogo/REBRAND.md`](dogo/REBRAND.md).

## Building

Requirements: JDK 21, the Android SDK, and Git with submodules.

```sh
git clone --recurse-submodules -b dogo/foundation https://github.com/Ex8-ca/dogo.git
cd dogo
./tools/dev/dev.sh setup     # init submodules, write local.properties
./tools/dev/dev.sh build     # Dogo debug APK (app.dogo.launcher.debug)
./tools/dev/dev.sh test      # JVM unit tests
./tools/dev/dev.sh apk       # list built APKs
```

Pinning the toolchain with [mise](https://mise.jdx.dev/) is optional; see
[`tools/dev/README.md`](tools/dev/README.md).

The `dogo` build channel has its own application ID, so it installs next to a
regular Lawnchair build. `./tools/dev/dev.sh build github` builds the
upstream-flavoured variant for comparison.

## Where the code lives

| Path | What |
|---|---|
| `dogo/src/app/dogo/launcher/` | Dogo code: `agent/` (actions, options, `backend/` agents), `privilege/` (access tiers, applying changes), `plugins/`, `themes/`, `llm/`, `ui/` |
| `plugins/sample-notes/` | A minimal example plugin (separate APK) |
| `dogo/tests/`, `dogo/androidTest/` | Dogo unit and on-device tests |
| `dogo/res/` | Dogo strings and branding overrides |
| everything else | Lawnchair / Launcher3, kept close to upstream so merges stay easy |

Upstream files that Dogo changes carry a `Modified by The Dogo Authors` notice
at the top.

## License

Dogo is licensed under the **[Apache License 2.0](LICENSE.txt)**, the same
license as Lawnchair and Launcher3.

- Copyright © 2005–2008 The Android Open Source Project
- Copyright © 2024 Lawnchair
- Copyright © 2026 The Dogo Authors

See [`NOTICE`](NOTICE) for attribution and third-party components. Downloaded
model weights are not part of Dogo and are covered by their publishers' own
licenses.
