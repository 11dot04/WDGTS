# HUD

A text-first home-screen widget in the style of the Nothing clock face: monospace caps, outlined and filled pills,
speech-bubble weather, finger-counting countdowns. One bitmap, four lines (five on a tall widget), all of it
drawn in code from a small vocabulary of tokens.

```
MON [9][NOV] 12:27▮ I◂27°(C)        line 1: the date (a greeting can take it over)
🗣 GOVARDHAN PUJA [→]                lines 2-4: whatever is most relevant right now
ALL DAY [174D]
```

## How it fits together

```
Smartspacer SDK ──► HudSmartspacerSource ──► HudSmartspacerMapper ─┐  top target + weather
local signals   ──► HudSources ────────────────────────────────────┤  blocks
                                                                   ▼
                                                            HudComposer   ranks, places, decides line 1
                                                                   ▼
                                                              HudContent
                                                                   ▼
                      HudWidget ──► HudRenderer (FlowLayout, HudPlan, Canvas) ──► bitmap in the widget
                          └─► tap zones (one per line), sized by HudGeometry
```

| package / file | role |
|---|---|
| `engine/HudTokens.kt` | the vocabulary: `Text`, `NumeralPill`, `MonthPill`, `OutlinePill`, `Chip`, `DotRow`, `Bubble`, `GlyphToken`, `Icon`, `Timer`, `Cursor`, `Space`; `Block` (a group of lines); `HudGlyph` |
| `engine/HudMetrics.kt` | every measurement, in the SVG set's own design units (pill height 10.368) |
| `engine/HudLayout.kt` | `FlowLayout` (wrap, ellipsize), `HudPlan` (which block gets which line), `LinePacker`. Pure Kotlin, unit tested |
| `engine/HudSizing.kt` | widget pixels -> unit size and line count (4, or 5 when tall enough) |
| `engine/HudRules.kt` | temperature-bar buckets, finger counting, weather vocabulary, countdown and greeting parsing. Pure |
| `engine/HudHeader.kt` | the date line and the weather block / temperature chip |
| `engine/HudRenderer.kt` | draws tokens to a Canvas: pills with knocked-out text, the bubble, ring, bracket, cursor, dots |
| `engine/SvgGlyph.kt`, `GlyphRegistry.kt` | read glyph SVGs straight from `assets/glyphs/` (paths and stroked rects) |
| `engine/smartspacer/` | everything that needs the Smartspacer SDK; delete the folder to build without it |
| `HudComposer.kt` | relevance classes, headline takeover, weather placement, music icon |
| `HudSources.kt` | missed calls, look-away, tithi, streaks, charging, notification chips, Open-Meteo weather |
| `HudWidget.kt`, `HudGeometry.kt`, `HudTapActivity.kt` | the widget, tap zones, the invisible trampoline for Smartspacer taps |
| `HudHooks.kt`, `HudSignals.kt` | notification listener hooks and signal state (older code, unchanged apart from Smartspacer start/stop) |
| `../widgets/GlyphMask.kt` | app icon -> clean white silhouette (monochrome layer first), shared with the streaks widget |

## What it shows, and when

Nothing is pinned. Each block has a class, and the free lines go to the most relevant ones:

| class | blocks |
|---|---|
| 0 urgent | missed calls |
| 1 contextual | Smartspacer's topmost target, the look-away nudge (after 20 min of screen time), streaks left (22:00 to midnight), weather when it is notable (rain, snow, thunder, hail ...) |
| 2 ambient | tithi (first ten minutes after the first unlock of the day), charging, notification chips (messaging apps, with their own monochrome icons), ordinary weather |

- Ambient blocks never use the fifth line, so a tall widget shows five lines only when something really needs them.
- **Weather** gets its own line when there is room. When the lines are wanted by other blocks it moves up into line 1 as a temperature chip (`I◂27°(C)`), if that still fits.
- **Greeting.** If the top target is a greeting ("Good morning, ..."), it takes over line 1 until it is gone.
- **One target.** Only Smartspacer's topmost usable target is shown. Widget targets (Smartspacer's 4x1 pager widgets arrive as a "Widgets not supported" text) are skipped; a widget cannot be nested in a widget.
- **Pairs.** A target and its subtitle or complication are laid out together: two lines when there is room, one folded line (`TITLE — SUBTITLE [→]`) when there is only one. Complications are outlined pills.
- **Times.** "in 7 min" or "at 09:50" within the hour becomes `STARTING ✌🖐 ⌊7MIN`: hands count the minutes (remainder first, then fives), the bracket carries the number.

