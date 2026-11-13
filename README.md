# Pure Metal

A native Metal renderer for Minecraft on Apple Silicon Macs. Same visuals as vanilla, rendered through Apple's Metal API instead of OpenGL. Works with Sodium.

- Fast Terrain renders terrain much faster on the GPU, on by default (macOS 15+)
- Terrain LOD simplifies distant terrain, leaves, plants and water at render distance 16+
- Optional Render Scale with temporal upscaling and an Auto mode
- Faster World Generation limits FPS while nearby chunks are missing (optional, singleplayer)
- Experimental Metal 4 mode (macOS 26+)
- Experimental Smooth Frame Pacing with VSync tries to show frames at an even pace
- With VSync off, Minecraft can render at a higher FPS without screen tearing by default
- Distant Horizons support, on by default (tested on DH 3.3.4, other 3.3.x run as untested when the key classes match 3.3.4)
- A Pure Metal line on the F3 screen for bug reports, including the Fast Terrain status

![Pure Metal vs OpenGL with Sodium: 216 vs 111 FPS](docs/gallery/1-sodium.webp)

## Performance

*Measured on Pure Metal 0.4.5 with its default settings. MacBook Air M2 (fanless), render distance 16, VSync off, no FPS cap. Every run starts at the same spot and view: 8 seconds of warm-up, then 20 seconds measured. Medians of 3 runs in alternating order.*

**With Sodium (fullscreen 3420x2148)**

| Renderer | Avg FPS | 1-in-200 slowest frame (p99.5) |
| --- | --- | --- |
| Pure Metal | <strong>216</strong> | <strong>5.1 ms</strong> |
| OpenGL | 111 | 18.2 ms |

**Without Sodium (fullscreen 3420x2148)**

| Renderer | Avg FPS | 1-in-200 slowest frame (p99.5) |
| --- | --- | --- |
| Pure Metal | <strong>147</strong> | <strong>7.2 ms</strong> |
| OpenGL | 58 | 28.0 ms |

**Pure Metal vs mcopt (1920x1080)**

*mcopt runs without Sodium, since the two are incompatible.*

| Renderer | Avg FPS | 1-in-200 slowest frame (p99.5) |
| --- | --- | --- |
| Pure Metal + Sodium, 50% scale | <strong>453</strong> | <strong>3.0 ms</strong> |
| mcopt | 438 | 3.2 ms |
| Pure Metal + Sodium | 352 | 3.3 ms |
| Pure Metal | 216 | 5.0 ms |
| OpenGL (no Sodium) | 60 | 23.7 ms |

**Pure Metal on different Macs (more coming soon)**

*Minecraft 26.3, no Sodium, render distance 16, fullscreen at each Mac's native resolution, VSync off, no FPS cap. M2 ran at 50% LOD, others at the default. Same seed and flight route for all: a warm-up lap, then 30 seconds measured. Results other than M2 were provided by third-party testers.*

| Mac | Pure Metal | Avg FPS | 1-in-100 slowest frame (p99) | 1-in-1000 slowest frame (p99.9) |
| --- | --- | --- | --- | --- |
| M5 Max, 48 GB | 0.4.5 | <strong>1043.4</strong> | <strong>1.4 ms</strong> | <strong>3.2 ms</strong> |
| M5 Max, 48 GB | 0.4.4.1 | 940.9 | 1.9 ms | 3.8 ms |
| M3 Max, 36 GB | 0.4.4.1 | 551.6 | 3.2 ms | 6.3 ms |
| M2 Air, 16 GB | 0.4.5 | 200.7 | 8.9 ms | 15.0 ms |

To measure on your own Mac, see [Run your own benchmark](#run-your-own-benchmark).

## Requirements

- macOS 14 or newer on Apple Silicon (tested on M2). Fast Terrain needs macOS 15. The optional Metal 4 mode needs macOS 26.
- Minecraft 26.3 with Fabric Loader 0.19+
- Java 25
- Sodium (optional)

## Install

Download the jar from the [releases page](https://github.com/imCinq/Pure-Metal/releases) and put it in your `mods` folder. Leave Graphics API on Default. Find Pure Metal settings in Video Settings or on its page in Sodium.

## Fast Terrain

On by default, needs macOS 15. Terrain renders much faster on the GPU and looks the same.

- **Benefit:** the GPU does about half the work per frame. On a MacBook Air M2 at fullscreen, FPS went from 157 to 294 with Sodium and from 86 to 154 without it, measured with Fast Terrain switched on and off in the same session at the same spot.
- **Resource packs:** if a pack or mod changes the terrain shaders, Fast Terrain turns off on its own. Remove the pack and it comes back.
- **Status:** the F3 screen and Pure Metal's settings show `Fast terrain: On` or `Off`.

To turn it off, see [JVM options](#jvm-options).

## Distant Horizons

**Distant Horizons** support is on by default. DH **3.3.4** has been visually tested and 3.3.3 was checked in Loom. Other 3.3.x versions whose key classes match 3.3.4 also run, with an "untested version" note in F3 and a log warning. Keep DH's rendering engine on **AUTO**. Distant terrain may take time to generate.

With an unsupported DH version, including 3.3.x versions whose checked classes differ (or 3.2, 3.4, 4.x and so on), Pure Metal falls back to the normal backend and doesn't use Metal while DH is installed. To turn the support off, see [JVM options](#jvm-options).

![Distant Horizons](docs/gallery/3-distant-horizons.webp)

## Known limitations

- Iris and other shader mods are not supported, including together with Distant Horizons.
- Metal 4 is optional and off by default. Metal 3 is the recommended mode and is currently the faster one.
- Pure Metal is an independent project and is not affiliated with Mojang, Microsoft or Apple.

## JVM options

Add these to your launcher's JVM arguments and restart.

| Option | Effect |
| --- | --- |
| `-Dpuremetal.fastTerrainMath=off` | Turns Fast Terrain off |
| `-Dpuremetal.experimentalDistantHorizons=false` | Turns Distant Horizons support off (`FALSE` and `False` also work) |

With Distant Horizons support off, the game runs without Metal while DH stays installed. To keep Metal without DH, remove the DH mod instead.

## Build from source

```
./gradlew build
```

Needs Xcode 26 or newer, or its command line tools (the Objective-C bridge is built with the system `clang` against the macOS 26 SDK; the result still runs on macOS 14+) and a Java 25 toolchain. The jar ends up in `build/libs`.

### Run your own benchmark

`tools/bench.py` compares graphics APIs on your own Mac:

```
python3 -I tools/bench.py <tag> default opengl
```

Results depend on the Mac, the world and the render distance, so compare runs on the same machine only. Setup, options and a note on Terrain LOD are in [docs/BENCHMARKING.md](docs/BENCHMARKING.md).

## Docs

- [Design notes](docs/DESIGN.md)
- [Benchmarking](docs/BENCHMARKING.md)
- [Changelog](CHANGELOG.md)

## Issues

Bug reports go to the [issue tracker](https://github.com/imCinq/Pure-Metal/issues). Include the Pure Metal line from F3 and `latest.log`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
