#!/usr/bin/env python3
"""Builds BotInventory for every Minecraft version we ship, from the one source tree.

The source tree is the 1.21 one, written against Mojang official names. Each
version is built by copying the tree, applying that version's substitutions,
and compiling.

Two build scripts, not one, because 26.x is a different toolchain: Mojang
stopped shipping the mappings, so `officialMojangMappings()` has nothing to
find and Loom split in two. The 26.x script is derived from the 1.21 one
rather than kept beside it, so the two cannot drift.

    python3 tools/multiversion.py                 # build every shipped version
    python3 tools/multiversion.py 26.2            # just one
    python3 tools/multiversion.py 26.2 --errors   # compile only, list every error
"""

import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORK = ROOT / "build" / "multiversion"
VARIANTS = ROOT / "variants"
# Fedora has no java-21 package, so locally this is the Temurin unpacked by hand.
JDK = pathlib.Path(os.environ.get("JAVA_HOME") or pathlib.Path.home() / ".local/opt/jdk-21")

# Minecraft version -> the Fabric Loader version for it.
TARGETS = {"26.2": "0.19.5", "26.3": "0.19.5"}

# Versions a shipped jar also runs on, so they are declared rather than built.
COVERS = {}

# Versions whose jar is covered by another version's COVERS.
SKIP = set()

ORDER = ["26.2", "26.3"]

# BotInventory is so small that no renames have happened to it between 1.21 and
# 26.2. The source is written against Mojang official names, which are the real
# names in the unobfuscated 26.x jars. The only difference is the toolchain.
RULES = {}


def unobfuscated(version):
    """True from 26.1 on, which is where Minecraft stopped shipping obfuscated."""
    return not version.startswith("1.")


def jdk_for(version):
    """Which JDK compiles this version. The 26 series refuses to configure on anything but 25."""
    if not unobfuscated(version):
        return JDK

    candidates = [os.environ.get("BOTINVENTORY_JDK25"),
                  os.environ.get("JAVA_HOME_25_X64"),
                  os.environ.get("JAVA_HOME_25"),
                  pathlib.Path.home() / ".local/opt/jdk-25",
                  "/usr/lib/jvm/java-25-openjdk"]

    for candidate in candidates:
        if candidate and pathlib.Path(candidate).is_dir():
            return pathlib.Path(candidate)

    return JDK


def rules_for(version):
    return RULES.get(version, [])


def variant_dirs_for(version):
    upto = ORDER[:ORDER.index(version) + 1]
    return [VARIANTS / name for name in upto if (VARIANTS / name).is_dir()]


def apply_variants(version, target):
    overridden = set()

    for directory in variant_dirs_for(version):
        for source in directory.rglob("*"):
            if not source.is_file():
                continue

            destination = target / source.relative_to(directory)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            overridden.add(destination.resolve())

    return overridden


def unobfuscate_build(path):
    """Turn the build script into the one a 26.x mod uses."""
    text = path.read_text(encoding="utf-8")
    text = text.replace("id 'net.fabricmc.fabric-loom-remap'", "id 'net.fabricmc.fabric-loom'")
    text = text.replace("\tmappings loom.officialMojangMappings()\n", "")
    text = text.replace("modImplementation ", "implementation ")
    text = text.replace("it.options.release = 21", "it.options.release = 25")
    text = text.replace("JavaVersion.VERSION_21", "JavaVersion.VERSION_25")
    text = text.replace("JavaLanguageVersion.of(21)", "JavaLanguageVersion.of(25)")
    path.write_text(text, encoding="utf-8")


def mod_version():
    text = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    return text.split("mod_version=")[1].splitlines()[0].strip()


def report(errors, target, limit=12):
    by_file = {}

    for line in errors:
        name = line.split(".java:")[0].split("/")[-1] + ".java" if ".java:" in line else "?"
        by_file.setdefault(name, []).append(line.strip().replace(str(target) + "/", ""))

    for name, lines in sorted(by_file.items(), key=lambda item: -len(item[1])):
        print(f"     {name}: {len(lines)}")

        for line in lines if limit is None else lines[:limit]:
            print("        ", line)


def accepted_range(version):
    covered = sorted([version] + COVERS.get(version, []),
                     key=lambda name: ORDER.index(name) if name in ORDER else 0)

    if len(covered) == 1:
        return version

    return f">={covered[0]} <={covered[-1]}"


