# HUD redo + Smartspacer — what to commit (don't commit this file)

Do this on a branch (e.g. `hud-redo`) so `.github` CI and `main` stay green until it builds.

## 1. Unzip over the repo root
Everything lands under `app/`. Four files **overwrite yours** (check the diff before committing):
- `app/src/main/java/com/wdgts/hud/HudWidget.kt` (rewritten)
- `app/src/main/java/com/wdgts/hud/HudItems.kt` (yours + blocks, ambient flag)
- `app/src/main/java/com/wdgts/hud/HudHooks.kt` (yours + 5 Smartspacer lines)
- `app/src/main/res/layout/widget_hud.xml` (4 tap zones, transparent root)

New files:
- `app/src/main/java/com/wdgts/hud/HudTapActivity.kt`
- `app/src/main/java/com/wdgts/hud/engine/` (12 files; `smartspacer/` holds the two files that need the SDK)
- `app/src/main/assets/glyphs/` (29 SVGs named `hud_*.svg`)
- `app/src/test/java/com/wdgts/hud/engine/HudLogicTest.kt` (optional; needs junit)

Untouched: `HudSignals.kt`, `HudCalendar.kt`, `Weather.kt`, everything in core/, widgets/, etc.
If `assets/glyphs/` already holds the designer's originals, leave them; the HUD only reads the `hud_*.svg` files.

## 2. Four hand edits (files I can't see)
**app/build.gradle.kts** — dependencies:
    implementation("com.kieronquinn.smartspacer:sdk-client:1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<your version>")
    implementation("androidx.core:core-ktx:<your version>")      // PathParser; you likely have it
    testImplementation("junit:junit:4.13.2")                     // only for the test file
minSdk must be 26 or higher.

**app/src/main/AndroidManifest.xml**
    <!-- top level, next to <application> -->
    <queries>
        <intent><action android:name="com.kieronquinn.app.smartspacer.MANAGER" /></intent>
    </queries>

    <!-- inside <application> -->
    <activity
        android:name=".hud.HudTapActivity"
        android:exported="false"
        android:excludeFromRecents="true"
        android:noHistory="true"
        android:taskAffinity=""
        android:theme="@android:style/Theme.Translucent.NoTitleBar" />
(If your manifest already has a `<queries>` block, add the `<intent>` inside it. If your package is not `com.wdgts`, the `.hud.HudTapActivity` name is relative to it.)

**app/src/main/res/xml/hud_widget_info.xml** — make it a 4x2: `targetCellWidth="4"`, add `targetCellHeight="2"`,
`minWidth`/`minResizeWidth="250dp"`, `minHeight`/`minResizeHeight="110dp"`, `resizeMode="horizontal|vertical"`. Leave the rest.

**SettingsActivity.kt** — one row to grant Smartspacer access (until then the HUD simply uses local data):
    // when the screen opens (in a coroutine): HudSmartspacerSource.checkAccess(this)   // NOT_INSTALLED / NEEDS_PERMISSION / GRANTED / UNKNOWN
    // button when NEEDS_PERMISSION:             HudSmartspacerSource.requestAccess(this)
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == HudSmartspacerSource.REQUEST_PERMISSION) HudSmartspacerSource.restart(this)
    }
(import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource)

## 3. Font
`assets/fonts/` is already there: make sure it holds the Geist Mono .ttf (any name containing "geist" and "mono"; variable is best).
If not found the HUD falls back to `Fonts.mono(ctx)` and will look off.

## 4. First build
Never compiled. Likely first errors: names I couldn't see in your code (`ImageWidget` members, `MicroStyle`/`mGlyph` signatures, `Prefs.weather`, `HudSignals.counts`) and a Smartspacer model field or two. Paste the errors and I'll fix them.
Tuning after it renders: `WEIGHT` and `CARD` in HudWidget.kt; sizes in engine/HudMetrics.kt.
