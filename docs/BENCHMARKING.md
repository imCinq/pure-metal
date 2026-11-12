# Benchmarking

`tools/bench.py` runs one dev client per graphics API, one at a time, and prints the average FPS and the 98th and 99.5th percentile frame times for each. `default` is Metal; the others are `vulkan` and `opengl`.

```
python3 -I tools/bench.py <tag> default opengl
```

## Setup

- Same toolchain as the build (see the README).
- A world named "New World" in `run/`.
- The game window must stay focused and in the world while it runs. Frames only count with no screen open.

## Options

Environment variables:

| Variable | Meaning |
|---|---|
| `BENCH_SECONDS`, `BENCH_WARMUP` | Measure and warm-up length in seconds (default 25 and 10) |
| `BENCH_REPEAT` | Runs per API; the median is printed |
| `BENCH_PREWARM` | Set to `1` for one unmeasured run with the first API before the measured runs, so autopilot chunks are generated and saved (off by default) |
| `BENCH_COOLDOWN` | Idle seconds before each run, so heat from earlier runs does not favour early ones (default 0) |
| `BENCH_WALK` | Fly forward during the run |
| `BENCH_COMMAND` | Command to run once on join, for example `gamemode creative` |
| `NO_SODIUM` | Run without Sodium |
| `BENCH_ARGS` | Extra Gradle arguments |

Your own vsync, fullscreen, pause-on-focus-loss and graphics API options in `run/options.txt` are restored afterwards.

Auto Render Scale stays inactive during a benchmark, so runs use the saved Render Scale.

## In-session A/B of shader math (`-PbenchAB`)

`-PbenchAB=<switch>` toggles one runtime switch every 2 s inside the same launch (A = off, B = on) and prints `BENCH-AB <switch> A(off)` / `B(on)` lines with FPS, frame times and `gpuMs/f`. One switch per launch. The `fastMath` switches compare strict (safe) shader math against relaxed math, vertex and fragment, like `-Dpuremetal.safeMath=false` does globally, but per pipeline group:

| Switch | Pipelines relaxed in B |
|---|---|
| `fastMathAll` | every group (should reproduce the global relaxed-math gain) |
| `fastMathTerrain` | solid and cutout terrain, `solid_block`, `cutout_block` |
| `fastMathTerrainTranslucent` | translucent terrain and the OIT terrain passes |
| `fastMathEntity` | entity, armor, item, glint, eyes, beacon beam, lightning, end portal and similar |
| `fastMathSky` | sky, end sky, sunrise/sunset, stars, celestial |
| `fastMathClouds` | clouds, flat clouds |
| `fastMathWeather` | rain and snow |
| `fastMathParticles` | opaque and translucent particles |
| `fastMathWater` | water mask |
| `fastMathPost` | OIT composite, depth blits, outline blits, post effect chains (guessed from shader names) |
| `fastMathGui` | gui, text, crosshair, vignette, screen effects |
| `fastMathLines` | lines, debug shapes, wireframe |
| `fastMathOther` | everything that matches no group (the table below names them) |

In an A/B launch the log prints one table `math group table` (group to pipeline names, `Other` entries with their shader names) when B first switches on. Only the selected groups build a second (relaxed) vertex and fragment function, so startup compiles those pipelines twice and keeps one extra pipeline state each in memory. This ignores the stock-terrain proof on purpose: it is diagnostic and can change pixels. Outside an A/B launch nothing changes.

### Shipped terrain math (`fastTerrainMath`)

