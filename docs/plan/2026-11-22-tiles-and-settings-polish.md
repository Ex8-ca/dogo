# Plan: settings leftovers, then tiles steps 2 and 3

Written 2026-11-22. Follows `2026-11-22-assistant-roadmap.md` (Phase 7) and the tile file
parser already in `dogo/src/app/dogo/launcher/tiles/`.

## What already exists, and what that decides

- **Placing a widget** is `AddWidgetTask` (`privilege/HomeTasks.kt`). It writes straight to the
  launcher database, takes a span, and finds room with `HomeGrid.findSpace`. It has no way to
  say "put it at column 2, row 3", which tiles need.
- **The launcher has one grid** (`InvariantDeviceProfile.numColumns x numRows`, 3 to 10 each,
  set by `HomeStyle`). The tile parser allows 1 to 8 columns and 1 to 16 rows. These must
  agree: tiles use the launcher's grid, so the parser range becomes 3 to 10 for both.
- **Page 1 may reserve its top row** for "At a glance" (`HomeGrid.findSpace` does this). A tile
  in row 0 of page 1 is refused while that is on.
- **Widgets already clip to a rounded outline** (`BaseLauncherAppWidgetHostView`), and
  Lawnchair already has `roundedWidgets` and `widgetPaddingFactor`. Gaps and rounding can come
  from these with no change to upstream code. A coloured frame cannot; that is the one open
  question (see Step 2, look).
- **Approval cards, per-action switches and the tools-off gate** exist
  (`ActionExecutor`, `AgentOptions.ActionType`, `ToolFreeAccess`).

## A. Settings leftovers (about half a day)

Seen on the phone after the regrouping, none changes behaviour:

1. **Shizuku says "Allow" when it is not installed.** Show "Get" and open its download page
   instead, so the button matches what it does.
2. **"On this phone" looks selectable when the phone can't run a model.** Dim the row and make
   it unselectable, keeping the "not available on this phone" note.
3. **Wallpaper colours and Omarchy themes order their parts differently** (toggle first, text
   first). Use one order in both: short description, then the controls.
4. **Right edges don't line up** (switch, check mark, Allow button, plugin card switch), and
   text buttons sit indented from the text above. Give trailing controls one fixed slot and
   remove the extra start padding on text buttons.

Done when: each fix is seen on the phone, tests still pass, one commit.

## B. Step 2: apply a tile file to the home screen (about 4 days)

**Decisions**

- **One grid.** `page = N, grid CxR, ...` must match the launcher grid. If it does not, the
  approval card says "Grid 5x7 becomes 4x8" and applying changes the grid too (through the
  existing `HomeStyle`). Parser range changes to 3 to 10 on both sides.
- **Page N is launcher page N minus 1.** A page that already holds icons or folders is
  refused: tiles never move the user's icons. The agent is told to use an empty page or add one.
- **Only real widgets for now.** Kinds are `widget:<package>/<class>`. Words like "clock" are
  the agent's job to turn into a real provider, from a list of installed widgets that the phone
  context will now include (names and provider ids, capped in length).
- **Dogo only removes what Dogo placed.** The ids of widgets it placed for tiles are kept in
  `noBackupFilesDir`. Re-applying a changed file moves, resizes, adds and removes only those.
  The user's own widgets are never touched.
- **Explicit cells.** A new `PlaceWidgetTask` takes `(page, x, y, spanX, spanY)`. A widget that
  cannot be that small or that large (`minSpan`, `maxSpan`) is reported, not silently resized.
- **Widgets that need setup stay visible.** If Android won't bind without a prompt, the widget
  is placed as "tap to set up" and the result says so. Giving Dogo the bind permission over adb
  (already in the roadmap) removes the prompts.
- **Undo.** The previous file is kept; Undo applies it through the same task.

**Look (gaps, rounding, frame)** starts with a short spike, because the options differ in cost:

1. Gaps and rounding from `widgetPaddingFactor` and `roundedWidgets`. No upstream edit. Try first.
2. A frame drawn behind each tiled widget in the workspace. Needs a small change to an upstream
   view, marked "Modified by The Dogo Authors, 2026 (see NOTICE)". Only if 1 looks flat.
3. If neither is clean, ship 1 only and say so. Better than a fragile hack.

**Build order**

1. Parser range to 3 to 10, glance rule, tests.
2. A pure "plan" function: current placed tiles + new file gives add, move, resize, remove,
   refuse, with reasons. Unit tested, no Android types.
3. `PlaceWidgetTask` and the apply step using that plan.
4. Look spike, then the look.
5. Phone check with real widgets: apply, change, undo; screenshots.

## C. Step 3: the agent writes tiles, with a preview and a look check (about 3 days)

- **Action `write_tiles`.** The agent sends the file text. Dogo parses it, shows any dropped
  lines with their reasons, and never applies the text directly. New action switch "Arrange
  tiles", on by default, covered by "Confirm every action".
- **Approval card.** Shows `TileWriter.describe` (what is added, moved, removed, which grid
  changes) and a small drawn preview of the grid from `TileGeometry`, labelled by widget name.
  Removing widgets always shows on the card.
- **Result goes back to the chat.** What was placed, what needs setup, what was refused and why.
- **Look check (the screenshot you asked for), in two layers.**
  1. *On the phone, automatic, no model:* Dogo draws its own home page to an image (its own
     views, no screen-capture permission). It flags a widget that is blank, smaller than its
     tile, or not bound. A widget that draws with a video surface may look blank, so a blank
     result is "can't tell", not "broken".
  2. *With a model, only when you tap "Check how it looks":* the image goes to a vision model.
     It contains other apps' content (calendar, messages), so it uses the same tools-off gate
     as notifications and the card says where it is going first. Its reply is advice, never an
     action.
- **Fix loop.** The agent may answer a look-check with a new tile file. That is a new
  `write_tiles` card, so every change is still approved.

**Build order:** action and parser wiring with tests; preview card; apply and result message;
home-page image and local checks; model check; phone run with the real agent.

## Risks

- Android may keep asking to bind widgets; the adb permission is the fix and is optional.
- A widget's minimum size can be bigger than the tile the agent chose. Reported, then the agent
  chooses again.
- A frame needs an upstream edit; kept out unless the spike shows it is worth it.
- The look check can't see inside widgets that use video surfaces.

## Done means

Each step: tests pass, built, run on the Pixel with real widgets, screenshots looked at,
committed and pushed, and what was not checked is written down.
