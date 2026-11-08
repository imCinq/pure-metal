#!/usr/bin/env python3
"""Check the DH experiment using existing 3.3.x jars, without launching Minecraft."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile


def java_home():
    configured = os.environ.get("JAVA_HOME")
    if configured:
        return Path(configured)
    result = subprocess.run(["/usr/libexec/java_home", "-v", "25"], text=True, capture_output=True)
    if result.returncode == 0 and result.stdout.strip():
        return Path(result.stdout.strip())
    candidates = [Path("/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home")]
    candidates.extend(Path.home().glob(".gradle/jdks/*/jdk-25*/Contents/Home"))
    for candidate in candidates:
        if (candidate / "bin/java").is_file() and (candidate / "bin/javac").is_file():
            return candidate
    raise SystemExit("Java 25 JDK not found; set JAVA_HOME to a Java 25 JDK.")


def main():
    if len(sys.argv) < 2:
        raise SystemExit("Usage: python3 tools/test_distant_horizons.py /path/to/DistantHorizons-3.3.4.jar [newer-3.3.x.jar ...]")
    root = Path(__file__).resolve().parent.parent
    env = dict(os.environ)
    home = java_home()
    env["JAVA_HOME"] = str(home)
    for argument in sys.argv[1:]:
        check(Path(argument).resolve(strict=True), root, env, home)


def check(jar, root, env, home):
    with tempfile.TemporaryDirectory(prefix="puremetal-dh-check-") as directory:
        temp = Path(directory)
        init = temp / "classpath.gradle"
        init.write_text("""allprojects { afterEvaluate { tasks.register('dhCheckClasspath') {
            dependsOn tasks.named('classes')
            doLast { println 'DH_CP=' + (sourceSets.main.output + configurations.compileClasspath + configurations.runtimeClasspath).asPath }
        } } }
""")
        result = subprocess.run([str(root / "gradlew"), "--offline", "-q", "-I", str(init), "dhCheckClasspath"], cwd=root, env=env, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError("Failed to prepare test classpath:\n" + result.stdout + result.stderr)
        cp = next(line[6:] for line in result.stdout.splitlines() if line.startswith("DH_CP="))
        cp = os.pathsep.join(p for p in cp.split(os.pathsep) if "/maven.modrinth/sodium/" not in p)
        compiled = temp / "classes"
        compiled.mkdir()
        subprocess.run([str(home / "bin/javac"), "-cp", cp, "-d", str(compiled), str(root / "tools/TestDistantHorizonsKnot.java"), str(root / "tools/TestDistantHorizonsExperiment.java")], check=True, timeout=25)
        full_cp = str(compiled) + os.pathsep + cp
        subprocess.run([str(home / "bin/java"), "-Djava.awt.headless=true", "-cp", full_cp, "io.github.imcinq.puremetal.backend.TestDistantHorizonsExperiment", str(jar)], cwd=temp, check=True, timeout=25)
        groups = os.pathsep.join(str(root / path) for path in ("build/classes/java/main", "build/resources/main"))
        cases = [("disabled", False, False, None, None), ("enabled", True, True, None, None),
                 ("auto-config", True, True, 'renderingEngine = "AUTO"\n', None),
                 ("opengl", True, False, 'renderingEngine = "OPEN_GL"\n', None),
                 ("repacked", True, True, None, "repacked"),
                 ("altered-code", True, False, None, "altered"),
                 ("altered-constants", True, False, None, "altered-constants"),
                 ("default", None, True, None, None), ("disabled-upper", "FALSE", False, None, None)]
        for name, flag, expected, config, variant in cases:
            game = temp / name
            (game / "mods").mkdir(parents=True)
            copy = game / "mods/dh.jar"
            if variant:
                target = "com/seibel/distanthorizons/common/wrappers/minecraft/MinecraftRenderWrapper.class"
                constants = "com/seibel/distanthorizons/common/wrappers/VersionConstants.class"
                matches = 0
                with zipfile.ZipFile(jar) as source, zipfile.ZipFile(copy, "w") as out:
                    for entry in source.infolist():
                        data = source.read(entry)
                        if variant == "altered" and entry.filename == target:
                            matches += 1
                            old = b"MinecraftRenderWrapper.java"
                            if data.count(old) != 1: raise RuntimeError("Expected wrapper source marker")
                            data = data.replace(old, b"MinecraftRenderWrappex.java")
                        if variant == "altered-constants" and entry.filename == constants:
                            matches += 1
                            if data.count(b"26.3") != 1: raise RuntimeError("Expected one 26.3 constant in " + constants)
                            data = data.replace(b"26.3", b"26.4")
                        if variant == "repacked":
                            entry.compress_type = zipfile.ZIP_DEFLATED
                        out.writestr(entry, data)
                    if variant == "repacked":
                        out.writestr("puremetal-fingerprint-test.txt", "additional harmless resource")
                if variant != "repacked" and matches != 1:
                    raise RuntimeError("Expected exactly one class to alter for " + variant)
            else:
                shutil.copyfile(jar, copy)
            if config:
                (game / "config").mkdir()
                (game / "config/DistantHorizons.toml").write_text(config)
            # None omits the property (unset); booleans are lowercased; strings are passed through verbatim.
            flag_arg = [] if flag is None else ["-Dpuremetal.experimentalDistantHorizons=" + (str(flag).lower() if isinstance(flag, bool) else flag)]
            command = [str(home / "bin/java"), "-Djava.awt.headless=true", "-Dfabric.development=true",
                       "-Dfabric.defaultModDistributionNamespace=official", "-Dfabric.defaultMixinRemapType=static",
                       "-Dfabric.classPathGroups=" + groups, *flag_arg,
                       "-cp", full_cp, "TestDistantHorizonsKnot", str(game), str(expected).lower()]
            result = subprocess.run(command, cwd=game, text=True, capture_output=True, timeout=25)
            if result.returncode:
                raise RuntimeError(name + " failed:\n" + result.stdout + result.stderr)
            print(jar.name + " " + name + ": " + next(line for line in result.stdout.splitlines() if line.startswith("PASS DH Knot")))


if __name__ == "__main__":
    main()
