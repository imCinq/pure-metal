# Contributing

Bug reports are welcome. Pull requests are welcome for fixes; for larger changes, open an issue first so we can agree on the approach.

- Build with `./gradlew build` (macOS, Apple Silicon, Java 25, Xcode command line tools). The build fails if the current version has no `CHANGELOG.md` entry.
- Run the headless tests with `python3 tools/test_terrain_lod.py` after building. CI runs them too, leaving out the timing-based fluid cost test.
- Keep changes focused and match the surrounding code style.
- Do not commit personal paths, user names, logs or world data. `./gradlew checkPrivacy` checks tracked text and processed resources for personal paths and the local user name; it does not generally detect logs or world data. Run `git config core.hooksPath .githooks` once so `.githooks/pre-push` also checks your commits before they leave your machine.
- By contributing you agree that your work is licensed under Apache 2.0.
