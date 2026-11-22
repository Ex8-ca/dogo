# Dogo assistant: conversations, alerts, search and skills

Status: plan. Written 2026-11-22, after the voice call, ring styles, widgets and
photo-copy work shipped (`4c3ba61c57` to `0bee9554e4`).

Four things were asked for. They share one idea: Dogo is the phone-side
front end of an agent (Hermes, or the on-device model), and the agent's
abilities should grow without Dogo shipping a new release each time.

1. Chat and voice in one place, with saved, named, reusable conversations
   (like Hermes Relay).
2. Watch incoming email and chats, and alert the user to what matters.
3. Web search.
4. Skills, like the Hermes marketplace, that shape how the phone's AI behaves.

## Where things stand

Built and pushed: voice call (light in the Dogo Ring), chat with photo copy,
widgets/dock/layout actions, Fire and Electric ring styles.

Built, tests pass (208/208), **not yet tried on the phone and not committed**:
saved conversations. Files: `ui/setup/Assistant.kt` (one conversation shared by
chat and voice), `ui/setup/Conversations.kt` (JSON on disk, titles, search,
date labels), a rewritten `DogoSetupScreen.kt` (history drawer, voice strip in
the composer, full-screen call, keyboard padding), `DogoCall.kt` reworked to
write into the shared conversation, `ConversationsTest.kt`.

Facts that shape the plan (checked, not assumed):

- Dogo already declares a notification listener
  (`com.android.launcher3.notification.NotificationListener`, used for
  badges). Email and chat apps show up there without Dogo holding any login.
- Hermes does the web already. A voice turn that needs real work goes to
  Hermes `/v1/runs` and Hermes uses its own web, file and memory tools. The
  typed chat does not do this yet; it only asks the model for actions.
- Hermes has a messaging gateway (Telegram, Email, Signal, ntfy, and others),
  cron jobs that deliver results to a chat, and webhooks that start a run when
  an event arrives. ntfy is a plain HTTP push service, so a phone with no
  Google services can subscribe to it directly. Telegram on this server is
  already configured.
- The marketplace (hermes-agent.nousresearch.com/marketplace) lists 2,578
  plugins, tabs for Plugins, Skills and Connectors, and one page per item
  (`/marketplace/<name>`) with an install command (`hermes plugins install
  <name>`) and a `hermes://plugin/install?catalog=<name>` link. Many entries
  carry a long "Disclosure" saying what they run and send. I did not find a
  public JSON catalog; that is a spike (see Phase 4).
- Hermes skills are folders with a `SKILL.md`, loaded on demand: the agent sees
  a name and one-line description for each, and reads the body only when it
  fits the task.

## Phase 0: finish conversations (small, do first)

Goal: the history work is real before anything is built on it.

- Install on the phone and walk through: type a chat, talk a call from the
  ring light, open the chat from the call, come back later, open an old
  conversation, search, delete, restart Dogo and check it all persists.
- Check the Hermes session carries over: resume a conversation, ask about
  something said earlier in it by voice.
- Check what the earlier build got wrong on the phone: the Send button behind
  the keyboard (now `imePadding`), and accent changes wiping the chat.
- Better names. Today the title is the first message cut at a word. After the
  first reply, ask the model for a 3 to 5 word title and store it (keep the
  cut-off title as the fallback and let the user rename by long-pressing).
- Then commit as its own change.

Done when: every step above works on the Pixel, and the commit passes CI.

## Phase 1: one agent for chat and voice, with web search

Correction (2026-11-22, tested on the Pixel): this phase was written assuming
typed chat could not use Hermes's tools. It can. Chat goes to Hermes's
OpenAI-style endpoint, which runs the full agent. Asking "search the web for
the latest stable LineageOS and tell me the URL you used" returned an answer in
20 seconds with the URL, and the agent said itself that the page was a
third-party tracker and not the official site. So for Hermes, search already
works by typing and by voice.

What is left:

- Sources as tappable chips under an answer (today the URL is plain text in the
  reply). Parse links from the reply; no extra model call.
- A visible "searching the web" state while Hermes works (typed chat only shows
  "Thinking…").
- Agents that are not Hermes (the on-device model, a plain OpenAI-style server)
  have no search. Options: SearXNG on the .3 server, or a search API key in
  the Keystore. Decision 3 below; skip this if Hermes is always the agent.
