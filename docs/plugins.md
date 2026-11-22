# Writing a Dogo plugin

A Dogo plugin is an ordinary Android app. Nothing has to be linked against
Dogo. It needs two things:

1. `assets/dogo-plugin.json`, a manifest saying what the plugin offers.
2. One exported service per entry point, answering the intent
   `app.dogo.launcher.PLUGIN`.

Dogo finds installed plugins automatically and lists them under
**Dogo Options > Plugins**. Nothing runs until the user switches a plugin on,
after seeing what it can do. If an update asks for new capabilities or
actions, the plugin is switched off until the user approves it again.

A working example is in [`plugins/sample-notes`](../plugins/sample-notes). It
lets the agent save notes and is about 80 lines of Java. Copy it to start your
own.

## Coming from Omarchy

The manifest uses the same shape as an Omarchy plugin's `manifest.json`, so
most fields carry over directly:

| Omarchy | Dogo | Notes |
|---|---|---|
| `schemaVersion`, `id`, `name`, `version`, `author`, `license`, `description` | same | `id` must be namespaced, e.g. `io.github.you.plugin` |
| `kinds: ["bar-widget"]` | `"widget"` | A home-screen AppWidget. `bar-widget` is accepted as an alias. |
| `kinds: ["panel"]` | `"panel"` | A sheet opened from Dogo |
| `kinds: ["service"]` | `"service"` | Background work |
| *(none)* | `"agent-actions"` | Actions the AI agent can propose. This is the main Dogo extension point. |
| *(none)* | `"agent-context"` | Facts added to the agent's prompt |
| `entryPoints: {"barWidget": "BarWidget.qml"}` | `entryPoints: {"agentActions": ".MyService"}` | Android component names instead of QML files |
| *(implicit: full user access)* | `capabilities: [...]` | Required, see below |

QML can't run on Android, so the code itself has to be rewritten. If your
Omarchy plugin keeps its logic in a helper script that prints JSON (as many
do), the logic usually ports over directly and only the UI changes. Set
`"omarchy": {"portedFrom": "<omarchy plugin id>"}` to credit the original.

**The big difference is permissions.** Omarchy plugins run with your full user
permissions. A phone holds messages, photos and banking apps, so a Dogo plugin
must declare up front everything it wants, and the user sees that list before
enabling it:

| Capability | Meaning |
|---|---|
| `network` | Use the internet |
| `read-apps` | See which apps are installed |
| `read-screen` | Read text on the screen |
| `notifications` | Show notifications |
| `system-settings` | Change system settings (Dogo routes this through Shizuku or root) |

## The manifest

```json
{
  "schemaVersion": 1,
  "id": "io.github.you.timer",
  "name": "Timer",
  "version": "1.0.0",
  "author": "You",
  "license": "MIT",
  "description": "Lets the agent start kitchen timers.",
  "kinds": ["agent-actions"],
  "entryPoints": { "agentActions": ".TimerService" },
  "capabilities": ["notifications"],
  "actions": [
    {
      "name": "start_timer",
      "description": "Start a countdown.",
      "params": { "minutes": "number of minutes", "label": "optional name" }
    }
  ]
}
```

Rules, all checked when the plugin is discovered:

- `schemaVersion` is `1`.
- `id` is lowercase and dotted, with at least two segments.
- `license` is an SPDX id.
- Every kind in `kinds` has an entry in `entryPoints`, written in camelCase
  (`agent-actions` becomes `agentActions`).
- An `agent-actions` plugin lists its `actions`. Action names are snake_case
  and unique.

## Agent actions

Once the plugin is enabled, the agent is told about each action under its
namespaced name, `io.github.you.timer:start_timer`, and can propose it like
any built-in action:

```json
{"action": "io.github.you.timer:start_timer", "params": {"minutes": 10}}
```

The user still has to approve it, and it is subject to the "Run plugin
actions" switch in Dogo Options. After approval, Dogo **binds** to your
`agentActions` service. Binding works even when your app is in the
background, which `startService` does not. Dogo then sends one
[`Message`](https://developer.android.com/reference/android/os/Message):

| Field | Value |
|---|---|
| `what` | `1` (run action) |
| `data.getString("action")` | the action name, e.g. `start_timer` |
| `data.getString("params")` | the params object as a JSON string |
| `replyTo` | a `Messenger` to answer on |

Reply with one message: `what = 0` for success or `1` for an error, and
`data.putString("message", "…")` with a short sentence. That sentence is shown
in the chat. Dogo unbinds after the reply and gives up after 15 seconds.

```xml
<service android:name=".TimerService" android:exported="true">
    <intent-filter>
        <action android:name="app.dogo.launcher.PLUGIN" />
    </intent-filter>
</service>
```

See `NotesActionService.java` in the sample for a complete service.

## Building and installing the sample

```sh
./gradlew :plugins:sample-notes:assembleDebug
adb install plugins/sample-notes/build/outputs/apk/debug/sample-notes-debug.apk
```

Then go to Dogo Options > Plugins, switch Notes on, and ask the agent to save
a note.

## Distribution

Plugins are plain APKs, so publish them anywhere: F-Droid, IzzyOnDroid,
Obtainium, GitHub releases. A Dogo plugin repository on F-Droid is planned.
