# Changelog

## 0.4.5

**Improvements**
- Fast terrain: terrain shaders now compile with relaxed math, vertex and fragment together, for vanilla terrain and for Sodium 0.9.2, 0.9.3-beta.1 and 0.9.3-beta.5. In a same-session A/B on an M2 at 3420x2148 with Sodium on Metal 3, GPU time per frame dropped from 11.1 to 5.6 ms (157 to 294 FPS). Results depend on the Mac, the world and the settings.
- It only applies while the terrain shaders are the unmodified stock ones. If a resource pack or another mod changes them, or the Sodium version is not one of the above, terrain keeps the exact math. Removing the pack and reloading resources turns it back on without a restart.
- It is off by default on macOS 14 and older, which lack the relaxed math mode it needs. `-Dpuremetal.fastTerrainMath=off` turns it off on any macOS.
- The F3 screen and the top of Pure Metal's settings (Video Settings and the Sodium page) show `Fast terrain: On` or `Fast terrain: Off (reason)`.

## 0.4.4.1

- Improved recovery from Metal 4 presentation stutter after switching windows.
- Avoided a long wait when Metal 4 presentation callbacks do not arrive at shutdown.
- Removed the in-game GitHub star reminder. The dismissible card in Sodium settings remains.
- Updated the mod icon.

## 0.4.4

**Fixes**
- Recover Metal presentation pacing after a shown frame confirms older presentation callbacks were missed.

**Improvements**
- Distant Horizons 3.3.x versions with the same checked classes as 3.3.4 can use the Metal path, with an untested-version warning. DH 3.3.3 was checked in Loom; other 3.3.x releases beyond 3.3.3 and 3.3.4 have not been tested.
- Sodium Terrain LOD hooks are skipped if their checked class shapes do not match, helping avoid failures when Sodium changes.
- Added a dismissible GitHub star card to the Pure Metal page in Sodium and a one-time in-game reminder during the first hour of play.

## 0.4.3

Distant Horizons support is now on by default, and a set of small fixes and doc updates ship with it.

**Improvements**
- Distant Horizons support is on by default with DH 3.3.4 and no Iris. The `-Dpuremetal.experimentalDistantHorizons=true` JVM argument is no longer needed. Add `-Dpuremetal.experimentalDistantHorizons=false` to turn it off, in any letter case. If DH stays installed with the support off, or with another DH version, the game runs without Metal, so remove DH to keep Metal without it. It is tested on DH 3.3.4 only. The F3 line reads `DH: on` when it is active.

**Fixes**
- Shutdown GPU waits are now limited to 5 seconds each, so a stuck GPU is much less likely to hang quitting. If work does not finish, Pure Metal logs it and keeps the state alive instead of freeing it under pending GPU work.
- Invalid buffer writes, texture buffer views, pipeline vertex layouts and inline constants are rejected with an error instead of writing out of bounds.
- The world-generation FPS cap, Smooth Frame Pacing, Terrain LOD and Render Scale entries on the Pure Metal screen now show the current value when the screen opens, including after a change in Sodium's settings.
- The whole-texture fallback for a region clear logs its warning once instead of on every call.

**Docs**
- The benchmark table in the README is fullscreen. `docs/BENCHMARKING.md` lists `BENCH_PREWARM` and `BENCH_COOLDOWN`.
- `docs/DESIGN.md` no longer contains owner-only workflow rules.

## 0.4.2

Adds Auto Render Scale, a dedicated settings screen, more Terrain LOD simplification and an experimental Smooth Frame Pacing option. The new options are off by default.

**New**
- Auto Render Scale: a new option under Render Scale, off by default. It lowers Render Scale when your GPU can't keep up with your FPS target (Max FPS, or your display's refresh rate if unlimited) and raises it again when it can, using 100, 85, 70 and 60%. Render Scale is greyed out while it is on, and the F3 line shows the current step. At very high FPS targets it may stay at a lower step.
- Smooth Frame Pacing (Experimental): a new option, off by default, that only works with VSync on. Each frame is shown on the next display refresh at an even rhythm, and a frame that is already late is never held back. In a test at 60 Hz, it made no measurable difference to hitches or latency.
- Video Settings now has one button that opens a Pure Metal settings screen, and Pure Metal's page in Sodium's settings is a single page with named sections.

