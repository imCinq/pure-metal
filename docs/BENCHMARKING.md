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

`fastTerrain` / `fastTerrainAll` compare relaxed fragment math of the proven stock terrain pipelines only. They need `-Dpuremetal.fastTerrainMath.allowLodPatched=true` when Terrain LOD textures or the RGSS early-out patch are active, because the proof refuses the patched fragment text otherwise. `-Dpuremetal.fastTerrainMath` itself defaults to `off`.

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