Relaxed math in the terrain vertex shader is a shipped default since 0.4.5: every pipeline whose vertex and fragment shaders and includes prove to be stock terrain compiles with relaxed vertex and fragment math as one unit, so solid, cutout, translucent and OIT depth passes compute the same positions. The proof covers three shader families: vanilla `core/terrain` (including multidraw, cutout, translucent and all OIT passes), vanilla `core/block` (`solid_block`, `cutout_block`, `translucent_block`) and Sodium `block_layer_opaque` (pinned hashes for Sodium 0.9.2 / 0.9.3-beta.1 and for 0.9.3-beta.5; another Sodium version keeps strict math until its hashes are added). Both stages and every include are hashed; Pure Metal's own Terrain LOD texture repeat and RGSS early-out patch are accepted (the hash is taken on the unpatched source the patch wraps). Resource pack, mod and Iris edits and unknown defines keep strict math, and wireframe debug pipelines always do (they are not family members). A shader family never draws with mixed modes and never blinks: when any member is refused, the family only requests a switch. Its relaxed pipelines keep drawing relaxed while they build strict variants on background threads, and once all of them are ready the switch is published at the next frame boundary (start of a frame, before any pass) for every pipeline of the family at once. Refused pipelines compile strict and wait for that same switch. If a strict variant cannot be built, that pipeline stays undrawn after the switch and an error is logged. The family state belongs to one resource generation: after a resource reload (a new shader source) it starts fresh, so removing a pack that changed the terrain shaders restores relaxed math without a restart. The proof is bound to the exact compile input. It checks GLSL source, defines and names; the SPIR-V compiled from them is not re-derived. Everything outside terrain stays strict.

- `-Dpuremetal.fastTerrainMath=on|off`, default `on` on macOS 15 and newer. On macOS 14 and older the default is `off` (the log says why), because there is no `MTLMathModeRelaxed` and the legacy fast math also assumes no NaN or infinity; `on` forces it with a warning. The old values `opaque` and `all` mean `on`, `false` means `off`; a typo logs a warning and keeps `on`. The old `fastTerrain` / `fastTerrainAll` A/B switches and `-Dpuremetal.fastTerrainMath.allowLodPatched` are gone.
- Log lines to grep: `relaxed terrain math (vertex and fragment) compiled for` at compile time, `strict math kept for` when a terrain pipeline is refused (with the reason), `terrain draw with relaxed math` and `terrain draw with strict math` at the first draw of each pipeline in each mode (the math mode really bound), `terrain shader family ... will switch to strict math` when a refused member requests the switch, then `strict math variant requested for` and `strict math variant ready for` per relaxed sibling, then `terrain shader family ... is strict now (generation N` once, and `terrain draw with strict math` at each pipeline's next draw. The F3 screen shows `Fast terrain: On` or `Fast terrain: Off (reason)`, and so does the note at the top of Pure Metal's settings (Video Settings and the Sodium page). A Sodium world should show `sodium:pipeline/...` pipelines drawing, not only `minecraft:pipeline/...` ones.
- `-PbenchAB=fastTerrainMath` is the confirmation A/B: A = strict math, B = the shipped relaxed math, built as a second variant per proven terrain pipeline (vertex and fragment, compiled exactly like the shipped functions). Expect `terrain draw with strict math` lines in A and `terrain draw with relaxed math` lines in B for the same pipelines. A B without relaxed draw lines means the proof refused the pipelines, not that relaxed math is slow.
- Without any A/B, no pipeline is classified into math groups and no name lists are built; only pipelines of the three shader families hash their sources, once per family and shader source.

The `fastMath*` group switches above ignore the proof and select by pipeline name, so they can differ from the shipped behaviour exactly when the proof refuses a pipeline. Every `fastMath` / `fastTerrain` name must be spelled exactly, otherwise the launch fails with the list of valid names.

Same-session reference (M2, 3420x2148, Sodium 0.9.3 beta.5, Metal 3): `fastMathTerrain` 13.91 to 8.52 ms/f GPU, 127 to 201 FPS; `fastMathTerrainTranslucent` 14.64 to 12.82 ms/f; relaxed fragment-only terrain, clouds, post, entity, GUI and other groups showed no gain; screenshot diff A vs B stayed inside the A vs A noise (94 px differing by more than 16/255). Far-coordinate visual check: add `tp @s 1000000 140 1000000 0 40` to `-PbenchCommand` and compare the `ab-A1` and `ab-B` screenshots for depth fighting or seams.

## Terrain LOD

Terrain LOD is on by default at render distance 16 and above, with an 80% full-detail radius. With default settings Pure Metal therefore draws less distant terrain than OpenGL does, so the runs are not the same rendering workload. For a like-for-like comparison, turn Terrain LOD off in Pure Metal's settings, or state the setting next to any numbers you publish.

Results also depend heavily on the Mac, the world and the render distance, so compare runs on the same machine only.

## GPU time per render pass (Metal 3)

