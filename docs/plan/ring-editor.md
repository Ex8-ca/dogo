# Manual Dogo Ring editor

## Scope

Add Edit ring under Dogo Options > Look and feel > Dogo Ring. Start from the
current custom groups, or the same automatic grouping used by the ring. Show
editable headings with selected apps below each; plus opens a searchable app
picker, minus removes a selected app. Also allow adding/removing groups.

Use existing Material 3 typography, colours and 48dp icon-button hit areas.
Keep the built-in Dogo commands fixed and explain that they remain available.
Keep one-app groups' existing ring behaviour (the slice opens that app directly).

## State and safety

- Work on a local draft. Save persists; Cancel makes no change.
- Preserve draft through configuration changes. Ask before dismissing dirty work.
- Enumerate apps off Main. Respect launcher-hidden apps and current-user apps;
  show unavailable selected packages so they can be removed rather than erased.
- Prevent duplicate apps within a group; allow the same app in different groups.
- Blank headings fail validation on Save, without losing input. Cap names at the
  existing RingConfig.MAX_NAME. Empty groups are kept in config but do not make
  visible ring slices (existing RingBuilder behaviour).
- Detect externally changed groups before saving, retaining the draft and
  offering reload instead of silently overwriting an agent's changes.
- Preserve enabled/style/hold timing when saving groups.

## Acceptance

Format, full tests and APK build; device exercise: automatic headings loaded,
rename, plus/search/add, minus/remove, Cancel unchanged, Save persisted,
reopen/restart persisted, actual ring reflects edits. Visually check editor and
picker screenshots. Restore the original phone ring configuration after tests.
Commit implementation separately from this plan. Do not replace the published
alpha APK under its existing immutable release; a later APK needs a new version.
