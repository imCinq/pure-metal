#!/usr/bin/env python3
"""Compare the six DH class fingerprints against the official 3.3.4 JAR.

Usage: python3 tools/compare_dh_jars.py 3.3.4.jar candidate.jar [candidate.jar ...]
Matching classes do not establish runtime compatibility. The candidate must also
satisfy Pure Metal's version, renderer and other preflight requirements.
"""
from pathlib import Path
import hashlib
import sys
import zipfile

FINGERPRINTS = {
    "com/seibel/distanthorizons/api/enums/config/EDhApiRenderingApi.class": "282d54510acb37c249437b5fea65a8929e92d911c666691f99a0a7fd8209b303",
    "com/seibel/distanthorizons/common/render/blaze/BlazeDhRenderApiDefinition.class": "5ab0f3a9a0de82e27c181676e93c85f4b998d7fe07d8acb39be4c44640260c06",
    "com/seibel/distanthorizons/common/wrappers/DependencySetup.class": "66e5ee35784e7c802015a9f0949fb585387d4a4d046f0de2787ef9cc4da0199d",
    "com/seibel/distanthorizons/common/wrappers/VersionConstants.class": "6131c47d6f17250e99c9d9e354312ab9f20257660279e516639c366d4fe5b0c6",
    "com/seibel/distanthorizons/common/wrappers/minecraft/MinecraftRenderWrapper.class": "9c10b82bcf5d939e9b67692097ba5ef9813e62ed23a259cbf1d468a9b30b98c4",
    "com/seibel/distanthorizons/fabric/mixins/client/MixinLightTexture.class": "5717a3c5cc3a5cbffa5ef76cedd36c3c532801f4852db3021dc78b1b1e6dc76e",
}


def differences(path):
    with zipfile.ZipFile(path) as jar:
        result = []
        for name, expected in FINGERPRINTS.items():
            try:
                info = jar.getinfo(name)
                if info.file_size > 2 * 1024 * 1024 or hashlib.sha256(jar.read(info)).hexdigest() != expected:
                    result.append(name + ": differs")
            except KeyError:
                result.append(name + ": missing")
        return result


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    reference = Path(sys.argv[1])
    if differences(reference):
        raise SystemExit("Reference does not match Pure Metal's official 3.3.4 fingerprints")
    failed = False
    for arg in sys.argv[2:]:
        problems = differences(Path(arg))
        print(Path(arg).name + (": fingerprints differ" if problems else ": fingerprints match"))
        for problem in problems:
            print("  " + problem)
        failed |= bool(problems)
    raise SystemExit(int(failed))


if __name__ == "__main__":
    main()