`-Dpuremetal.passTiming=true` (Gradle: `-PpassTiming`) reports where the frame's GPU time goes, per logical render pass. It is off by default and changes nothing then. It is for the in-game bench (`-Pbench=<seconds>`) on the Metal 3 path (`-Pmetal4=false`); on Metal 4 it logs "unsupported" and does nothing.

Example, with a fixed spot:

```
./gradlew runClient -Psodium -PsodiumVersion=mc26.3-0.9.3-beta.5-fabric -Pworld="New World" -Pbench=30 -PbenchWarmup=30 -Pprofile -Pmetal4=false -PpassTiming -PbenchCommand="gamemode spectator;tp @s 0 140 0 0 40;time set noon;weather clear"
```

Apple GPUs can only take timestamps at encoder (stage) boundaries, so in this mode every logical pass gets its own render encoder with samples, instead of being merged into one. At the end of the bench it prints:

- `BENCH-PROFILE vertexMs/f=.. fragmentMs/f=.. blitMs/f=..` (with `-Pprofile`): vertex and fragment time summed over all encoders, and the in-frame copy encoders. Metal 4 prints `spanMs/f` and `fragTailMs/f` instead (different meaning).
- `BENCH-PASS-SUMMARY`: `splitGpuMsPerFrame` is the total GPU time per frame in split mode. Compare it with `gpuMsPerFrame` of an unsplit run to see how much splitting inflates the frame. Also the sum of pass spans, vertex and fragment sums, blit time, timed passes per frame and passes that could not be timed.
- `BENCH-PASS label="<pass label> <width>x<height>[+d]"`: one line per pass, sorted by `totalMs/f`, the top 25 plus an `other` line. `count/f` is how often the pass ran per frame, `vertexMs/f` and `fragmentMs/f` its vertex and fragment stage time, `totalMs/f` its span (first vertex sample to last fragment sample) and `percent` its share of the summed spans. `+d` means the pass has a depth attachment. Clears that break a pass show as `clear`, and the scale-up to the window as `Pure Metal upscale`.

Reading it: vertex plus fragment can be less or more than `totalMs/f` (stages of one pass overlap, and the span includes gaps). The numbers are shares of the split frame, not of the normal one. Distortions of split mode:

- No merging: every merged pass now loads and stores its attachments, so memory traffic and total time go up.
- Depth clears are no longer folded into the previous pass and the temporal stretch is no longer folded into a pass, so extra `clear` and `Pure Metal upscale` encoders appear.
- Encoders of neighbouring passes can overlap on the GPU, so the sum of spans can exceed `splitGpuMsPerFrame`.
- Not timed: MetalFX and temporal compute work, the presenter's own work, and upload blits (blit time is the in-frame copy encoders only).

With temporal upscaling active, `-Pprofile` also splits command buffers around the hand pass for its own breakdown; the per-pass samples still work, but that case adds more distortion.

## Release checklist: terrain shader hashes

Relaxed terrain math only applies while the terrain shaders match the hashes pinned in `TerrainShaderProof.java`. A new Minecraft or Sodium version that changes one of those files silently drops terrain back to standard math (about 45% fewer FPS on the M2 test setup), shown as `Fast terrain: Off (...)` in F3. Before each release, and on every Minecraft or Sodium bump:

1. Run `java tools/CheckTerrainShaderHashes.java <minecraft-client.jar> [sodium.jar]` from the repository root (any JDK 21 or newer, no classpath). It reads the pinned families from `TerrainShaderProof.java` and checks each file against its own pinned hash, per family and as one complete variant, like the runtime proof. Exit code 0 means every family matches a pinned variant.
2. For a family with no matching variant, the tool prints the closest one and the files that differ with their current hashes. Diff those files against the previous version. If the change cannot affect relaxed math safety, add a new `Variant` (with its include map) in `TerrainShaderProof.java` and re-run the tool.
3. By hand on a Minecraft bump: the `ALPHA_CUTOUT` and `OIT_*` defines in `RenderPipelines` (`javap -c -constants`) and `LevelRenderer.OIT_*` against the allowlists in `TerrainShaderProof.java`.
4. Run one launch with Sodium and one without and grep the log for `relaxed terrain math (vertex and fragment) compiled for` (and no `strict math kept for` or `will switch to strict math`).
