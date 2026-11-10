# HUD round 2 — what to apply (don't commit this file)

Unzip over the repo root. Commit on a branch (hud-redo) until it builds.

## Delete from the repo
- `app/src/main/java/com/wdgts/hud/HudItems.kt` (replaced by HudSources.kt + HudComposer.kt)
- `app/src/main/java/com/wdgts/hud/HudCalendar.kt` (unused now; keep it only if something else of yours calls it)

## Overwritten / new
- Gradle: `app/build.gradle.kts` now has the signing config and CI versionCode. **Add `app/signing/wdgts-debug.p12` with `git add -f` if your .gitignore skips keystores.**
- `AndroidManifest.xml` (Smartspacer query, date/time broadcasts, HudTapActivity), `SettingsActivity.kt` (Smartspacer access, targets dump, day/night glyph).
- `res/layout/widget_hud.xml`: five tap zones.
- Whole `hud/` folder, `widgets/GlyphMask.kt`, `widgets/StreaksWidget.kt`, `assets/glyphs/` (35 SVGs), tests, `hud/README.md`.

## One hand edit: res/xml/hud_widget_info.xml (width)
    android:targetCellWidth="5"
    android:targetCellHeight="2"
    android:minWidth="300dp"
    android:minResizeWidth="250dp"
    android:minHeight="110dp"
    android:minResizeHeight="110dp"
    android:resizeMode="horizontal|vertical"
and make sure there is no maxResizeWidth. Remove the widget and add it again so the launcher uses the new size.

## Install note
The new signing key differs from the one your CI used before: uninstall the old build once. After that, updates install over each other.

## Expect on first build
Names I cannot see in your code: `ImageWidget` (bind/paint/refreshAll), `Prefs.*`, `HudSignals.*`, `MusicState.*`, `STREAKS`/`Streaks`, `Panchang`/`Astro`. Paste the errors and I will fix them.
