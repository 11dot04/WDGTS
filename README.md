# WDGTS HUD engine (Nothing look) — first drop

Package `app.wdgts.hud` is a placeholder; rename to your own.
**Not compiled or run yet** (no Kotlin toolchain where this was written). The layout/packing/rules
logic was cross-checked with a line-by-line Python port; the Canvas and Smartspacer-mapping code is untested.

## Dependency
    implementation("com.kieronquinn.smartspacer:sdk-client:1.2")   // pulls sdk-core 1.1.2 (models)
    implementation("androidx.core:core-ktx:<yours>")
    testImplementation("junit:junit:4.13.2")
Add `res/font/geist_mono.ttf` (variable or regular; OFL). `PathParser` comes from androidx.core.

## Files
| file | what |
|---|---|
| HudTokens.kt | Token types (Text, NumeralPill, MonthPill, Bubble, GlyphToken, Timer, Cursor, Space), `Block`, `Glyph` enum with resource names |
| HudMetrics.kt | every measurement, in SVG design units (`Hud.*`) |
| HudLayout.kt | `FlowLayout` (wrap/ellipsize) and `LinePacker` (fills the 3 dynamic lines); pure Kotlin |
| HudRules.kt | temp-bar buckets, finger counting, 32 weather states -> glyph + word, countdown parsing; pure |
| HudMapper.kt | system clock + Smartspacer targets -> tokens (header, weather block, per-template blocks) |
| SvgGlyph.kt / GlyphRegistry.kt | parse and cache the glyph SVGs from assets, sizes in design units |
| HudRenderer.kt | draws to a Bitmap, returns tap hit-rects |
| HudLogicTest.kt | JVM tests for the pure parts |

## Glyphs: direct SVG assets, no XML conversion
`assets/glyphs/` holds 29 ready SVGs (the 14 weather glyphs cut from Specs, plus hud_speak, hud_followup,
hud_hand_1..5, hud_temp_100_80/70_60/50_30/20_10/9_0 split from the Temp Bar sprite, hud_unit_f/c/k).
`SvgGlyph` parses them at runtime (path data plus the stroked ring rects), so nothing is converted.
Supported SVG subset: `<path d>`, `<rect>` with rx/stroke, fill-rule, width/height on `<svg>`.

Packaging: Gradle only packs `src/main/assets` by default. To use this module-root `assets/` folder add
    android { sourceSets { getByName("main") { assets.srcDirs("assets") } } }   // "../assets" if it sits above the module

Mapping from your files: Speak.svg -> hud_speak, Followup.svg -> hud_followup, 1..5.svg (markers) -> hud_hand_1..5,
Frame 2/3/4 -> hud_unit_f/c/k, Temp Bar (in °F) -> hud_temp_*, weather -> hud_wx_* (glyph-only, new crescent for night).
Date numerals, months and weather word bubbles are drawn in code in Geist Mono; the Days/Months/Specs sprites are reference only.

## Wiring (next drop adds the provider/receiver)
    val renderer = HudRenderer(ResourcesCompat.getFont(ctx, R.font.geist_mono)!!, GlyphRegistry(ctx))
    val content = HudMapper().build(ZonedDateTime.now(), DateFormat.is24HourFormat(ctx), targets, redactSensitive = locked)
    val frame = renderer.render(widthPx, heightPx, content, HudColors(Color.WHITE))
    views.setImageViewBitmap(R.id.hud, frame.bitmap)   // frame.hits -> trampoline click zones

## Things measured from the reference, tune in HudMetrics.kt
BODY_CAP (free text is ~8% larger than pill text), LINE_PITCH, PAD_*, BUBBLE_PAD_L/R, CURSOR_*,
TIMER_LEAD. The Timer (bracket + underline) shape is approximated from a screenshot crop.