def build(version, loader, errors_only=False):
    target = WORK / version
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)

    for item in ["src", "gradle", "gradlew", "build.gradle", "settings.gradle",
                 "gradle.properties", "LICENSE"]:
        source = ROOT / item
        destination = target / item

        if source.is_dir():
            shutil.copytree(source, destination)
        else:
            shutil.copy2(source, destination)

    properties = target / "gradle.properties"
    lines = []

    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("minecraft_version="):
            line = f"minecraft_version={version}"
        elif line.startswith("loader_version="):
            line = f"loader_version={loader}"

        if version == "26.3" and line.startswith("carpet_version="):
            line = "carpet_version=26.3+v260915"
        elif version == "26.3" and line.startswith("sgui_version="):
            line = "sgui_version=2.2.1+26.3"

        lines.append(line)

    properties.write_text("\n".join(lines) + "\n", encoding="utf-8")

    if version == "26.3":
        wrapper = target / "gradle" / "wrapper" / "gradle-wrapper.properties"
        wrapper.write_text(wrapper.read_text(encoding="utf-8")
                           .replace("gradle-9.5.0-bin.zip", "gradle-9.6.0-bin.zip"),
                           encoding="utf-8")

    manifest = target / "src" / "main" / "resources" / "fabric.mod.json"
    text = manifest.read_text(encoding="utf-8")
    text = re.sub(r'"minecraft": "[^"]*"', f'"minecraft": "{accepted_range(version)}"', text)

    if unobfuscated(version):
        text = re.sub(r'"java": ">=\d+"', '"java": ">=25"', text)
    if version == "26.3":
        text = re.sub(r'"fabricloader": "[^"]*"', '"fabricloader": ">=0.19.5"', text)

    manifest.write_text(text, encoding="utf-8")

    if version == "26.3":
        gradle = target / "build.gradle"
        gradle.write_text(gradle.read_text().replace(
            'carpet:fabric-carpet:${project.carpet_version}', 'maven.modrinth:carpet:yt9oDFOj'))

    if unobfuscated(version):
        unobfuscate_build(target / "build.gradle")
        mixins = target / "src" / "main" / "resources" / "botinventory-carpet.mixins.json"
        mixins.write_text(mixins.read_text(encoding="utf-8")
                          .replace('"JAVA_21"', '"JAVA_25"'), encoding="utf-8")

    overridden = apply_variants(version, target)
    applied = 0

    for path in (target / "src").rglob("*.java"):
        if path.resolve() in overridden:
            continue

        original = path.read_text(encoding="utf-8")
        patched = original

        for old, new in rules_for(version):
            patched = patched.replace(old, new)

        if patched != original:
            path.write_text(patched, encoding="utf-8")
            applied += 1

    print(f"  {version}: {len(overridden)} variant files, patched {applied}, building...",
          flush=True)
    task = "compileJava" if errors_only else "build"
    result = subprocess.run(
        ["./gradlew", task, "-q", "--console=plain"],
        cwd=target, capture_output=True, text=True,
        env={**os.environ, "JAVA_HOME": str(jdk_for(version))})

    if result.returncode != 0:
        log = target / "build-failure.log"
        log.write_text(result.stdout + result.stderr, encoding="utf-8")
        errors = [line for line in (result.stdout + result.stderr).splitlines()
                  if "error:" in line]
        print(f"  {version}: FAILED, {len(errors)} errors")
        report(errors, target, limit=None if errors_only else 12)
        print(f"     Full build output: {log}")
        return None

    if errors_only:
        print(f"  {version}: compiles clean")
        return None

    jars = [jar for jar in (target / "build" / "libs").glob("*.jar")
            if "sources" not in jar.name]

    if not jars:
        print(f"  {version}: built but produced no jar")
        return None

    out = WORK / f"botinventory-carpet-{mod_version()}+{version}.jar"
    shutil.copy2(jars[0], out)
    print(f"  {version}: OK -> {out.name}")
    return out


def main():
    arguments = sys.argv[1:]
    errors_only = "--errors" in arguments
    wanted = [item for item in arguments if not item.startswith("--")] or list(TARGETS)
    WORK.mkdir(parents=True, exist_ok=True)
    built = {}

    for version in wanted:
        if version in SKIP:
            print(f"  {version}: covered by another jar, not built")
            continue

        if version not in TARGETS:
            print(f"  {version}: not a version we know about")
            continue

        jar = build(version, TARGETS[version], errors_only)

        if jar:
            built[version] = jar.name

    if errors_only:
        return 0

    print("\nbuilt:", json.dumps(built, indent=2))
    return 0 if len(built) == len([v for v in wanted if v not in SKIP]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