## The look

- Font: Geist Mono (`assets/fonts`, any file whose name contains "geist" and "mono"), set at weight `WEIGHT` in `HudWidget`.
- One text size for body text, one for text inside pills; both measured from the font at runtime.
- Filled shapes (numeral pill, bubble) knock their text out so the wallpaper shows through; outlined pills use a 0.52-unit stroke.
- Weather uses the Nothing words only: SUNNY, CLOUDY, BREEZY, RAIN, HAIL, THUNDER, TORNADO, SNOW, FLOOD, HAZE, HURRICANE, STORM, SEVERE, plus CLEAR for a clear night. Open-Meteo and Smartspacer states are folded into that set (`HudRules.weatherLook`, `HudSmartspacerMapper.weatherLook`).
- Anchored to the top, white ink, transparent. `CARD = true` in `HudWidget` draws the palette surface behind it.
- Optional sun/moon glyph between the month and the time (Settings, off by default): 06:00 and 18:00, or sunrise and sunset.

## Glyphs

`assets/glyphs/hud_*.svg`, read at runtime (no XML conversion). Three sources, all on the same 10.368-unit grid:

- the Nothing-style set drawn for this project: weather, temperature bar (five positions), hands 1-5, F/C/K rings, speak, arrow pill, eye, calendar, moon disc, USB-C
- Google Material icons (Apache 2.0): call (handset), music note, and the globe in the streaks widget
- shapes built from primitives (USB-C, eye, calendar, moon disc, dot rows)

App icons (messaging chips, the music player) come from the app's adaptive-icon monochrome layer, falling back to its foreground as a silhouette, trimmed and optically sized by `GlyphMask`.

## Smartspacer

- Needs `com.kieronquinn.smartspacer:sdk-client:1.2` (Kotlin 2.4 metadata, so the build's Kotlin must be 2.4.0).
- Grant access once: Settings > Smartspacer > "Grant Smartspacer access". Without it the HUD still works from local data.
- The session starts with the notification listener or the widget, reports screen on/off, and keeps its last good target list through short gaps (two minutes) so the HUD does not flicker back to local data.
- Settings > "Copy Smartspacer targets" puts a dump of every live target on the clipboard (component, feature type, texts, icons, tint flags, actions). Use it to see exactly what a plugin sends before changing the mapping.
- Smartspacer trims complication text to 12 characters before we see it.

## Taps

A widget cannot say where it was touched, so each line is a tap zone (`zone_l1` to `zone_l5`, sized in pixels by `HudGeometry` to match the bitmap). Local blocks fire their own intent; Smartspacer lines open `HudTapActivity`, which finds the target again, fires its action and reports the interaction.

## Widget size

`res/xml/hud_widget_info.xml`: `targetCellWidth="5"` (as wide as the grid allows), `targetCellHeight="2"` for four lines; drag it taller for the fifth. `minWidth`/`minResizeWidth` of `250dp` or more, no `maxResizeWidth`.

## Signing

Every build is signed with `app/signing/wdgts-debug.p12` (alias `wdgts`, password `wdgtsdebug`), and `versionCode` follows the GitHub run number, so each new APK installs over the last one instead of needing an uninstall. The key is committed on purpose. That also means anyone who can read the repo can sign an APK that installs over yours; for a private sideloaded app that is acceptable. If the repo is public, store a private keystore as a CI secret and set `WDGTS_STORE_PASSWORD` (the Gradle file reads that property or environment variable). The first install after switching keys needs an uninstall of the old build.

## Tuning

`engine/HudMetrics.kt`: `BODY_CAP` (free text size against pill text), `LINE_PITCH`, `PAD_H`/`PAD_V`, `CURSOR_W`, bubble padding, `WIDTH_UNITS` (about 26 characters per line). `HudWidget`: `WEIGHT`, `CARD`.

## Known limits

- Pixel match to the Nothing reference is by eye, not by measurement; the bracket timer is approximated from a screenshot.
- The relevance classes are fixed rules, not learned.
- Plugin icons are not used (they rarely suit the style); colour images (team logos, QR tiles) are skipped.
- `HudGeometry` reads the launcher's reported size; the tap zones can be a few pixels off the lines if the launcher rounds.

## Credits

Geist Mono (SIL OFL), Google Material icons (Apache 2.0), Smartspacer SDK by Kieron Quinn (Apache 2.0).
