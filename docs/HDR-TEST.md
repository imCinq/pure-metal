# HDR Highlights test checklist

HDR Highlights (experimental) lets the brightest parts of the picture go brighter than normal white on a screen that can do it. This is how to check it works.

## Before you start

1. Lower the screen brightness to about 50 to 70 percent. The screen only has spare brightness (headroom) below its maximum. At full brightness there is no headroom and nothing will look different.
2. Turn off any low power mode and night shift or true tone if you can, so the colors are easy to judge.

## Launch

```
./gradlew runClient -PhdrHighlights -PhdrCompare
```

- `-PhdrHighlights` turns the feature on for this run. `-PhdrHighlights=false` forces it off, whatever the saved setting is.
- `-PhdrCompare` is optional. It splits the screen: the left half gets the boost, the right half is the normal picture, with a thin magenta line between them.

The layer type is chosen at startup, so changing the setting in the menu needs a restart.

## Read the F3 line

Press F3. With HDR Highlights on, there is a line like this:

```
HDR: headroom 1.62 (max 2.00), boost 1.62, colorspace ok, compare on
```

- headroom: how much brighter than white the screen can go right now. It changes with the brightness slider. 1.00 means none.
- max: the most the screen could ever offer. About 2.00 is expected on the built-in screen of an M2 MacBook Air.
- boost: how far the highlights are actually pushed. It is 1.00 whenever a menu or inventory is open.
- colorspace: `ok` means the HDR layer is active. `untagged` means it is not (no boost is applied).
- compare: `on` when started with `-PhdrCompare`.

If headroom stays at 1.00, lower the brightness more.

## What to look at

These should glow brighter than the rest of the picture:

- the sun at noon (look near it, the sky around it)
- lava
- fire and torches
- glowstone and other lit blocks

These must NOT glow:

- a green field or grass
- orange wool (it is bright and saturated, but it is not a light)
- blue and purple blocks

Known limitation: white chat text and the crosshair may glow too, because the boost works on the finished picture and cannot tell them from the world. Menus and inventories are never boosted.

## Compare Off and On

Two ways:

1. Same run, split screen: use `-PhdrCompare`, stand with the sun or a lava lake across the middle of the screen. The left of the magenta line is boosted, the right is not. Turn your head to move bright things across the line.
2. Two runs: launch once with `-PhdrHighlights=false` (Off, forced even if the saved setting is on) and once with `-PhdrHighlights` (On), same world and same spot, and compare.

If the sun looks the same on both sides of the line, check the F3 line: headroom must be above 1.00 and colorspace must say `ok`.