**Improvements**
- Terrain LOD now also simplifies the outer band: groups of plain leaf blocks merge into one cube, small plants such as grass, ferns and flowers are not drawn far away, and tops of calm water merge. Glass is unchanged.
- Fewer memory allocations while chunks build now applies to every Sodium from 0.9.2 on, as long as its code matches what Pure Metal expects. If it doesn't match, Pure Metal leaves Sodium's code alone.

**Compatibility**
- Far water merging supports Sodium 0.9.2 and 0.9.3. With other versions, water tops simply stay unmerged.
- Tested on an Apple M2. Other Macs should work but were not tested for this release.
- Iris and other shader mods are still not supported.

Everything from 0.4.1 is included.

## 0.4.1

Adds an optional Faster World Generation setting and experimental Distant Horizons support, plus rendering fixes and less work while building chunks. The new options are off by default.

**New**
- Faster World Generation: a new option in Video Settings and Sodium's settings, off by default. In singleplayer, while chunks near you are still missing, FPS is limited to twice your refresh rate (at most 259) so the CPU time saved goes to building chunks. Normal FPS returns once they have loaded, so walking around is barely affected. Over the same flight into new terrain it finished about 38% more chunks, and about 59% more with C2ME.
- Distant Horizons (Experimental): Pure Metal can now run Distant Horizons 3.3.4 on Metal. Add `-Dpuremetal.experimentalDistantHorizons=true` to your launcher's JVM arguments and keep DH's rendering engine on AUTO. Distant terrain may take time to generate. Not supported with Iris.
- The Pure Metal line on the F3 screen shows whether the Distant Horizons path is on, or why it is off (version, modified build, engine setting, unreadable config, Iris).
- Clear log messages when Distant Horizons can't use the experimental path, for example "Distant Horizons 3.3.5 found, the experimental path needs 3.3.4", or which rendering engine setting to change.

**Improvements**
- Terrain LOD does less scanning and meshing work.
- Fewer memory allocations while Sodium 0.9.2 or 0.9.3-beta.1 builds chunks with blocks and fluids.

**Fixes**
- Clears meant for part of the screen now clear only that area instead of the whole image. If an area can't be cleared on its own, the whole image is cleared as before instead of failing.
- Depth clear values outside the valid range are now clamped instead of clearing nothing.

**Compatibility**
- Tested with Sodium 0.9.2 and 0.9.3-beta.1, and with C2ME.
- Distant Horizons 3.3.4 was tested in game on one setup; other versions keep the normal fallback.
- Tested on an Apple M2. Other Macs should work but were not tested for this release.
- Iris and other shader mods are still not supported.

Everything from 0.4.0 is included.

## 0.4.0

Adds an experimental Metal 4 mode, a Pure Metal line on the F3 screen, and safer startup. Nothing changes unless you turn Metal 4 on.

**New**
- Metal 4 (Experimental): a new option in Video Settings and Sodium's settings that runs Pure Metal on Apple's newer Metal 4 API. It needs macOS 26 and is off by default. Metal 3 is still the faster and more stable choice, so only turn it on if you want to try it. Changing it takes effect after a restart.
- On older macOS versions the Metal 4 option is greyed out, and its tooltip explains why.
- The F3 screen now shows a Pure Metal line: which renderer is running (Metal 3 or Metal 4), your GPU, and the upscaler and render scale in use. Handy for bug reports. You can hide it in the F3 settings like any other line.

**Fixes**
- If Minecraft switched your Graphics API to OpenGL after a crash that had nothing to do with Metal, Pure Metal now switches it back to Default.
- More reliable startup crash recovery: Pure Metal only skips Metal after a start that really failed, and running two game instances at once no longer confuses this check.
- The log now says why Metal didn't start, for example when Graphics API isn't set to Default.
- The note under Pure Metal's title in Sodium's settings is easier to read on bright backgrounds.

**Compatibility**
- Tested with and without Sodium 0.9.2.
- Tested on an Apple M2. Other Macs should work but were not tested for this release.
- Iris and other shader mods are still not supported.

Everything from 0.3.2 is included.
