# Pure Metal

A native Metal renderer for Minecraft on Apple Silicon Macs. Same visuals as vanilla, rendered through Apple's Metal API instead of OpenGL. Works with Sodium.

- Terrain LOD simplifies distant terrain, leaves, plants and water at render distance 16+
- Optional Render Scale with temporal upscaling and an Auto mode
- Faster World Generation limits FPS while nearby chunks are missing (optional, singleplayer)
- Experimental Metal 4 mode (macOS 26+)
- Experimental Smooth Frame Pacing with VSync tries to show frames at an even pace
- With VSync off, Minecraft can render at a higher FPS without screen tearing by default
- Distant Horizons support, on by default (tested on DH 3.3.4, other 3.3.x run as untested when the key classes match 3.3.4)
- A Pure Metal line on the F3 screen for bug reports

![Before and after](docs/gallery/1-before-after.webp)

## Requirements

- macOS 14 or newer on Apple Silicon (tested on M2). The optional Metal 4 mode needs macOS 26.
- Minecraft 26.3 with Fabric Loader 0.19+
- Java 25
- Sodium (optional)

## Known limitations

- Experimental. Tested on an Apple M2 only; other Macs should work but are untested.
- Metal 4 mode is optional and has been slower or less stable than Metal 3 on the tested M2.
- Iris and other shader mods are not supported.
- Pure Metal is an independent project and is not affiliated with Mojang, Microsoft or Apple.

## Install

Download the jar from the [releases page](https://github.com/imCinq/Pure-Metal/releases) and put it in your `mods` folder. Leave Graphics API on Default. Find Pure Metal settings in Video Settings or on its page in Sodium.

## Distant Horizons

**Distant Horizons** support is on by default in **Pure Metal 0.4.3**. DH **3.3.4** has been visually tested. Starting with **0.4.4**, other 3.3.x versions whose key classes match 3.3.4 also run, with an "untested version" note in F3 and a log warning. DH 3.3.3 was checked in Loom. Keep DH's rendering engine on **AUTO**. Distant terrain may take time to generate. Not supported with Iris.

With an unsupported DH version, including 3.3.x versions whose checked classes differ (or 3.2, 3.4, 4.x and so on), Pure Metal falls back to the normal backend and doesn't use Metal while DH is installed. To turn the support off, add this to your launcher's JVM arguments and restart. If DH stays installed, the game then runs without Metal too, so to keep Metal without DH, remove the DH mod instead:

```
-Dpuremetal.experimentalDistantHorizons=false
```

The value `false` turns it off in any case (`FALSE` and `False` also work).

![Distant Horizons](docs/gallery/3-distant-horizons.webp)

## Build

```
./gradlew build
```

Needs Xcode 26 or newer, or its command line tools (the Objective-C bridge is built with the system `clang` against the macOS 26 SDK; the result still runs on macOS 14+) and a Java 25 toolchain. The jar ends up in `build/libs`.

## Benchmarking

![Benchmarks](docs/gallery/2-benchmarks.webp)

**Pure Metal vs mcopt (1920x1080)**

| Renderer | Avg FPS | 1-in-200 slowest frame (p99.5) |
| --- | --- | --- |
| Pure Metal + Sodium, 50% scale | <strong>465</strong> | <strong>2.8 ms</strong> |
| mcopt | 464 | 4.5 ms |
| Pure Metal + Sodium | 298 | 4.2 ms |
| Pure Metal | 247 | 4.9 ms |
| OpenGL (no Sodium) | 91 | 20.0 ms |

*Separate test from the benchmark above: one 20-second flight per renderer, same world and path. Pure Metal 0.4.2, Sodium 0.9.3-beta.1, mcopt 0.3.0-alpha.2.*

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
