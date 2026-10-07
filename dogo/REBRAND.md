# Dogo — rebranding the fork

Dogo is a fork of Lawnchair (itself an AOSP Launcher3 fork). The Java/Kotlin
packages are deliberately **not** renamed: `app.lawnchair.LawnchairLauncher`,
`com.android.launcher3.*` and friends are wired together through the manifest,
Dagger modules, resource references and `BuildConfig` lookups. Renaming them is
a large, risky change that buys nothing user-visible.

Instead the fork ships as its own **distribution channel**.

## The `dogo` channel

A new product flavour in the root `build.gradle`:

| | `github` (upstream) | `dogo` (ours) |
|---|---|---|
| applicationId | `app.lawnchair[.debug]` | `app.dogo.launcher[.debug]` |
| app label | `Lawnchair` / `Lawnchair (Debug)` | `Dogo Phone` |
| APK filename | `Lawnchair.<ver>.<channel>.<type>.apk` | `Dogo.<ver>.<channel>.<type>.apk` |

Both install side by side, which is the point: you can run a stock Lawnchair
and Dogo on the same phone and compare.

```sh
./dev.sh build          # Dogo Phone  -> app.dogo.launcher.debug
./dev.sh build github   # Lawnchair   -> app.lawnchair.debug
./dev.sh test           # unit tests (defaults to the dogo channel)
```

The `dogo` channel also maps to `quickstep/AndroidManifest-launcher.xml`, like
the other shipping channels. The plain `quickstep/AndroidManifest.xml` drags in
Smartspacer, which declares `minSdk 29` and fails the manifest merge against
this project's `minSdk 26`.

## Per-variant strings

`derived_app_name` and `launcher_component` are emitted by a small
`GenerateAppNameRes` task from `androidComponents.onVariants`, rather than as
`resValue`s. Two resValue behaviours forced this:

1. **buildType beats flavour.** A `resValue` in the `dogo` flavour for
   `derived_app_name` was silently ignored, because `debug` also defined one and
   AGP gives the buildType version precedence. Verified: the APK shipped with the
   label `Lawnchair (Debug)`.
2. **`${applicationId}` is not resolved during flavour configuration.** The old
   `configureEach { resValue(... "${applicationId}/...") }` produced the literal
   `"null/app.lawnchair.LawnchairLauncher"` on *every* channel — including the
   stock `github` build. `AppFilter` runs that string through
   `ComponentName.unflattenFromString`, yielding a component with a null
   package that can never match, so the launcher did not actually filter itself
   out of `filtered_components`.

   The existing `AppFilterTest` mocks `getStringArray`, so it could not catch
   this. Verified fixed in the built APKs:
   `app.dogo.launcher.debug/app.lawnchair.LawnchairLauncher` and
   `app.lawnchair.debug/app.lawnchair.LawnchairLauncher`.

## Verifying a rebrand

The build succeeding proves nothing — both bugs above produced green builds.
Check the artefact:

```sh
$ANDROID_HOME/build-tools/37.0.0/aapt2 dump badging <apk> \
  | grep -E "^package:|application-label:"
$ANDROID_HOME/build-tools/37.0.0/aapt2 dump resources <apk> \
  | grep -A1 'string/launcher_component$'
```