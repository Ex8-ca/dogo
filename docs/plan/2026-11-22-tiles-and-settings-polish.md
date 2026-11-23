# Dogo: settings polish and agent-written widget tiles

This is the implementation plan and resumable handoff for Phase 7 of
`2026-11-22-assistant-roadmap.md`. Updated after the real-widget checkpoint
`6c17316b35` on branch `dogo/foundation`.

## Goal

The agent arranges real Android widgets by writing a small text file. Dogo
validates the file, previews the changes, asks for approval, applies the layout,
and helps check how it looks. No code in the file executes. Only widgets Dogo
placed for tiles are managed by this feature; user icons, folders and widgets
must be preserved.

The user's requested loop is: create and size widgets, take a screenshot, and
check that the layout looks good. Database placement alone is not visual proof.

## Verified checkpoints

### Settings polish — shipped

- `060cb5c91f`: Dogo Options grouped into categories, tools-off route card.
- `8d6c6a3822`: Get link for missing Shizuku, unavailable local model dimmed,
  consistent control edges and section order. Built and checked on the phone.

### Step 1 — parser and geometry — shipped

- Pure Kotlin parser/writer/config/geometry in `dogo/src/app/dogo/launcher/tiles/`.
- Page grids are 3..10 columns and rows, matching the launcher.
- Bounded file and line lengths; plain, bounded error messages.
- Real widget kind: `widget:<package>/<class>`.
- Built-in kinds are not implemented by the placement layer.

### Step 2 — planner and real widgets — working checkpoint

- `0b91e8a995`: pure `TilePlanner`, adds/moves/resizes/removals/refusals.
- `6c17316b35`: `ApplyTilesTask`, `TileStore`, `write_tiles` action and tests.
- Apply reads current launcher pages and provider limits, uses explicit cells,
  persists through the launcher database, and tracks managed widget IDs under
  `noBackupFilesDir/tiles/state.json`.
- Only tracked widgets are changed. Other items occupy cells the planner must
  protect. Page 1 reserves row 0 while the date line is enabled.
- The previous file is saved. Undo has been exercised through the debug trigger;
  a production one-tap Undo control is still needed.
- Last full committed checkpoint: 499 tests, zero failures; APK built and
  installed on the Pixel 2 XL. Secret scan clean before the commit.
- Device cases exercised: add, move, resize, remove, undo, too-small provider
  refusal, and a widget needing its own setup screen. Database and screenshots
  were checked. An unconfigured Digital Clock looked blank until its style
  picker was confirmed, then rendered normally.
- Test arrangement uses page 2 of the 4x6 launcher grid. The user's page 1 and
  dock were not intentionally rearranged.
- Screenshot evidence lives outside the repo under
  `/home/marc/.hermes/cache/scratch/shots/` (`t_page2.png`, `t_three.png`,
  `t_final.png`, `t_swap.png`, `t_pending.png`, `t_cfg.png`, `t_done.png`).

### Step 3 — agent action — partly shipped

- `Action.WriteTiles`, JSON action `write_tiles` with `params.file` (also accepts
  `params.tiles`), its own action switch, standard confirmation policy.
- Agent prompt explains providers, cells, minimum sizes, full-file replacement
  and preservation of user items. Phone facts list installed widget providers.
- Result reports additions, moves/resizes, removals, refusals and setup needs.
- A real conversational agent has NOT yet designed and applied a layout.

## In progress at handoff

Five files had uncommitted approval-card edits after `6c17316b35`:

- `privilege/ApplyTilesTask.kt`: dry-run branch returns a plan without writes.
- `privilege/ActionApplier.kt`: `previewTiles(text)` obtains the live plan.
- `ui/setup/Assistant.kt`: obtains detail lines while building proposals.
- `ui/setup/SetupChatViewModel.kt`: optional proposal detail lines.
- `ui/setup/DogoSetupScreen.kt`: renders those lines on ready cards.