- Tell the agent to prefer official sources (it already hedges honestly; a line
  in the system prompt makes it check them).

Size: 1 day, not 2 to 3.

## Phase 2: proactive alerts

Design check against the real code (2026-11-22). Findings that change the work:

- Hook: one line in `NotificationListener.onNotificationPosted` (line ~275),
  handing plain fields to a background worker. The existing dot logic stays
  untouched.
- Trap: turning off notification dots makes the listener unbind itself
  (`NotificationListener.java` ~255-259), which would silently stop alerts.
  Guard it when alerts are on.
- Dogo posts no notifications today and never asks for `POST_NOTIFICATIONS`
  (declared, never requested). Needed on Android 13+, asked when alerts are
  turned on. Four channels by tier (interrupt, notify, digest, service);
  importance can't be changed after creation, so choose it up front.
- Dogo's own alerts re-enter its own listener; filter its package.
- Updates re-fire for the same notification key: dedupe by key and a
  per-sender cooldown.
- ntfy: a foreground service with a streaming OkHttp connection and
  `?since=` resume. Not WorkManager (15-minute floor, Doze), and it isn't a
  dependency here anyway. Use the `specialUse` service type; `dataSync` is
  time-limited on Android 15.
- Alert rules live in their own store, not in `AgentOptions` (the
  every-action-has-a-switch test only covers agent actions). A "send a reply"
  action, if ever added, is an agent action and goes through that test.
- Privacy: never store notification text; the rolling log holds time, app,
  sender key, score and reason only. Keep it out of conversations and out of
  Android backup. Scoring is hard-wired to the on-device model; any remote
  scorer is per-app opt-in, checked inside the scorer so a test can assert it.

Build order: pure engine and tests (`alerts/`, no Android imports) first, then
the listener hook and notifier, then settings, then ntfy.

Goal: Dogo tells you about the things you would want to know, and stays quiet
otherwise. Quiet matters more than clever; one wrong alert a day and the user
turns it off.

Two sources, because they cover different things.

A. On the phone: the notification listener Dogo already has. It sees mail and
   chat app notifications (Gmail-free mail clients, Signal, Matrix, SMS) with
   no logins. Each incoming notification is a candidate.

B. On the Hermes server: the gateway's Email and Telegram platforms, and cron
   jobs ("every 15 minutes, check my inbox for anything urgent"). Results are
   pushed to the phone over ntfy, which Dogo subscribes to directly (HTTP
   stream, no Google). This reaches mail that has no app on the phone.

How a candidate becomes an alert, cheapest check first:

1. Rules the user can see and edit: people who always count (VIPs), words that
   count (invoice, deadline, delivery), apps to ignore.
2. Only for what rules leave undecided, a model scores it 0 to 3: ignore,
   digest, notify, interrupt. On-device model by default.
3. Output matches the score. Ignore: nothing. Digest: collected into one
   summary at set times. Notify: a Dogo notification, plus a count on the ring
   light. Interrupt: heads-up, and offers to read it aloud.
4. Each alert has actions: draft a reply (shown as a card to approve), snooze,
   "never alert for this sender", "this was useful / not useful". The last two
   feed the rules, so it learns what matters.

Privacy decisions (need your call, see below):

- Notification text is personal. Default: it is scored on the phone and never
  leaves it. Sending it to Hermes is an explicit per-app opt-in.
- Dogo reads and stores nothing it does not alert on, beyond a short rolling
  log the user can clear.
- Nothing is sent or replied to without a card the user approves, the same
  rule as phone changes today.

Done when: with a week of real use, the user can see why each alert fired, and
turning off one sender or app takes two taps.

## Phase 3: skills that shape the assistant

Goal: install a skill and the assistant behaves differently, on the phone and
on Hermes.

Two layers, because the phone's model and Hermes are different agents.

Hermes side (free, already works): skills installed on the Hermes host shape
every Hermes-backed turn. Dogo's job is only to show and manage them: list
installed skills, install by name, remove. These run as `hermes skills ...`
on the server; the phone asks Hermes to do it and shows an approval first.

Phone side (new): a skill folder on the phone, same `SKILL.md` format, so one
skill works in both places. Rules:

