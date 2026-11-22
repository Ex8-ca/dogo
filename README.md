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

- **Conversational setup.** A chat-driven setup flow where the agent proposes
  concrete changes (theme, wallpaper, folders, home-screen apps, hiding apps,
  animation speed). Nothing is applied until you approve it.
- **On-device AI.** GGUF models run locally through
  [llama.cpp](https://github.com/ggml-org/llama.cpp) (via
  [java-llama.cpp](https://github.com/kherud/java-llama.cpp)). Weights are
  downloaded at runtime, not bundled in the APK, and the catalog only offers
  models that fit your phone's RAM and storage.
- **Optional cloud escalation.** A router decides whether a request stays
  on-device or goes to a cloud model, based on how confident the local model is
  and how complex the request is.
- **Action gates.** You choose which kinds of changes the agent may make, and
  whether each one still needs a confirmation tap.
- **Google-free by design.** Points you to [F-Droid](https://f-droid.org/) and
  [Obtainium](https://github.com/ImranR98/Obtainium) for apps, and pairs well
  with [LineageOS](https://lineageos.org/) without GApps.

## Roadmap

- AccessibilityService and [Shizuku](https://shizuku.rikka.app/) integration so
  the agent can configure apps and system settings, not just the launcher
- **Community plugins**, Omarchy-style: add-ons that extend what the agent can
  do, distributed through an F-Droid repository
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
| `dogo/src/app/dogo/launcher/` | Dogo code: `agent/`, `llm/`, `cloud/`, `ui/setup/`, `ui/options/`, `distribution/` |
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