That intermediate code built successfully, but Spotless failed because a KDoc
for `writeTiles` was left immediately before the KDoc for `previewTiles` at
`ActionApplier.kt:274`. Fix the misplaced comment, then re-run all gates. The
preview additions had NOT been unit-tested or exercised on the phone yet.

Do not treat an intermediate successful build as a finished approval preview.
Saved conversations currently do not persist these new detail lines. A preview
is a snapshot; Apply must still re-plan from live state.

## Approval-preview checkpoint after resuming

The misplaced KDoc is fixed. Preview now runs when a ready tile card is shown,
including a restored conversation, with an explicit Refresh control. There is no
persisted preview snapshot to go stale. A summary always reports the removal
count; the bounded planner details retain their explicit truncation marker.
Cancellation propagates from preview work.

An expandable requested-only grid schematic uses `TileGeometry`, with numbered
boxes, labels and an accessibility description. This is deliberately NOT a live
widget rendering or an accepted-plan drawing; the refusal list remains the
source of truth about what cannot be placed. Widget styling remains unimplemented.

Verification so far:

- Spotless apply/check passed; full suite: 499 tests, zero failures/errors,
  two skipped. Debug APK built and installed.
- On-device dry-run reported three removals and a too-small Now Playing refusal.
  Before/after launcher database dump, saved tile state and system widget dump
  were identical. The preview did not allocate widget IDs or apply changes.
- The real ready card showed the summary, all three removals, refusal reason,
  refresh, requested grid and Apply/Dismiss. Expanded diagram and card were
  checked via screenshots; scrolling exposed the whole card.
- Dismissed the destructive test card without pressing Apply. Database and tile
  state remained unchanged. Accessibility flag was still zero.
- Evidence: `/home/marc/.hermes/cache/scratch/tiles-preview-verification/`.
- Independent standards/spec reviews completed. Fixed their findings: file and
  provider reads now run on IO; grid-changing previews explicitly mark counts
  and refusals provisional; additional parser issues have an omitted count;
  the diagram explicitly says live style settings have no effect yet.
- Re-ran formatting/check, all 499 tests (zero failures/errors, two skipped),
  build and install after those fixes. On-device 4x6 -> 5x6 dry-run showed the
  provisional warning; five parser issues showed three details plus two omitted.
  Database, saved tile state and accessibility flag were unchanged.
- This is still a partial approval-preview slice: it does not draw the accepted
  live plan, and stale-state confirmation semantics still need review before a
  change materially different from the displayed snapshot may be applied.

## Remaining work, in order

### 1. Finish the approval preview (next checkpoint)

- Repair lint and preserve coroutine cancellation in the preview path.
- Show bounded add/move/resize/remove/grid-change/refusal detail lines.
- Ensure removals are not hidden by a line cap; make truncation explicit.
- Draw a small labelled grid preview from `TileGeometry`. Clearly distinguish
  the requested layout from what the live planner can actually place; a refused
  widget must not look like an approved placement.
- Show preview limitations: provider content is not rendered, styling is not
  implemented, state can change before Apply.
- Restored ready proposals must regenerate their preview or state clearly that
  it is unavailable, not silently show stale or absent details.
- Verify dry-run does not allocate widget IDs, change the launcher database,
  update tile state or change the grid.
- Exercise an actual approval card, including a refusal/removal, on the Pixel.

### 2. User-facing Undo and reliable file state

- Add a one-tap Undo control that applies the previous file through the same
  checks. Undo should be an explicit action, not a hidden debug-only function.
- Handle an initially empty arrangement and widgets requiring setup honestly.
- Review atomic state saving, partial apply failures, queued task timeouts,
  provider resolution, managed IDs and grid-reload races before expanding use.
- Do not claim an all-or-nothing transaction until it exists and is tested.

### 3. Tile appearance spike

- `general` style keys parse today but do NOT affect live widget appearance.
- Try existing `widgetPaddingFactor` and `roundedWidgets` first. Determine
  whether these global preferences would unexpectedly change non-tile widgets.