- The system prompt carries only each skill's name and description. The body
  is loaded when the model asks for it (same progressive loading Hermes uses),
  so ten skills do not cost ten prompts.
- Skills can add instructions and name phone actions they may use. They cannot
  add new powers on their own: anything that changes the phone still goes
  through the existing action switches and approval cards.
- A "Skills" screen in Dogo Options: installed, on/off per skill, and which
  agent each one applies to.
- Ship three starter skills of our own to prove the loop (for example a
  morning briefing, a "tidy my home screen" routine, an email-triage helper
  that Phase 2 uses).

Done when: dropping a new `SKILL.md` in changes the answer to a matching
request, on both Gemma and Hermes, with no app update.

## Phase 4: marketplace

Spike result (2026-11-22, read-only, checked against the live site): the
marketplace does have a public source, so Dogo should not scrape pages.

- Plugins: `https://hermes-agent.nousresearch.com/docs/api/plugin-catalog.json`
  (redirects to GitHub Pages, about 5 MB, `{generated_at, entries[2578]}`).
  This is the file Hermes' own CLI and Desktop read. Each entry has a name, a
  repo, a mandatory 40-character `sha` pin, a tier (official, verified,
  community), a description, and a `capabilities` block (tools, hooks, env it
  needs) plus `known_issues`.
- Skills: `.../docs/api/skills.json` is a 67 MB array of about 105,000 items
  across several registries. Too big for a phone. Use only the official subset
  (about 141 optional skills), or ask Hermes to search.
- Not a documented API: no version number or stability promise. Parse
  defensively (only name, repo and sha required), follow redirects, cache with
  revalidation, show `generated_at`, and ship a small bundled snapshot as a
  fallback.
- The phone installs nothing itself. Installs go through the Hermes host
  (`hermes plugins install <name>`, `hermes skills install <installIdentifier>`
  or the `hermes://` links), so the SHA pin and the security scan stay in the
  path. Community skills are blocked by the scan if it finds anything cautionary.
- Skill format: a folder with `SKILL.md` (YAML frontmatter: name, description,
  version, platforms, metadata.hermes.{tags, category, config}) and optional
  references/, templates/, scripts/. Same format on the phone, as planned.

Still unchecked: whether the files support conditional requests (only
Last-Modified was seen), and the gzip size of skills.json.

Goal: browse and install from the Hermes marketplace inside Dogo.

- Spike first (half a day): find where the marketplace gets its list (an index
  on GitHub, or an endpoint the page calls). If there is no stable public
  source, do not scrape the site from the phone. Fallback: Dogo asks Hermes to
  search and install ("hermes skills search/install"), and shows the result.
- Show the item's own disclosure text before install. Many entries run code
  or send data off the machine; the user should read that, not a summary.
- Install gate: always an approval card, never silent, and only skills or
  plugins; Connectors and model providers stay out of the first version.
- Phone-only skills are plain text files, so installing one cannot run code.
  Anything that ships code installs on the Hermes host, not on the phone.
- Keep a short "recommended for Dogo" list we vouch for: Telegram Business
  (drafts replies, you approve) fits Phase 2 well; phone-harness is the
  opposite way round (Hermes driving a phone over adb) and is not a goal here.

Done when: from the phone, find a skill, read what it does and what it
touches, approve, and it is used on the next turn.

## Phase 5: system access, and the Tenkai roadmap

