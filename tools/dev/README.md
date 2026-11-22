# Dev tooling

- `dev.sh` — build/test helper (`build`, `test`, `spotless`, `setup`, `apk`).
  Defaults to the repo containing it; override with `DOGO_REPO`.
- `mise.toml.example` — pins JDK 21 (matches CI) and sets `ANDROID_HOME`.
  Copy it to the *parent* directory of the clone as `.mise.toml` and put the
  Android SDK at `<parent>/.android-sdk` (the SDK is not committed).