- Prefer tile-only styling if global options affect the user's own widgets.
- Add a frame only if a small, maintainable upstream host-view hook is justified;
  mark modified upstream files with the Dogo attribution required by NOTICE.
- Do not promise blur/frame/rounding until rendered device evidence exists.
- Check equal gaps, side margins, content-fit heights and wallpaper legibility.
  The current test layout is functional but visually uneven.

### 4. Local look check (no model, no upload)

- Capture Dogo's own home-page views, not other apps' screens; no general
  screen-capture or accessibility permission should be needed.
- Inspect missing/unbound/setup-pending widgets and tile bounds locally.
- Blank rendering means "cannot tell", not "broken" (surface widgets and
  intentionally empty widgets are possible).
- Provide a visible image/result and account for a page not currently visible.

### 5. Opt-in vision advice

- Add "Check how it looks" with a destination disclosure before sending.
- Calendar/message widgets contain untrusted and potentially private content.
  Use the enforced tools-off route, not the ordinary tool-enabled Hermes route.
- Advice must not run actions. A proposed fix is a fresh approval card.
- Test destination, route refusal, cancellation, no-upload path and image support.
- Do not modify the remote Hermes server/profile without permission.

### 6. Real-agent end-to-end run and edge cases

- Ask the configured agent for a layout using the installed-provider facts.
- Inspect the proposal, apply it, read the result, screenshot and inspect visually.
- Test page 1 reserved date row without disturbing existing user items.
- Test changed grid, occupied page, duplicate providers, missing providers,
  no-op reapply, stale approval and restart/reload.
- Recheck that accessibility stays OFF; never accept a root prompt for the user.

## Other leftovers to verify (not completed by this plan)

- Safe-route card and a real notification through `dogosafe` on the phone.
- Whether dogosafe background review/memory writing is disabled: recover the
  earlier verification or read the approved server scope; do not guess.
- The requested bundled neon wallpaper and wordmark visibility: inspect current
  assets/history and device, rather than assuming an earlier request shipped.

## Main code and verification commands

Repo: `/home/marc/Projects/Dogo/dogo`, branch `dogo/foundation`.

Relevant files:

- `dogo/src/app/dogo/launcher/tiles/{TileConfig,TileParser,TileWriter,TilePlan,TileStore,TilesDebugReceiver}.kt`
- `dogo/src/app/dogo/launcher/privilege/{ApplyTilesTask,ActionApplier}.kt`
- `dogo/src/app/dogo/launcher/agent/{ActionExecutor,AgentOptions}.kt`
- `dogo/src/app/dogo/launcher/ui/setup/{Assistant,SetupChatViewModel,DogoSetupScreen,SetupChatAgent,PhoneFacts,Conversations}.kt`
- Tests: `dogo/tests/app/dogo/launcher/tiles/` and
  `dogo/tests/app/dogo/launcher/agent/WriteTilesActionTest.kt`.

Run in order, not concurrently with source edits:

```sh
./tools/dev/dev.sh shell spotlessApply -q
./tools/dev/dev.sh test -q
./tools/dev/dev.sh build -q
```

Count failures/errors/skips from
`build/test-results/testLawnWithQuickstepDogoDebugUnitTest/TEST-*.xml`.
Build logs are under `/home/marc/.hermes/cache/scratch/b_*.log`.
Install the debug APK with `adb install -r -t`.

The adb-only `TilesDebugReceiver` requires DUMP and checks `BuildConfig.DEBUG`.
It accepts `--es file` (pipes mean newlines) or `--ez undo true`. Production
agent changes go through `ActionApplier` and the normal approval policy.

## Release discipline

- Commit documentation separately from implementation, using
  `Dogo Dev <dev@dogo.ca>`.
- Before every commit scan staged additions for actual known key values without
  printing them, plus the configured ephemeral, project and safe-profile key prefixes.
- Keep all credentials out of source, plans, logs and screenshots.
- For every checkpoint report separately: formatted, tested, built, device
  exercised, committed and pushed. Explicitly list anything still unverified.