Added 2026-11-22 after comparing with Tenkai's public roadmap
(tenkailauncher.com/#roadmap, read that day). Every item is tagged with the
access it needs, because that decides whether anyone can use it.

Access levels, lowest first (see `privilege/Tier.kt`):

- **Dogo only**: works for everyone, nothing to set up.
- **Permission**: a normal Android toggle the user flips once (for example
  "draw over other apps", notification access).
- **adb grant**: one command from a computer, once. Survives reboots.
- **Shizuku or root**: shell commands run by Dogo. Shizuku needs no root.
- **System app**: needs Dogo built into the OS (the LineageOS build).

What exists today. Dogo only: theme, folders, hide apps, ring, wallpaper,
widgets, dock, grid, icon shape and size, accent, icon pack. adb grant:
widget binding without a prompt per widget, animation speed. Shizuku or root:
system dark mode, and nothing else. The Shizuku and root routes are written but
have not been tried on the phone; the user accepts any root prompt themselves.

### 5a. More the agent can do once it has a shell (Shizuku or root)

Each is one reviewable command behind an approval card and its own switch in
Dogo Options, like every other action.

- Freeze, unfreeze or uninstall bloat apps; grant or revoke a permission for
  another app (`pm`).
- Wi-Fi, Bluetooth, airplane mode, Do Not Disturb, battery saver (`cmd`,
  `settings`, `svc`).
- Screen density, font size, screen timeout (`wm`, `settings`): "make
  everything bigger".
- System-wide accent colour, font and icon shape through overlays
  (`cmd overlay`), so the change reaches Settings and other apps, not only Dogo.
- Switch on Dogo's notification access without a trip through Settings.
  Phase 2 depends on this, so it comes first.
- Freeform or tiled windows, from the earlier Omarchy question.

Out of reach even with root: the status bar, navigation bar and boot
animation. Those wait for the system-app build.

### 5b. Tenkai roadmap items, mapped

| Tenkai item | Their status | Dogo | Access | Where |
|---|---|---|---|---|
| Stacked and grouped widgets | Shipped (1.6.0) | Not built. Needs a container widget; Lawnchair has none | Dogo only | Own task after Phase 3 |
| Notification and to-do counts in the date line | Next up | Phase 2 gives the data | Permission | Phase 2 |
| Catch-up and notes widget | Next up | Alerts digest plus quick notes | Permission | Phase 2 |
| Floating pie over any app | Exploring | Not built | Permission (draw over apps) | Own task; after the ring is checked on the phone |
| Home-screen spotlight search | Exploring | Not built | Dogo only | Own task; can search apps, contacts and the web through Phase 1 |
| Optional on-device AI suggestions | Exploring | Mostly there: the agent already does this | Dogo only | Phases 1 to 3 |
| A to Z index slider | Exploring | In Lawnchair already | Dogo only | Done |
| Android Private Space | Exploring | Should work on Android 15 builds; LineageOS 22 qualifies | Dogo only | Check on the phone |
| HTTP queries, RSS | Their pricing page | Plugins | Dogo only | Phase 3 skills |

Order inside this phase: notification access (5a), then counts and the
catch-up widget (Phase 2 items), then spotlight search and the floating pie.
Test Shizuku on the phone before building more shell actions on it.

Done when: Shizuku is paired on the Pixel and one shell action runs end to end
from a card, and each Tenkai row above is either built, scheduled, or marked
"not doing".

## Order and size

| Phase | What | Size |
|---|---|---|
| 0 | Verify and commit conversations; better titles | 1 day |
| 1 | Typed chat uses Hermes; web search; sources | 2 to 3 days |
| 2 | Alerts: listener, rules, scoring, ntfy, digest | 1 to 1.5 weeks |
| 3 | Skills format, loader, Options screen, 3 starters | 1 week |
| 4 | Marketplace spike, browse, install gate | 3 to 5 days |
| 5 | Shizuku test, shell actions, Tenkai items | 1.5 to 2 weeks, in pieces |

Each phase ships on its own and is tagged, as before. Phase 2 depends on
nothing but Phase 0; Phase 3 should land before Phase 4. Phase 2 is the one
the user will feel most, so it should not wait behind skills.

## Decisions needed

1. Notification text: on-device scoring only (recommended), or allow sending
   it to Hermes for apps the user picks?
2. Push from the server: ntfy (open, Google-free, self-hostable on the .3
   server) or reuse the Telegram bot already on Hermes?
3. Web search for the on-device model: SearXNG on the .3 server, or a search
   API key? Or skip it and let Hermes do all searching?
4. Phone skills: same `SKILL.md` format as Hermes (recommended, one skill in
   both places), or a Dogo-only format?

## Not in this plan

Reading mail directly over IMAP from the phone, replying without approval,
installing marketplace plugins onto the phone itself, and any cloud account
for Dogo. Each is a bigger trust step and should be its own decision.

## Known unverified

- Whether the Hermes marketplace has a public catalog endpoint.
- How much a notification listener sees from the user's mail and chat apps
  (some hide content on the lock screen or in work profiles); Phase 2 starts
  by logging what arrives from the apps actually in use.
- Fire and Electric ring styles were tuned from screenshots only and have not
  had a final look on the phone.
