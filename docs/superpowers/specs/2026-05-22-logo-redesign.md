# Literal Memo Logo Redesign

**Date:** 2026-05-22
**Status:** Approved

## Overview

Redesign the Literal Memo Android app launcher icon from the current "LM" monogram to a symbolic "Thinker" avatar — a rounded head silhouette with three dots representing thought, insight, and consciousness.

## Design Concept

**Symbol:** "Thinker" — a human head profile rendered as a clean rounded shape, with three interior dots:
- One dot at the top-center (forehead/crown area) — representing **insight, the "third eye," inspiration**
- Two dots below at eye level — representing **perception, focus, seeing clearly**

Together the three dots evoke a face engaged in thought: aware, perceptive, illuminated.

## Design Specifications

### Foreground Icon (ic_launcher_foreground.xml)

- **Viewport:** 100×100 (within a 108dp adaptive icon canvas)
- **Scale/position:** Centered, filling approximately 60% of the adaptive icon safe zone
- **Head shape:** Smooth pill-like curve (wider at top, slight taper toward chin, flat base)
- **Dot (crown):** cx=50, cy=34, r=4 — solid white
- **Dot (left eye):** cx=36, cy=40, r=3 — solid white
- **Dot (right eye):** cx=64, cy=40, r=3 — solid white
- **Stroke:** 4dp for the head outline, white (#FFFFFF)
- **Fill:** None (transparent) — the background provides the fill

### Background Layer

- **Color:** `#000000` (black) — unchanged from current design

### Monochrome Icon (ic_launcher_monochrome.xml)

- Same geometry as foreground, but uses `#000000` fill/stroke (system tints it dynamically)
- Used for Android 13+ themed icons

### Play Store Icon (ic_launcher-playstore.png)

- 512×512 PNG render of the same design on black background

### SVG Path Reference (for implementation)

```
Head outline:
M50 14 C35 14 22 25 22 40 c0 10 5 18 10 23 v12 h36 V63 c5-5 10-13 10-23 c0-15-13-26-28-26 Z

Dots:
circle at (50, 34) r=4  — crown/third eye
circle at (36, 40) r=3  — left eye
circle at (64, 40) r=3  — right eye
```

## Color Scheme

- **Background:** `#000000` (black) — constant across all API levels
- **Foreground elements (API < 31):** `#FFFFFF` (white)
- **Monochrome (API 31+):** `#000000` (black, system-tinted)
- **App theme colors unaffected** (still uses Material You dynamic colors on Android 12+)

## Files to Modify

| File | Change |
|------|--------|
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Replace "LM" path with Thinker head path |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | Replace "A" path with Thinker head path (black fill) |
| `app/src/main/res/values/colors.xml` | `ic_launcher_background` stays `#000000` (no change) |
| `app/src/main/ic_launcher-playstore.png` | Regenerate with new design |

## Compatibility

- **Minimum SDK 26** — VectorDrawable is natively supported
- **All densities** — VectorDrawable scales automatically; no raster regeneration needed
- **Adaptive icons (API 26+)** — Uses existing adaptive icon XML structure
- **Monochrome themed icons (API 31+)** — Separate monochrome layer provided
- **Pre-API 26 fallbacks** — WebP raster files in mipmap-*dpi directories remain, need regeneration

## Future Considerations

- App uses Material You dynamic colors for the theme, but the launcher icon remains black + white independent of the theme
- If the app ever needs a brand color, it can be introduced via the background layer without changing the foreground geometry
