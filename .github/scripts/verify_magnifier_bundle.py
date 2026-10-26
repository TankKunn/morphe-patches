from __future__ import annotations

import argparse
import io
import json
import re
import tempfile
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

PATCH_CLASS = "app/morphe/patches/youtube/layout/player/buttons/MagnifierReconPatchKt.class"
PATCH_NAME = "Magnifier surface recon"
SETTING_KEY = "morphe_magnifier_enabled"
EXPECTED_STRINGS = ("morphe_magnifier_enabled_title", "morphe_magnifier_enabled_summary")


def fail(message: str) -> None:
    raise SystemExit(f"Bundle verification failed: {message}")


def check_source(root: Path) -> None:
    patch_source = root / "patches/src/main/kotlin/app/morphe/patches/youtube/layout/player/buttons/MagnifierReconPatch.kt"
    settings_source = root / "extensions/youtube/src/main/java/app/morphe/extension/youtube/settings/Settings.java"
    string_source = root / "patches/src/main/resources/addresources/values/youtube/strings.xml"
    for path in (patch_source, settings_source, string_source):
        if not path.is_file():
            fail(f"missing source file {path.as_posix()}")
    if patch_source.read_text(encoding="utf-8").count(SETTING_KEY) != 1:
        fail("source patch must declare the one master toggle exactly once")
    if settings_source.read_text(encoding="utf-8").count(f'"{SETTING_KEY}"') != 1:
        fail("Settings.java must declare the one master toggle exactly once")
    strings = ET.parse(string_source).getroot()
    declared = [e.attrib.get("name") for e in strings if e.tag == "string"]
    for name in EXPECTED_STRINGS:
        if declared.count(name) != 1:
            fail(f"YouTube strings must declare {name} once")

    resources_root = root / "patches/src/main/resources/addresources"
    for xml_path in resources_root.rglob("*.xml"):
        try:
            ET.parse(xml_path)
        except ET.ParseError as error:
            fail(f"malformed XML {xml_path.relative_to(root)}: {error}")

    # This fork's deliverable is normal YouTube. Product-specific resource
    # folders are separate Android resource qualifiers: a Music-only value
    # must not be treated as a YouTube resource (or vice versa).
    all_resource_xml = list(resources_root.rglob("*.xml"))
    youtube_resources = [p for p in all_resource_xml if "youtube" in p.parts]
    common_values = [
        p for p in all_resource_xml
        if any(part.startswith("values") for part in p.parts)
        and "music" not in p.parts and "reddit" not in p.parts
    ]
    resource_names: set[tuple[str, str]] = set()
    for xml_path in set(youtube_resources + common_values):
        if not any(part.startswith("values") for part in xml_path.parts):
            continue
        try:
            values = ET.parse(xml_path).getroot()
        except ET.ParseError:
            continue
        for element in values:
            name = element.attrib.get("name")
            if name:
                kind = element.tag.removesuffix("-array")
                resource_names.add((kind, name))
                if element.tag.endswith("-array"):
                    resource_names.add(("array", name))
    for xml_path in youtube_resources:
        content = xml_path.read_text(encoding="utf-8-sig")
        for kind, name in re.findall(r"@(string|array|drawable)/([A-Za-z0-9_]+)", content):
            # References to Android/target-app resources (for example
            # @string/application_name) are supplied by the APK being patched.
            if name.startswith("morphe_") and (kind, name) not in resource_names:
                fail(f"unresolved {kind} resource @{kind}/{name} in {xml_path.relative_to(root)}")
        try:
            xml_root = ET.parse(xml_path).getroot()
        except ET.ParseError:
            continue
        for element in xml_root.iter():
            class_name = element.attrib.get("class") or element.attrib.get("{http://schemas.android.com/apk/res/android}name", "")
            if not class_name.startswith("app.morphe."):
                continue
            relative = Path(*class_name.split("."))
            candidates = list(root.glob(f"**/{relative.name}.java")) + list(root.glob(f"**/{relative.name}.kt"))
            if not any(re.search(rf"\b(class|object|interface)\s+{re.escape(relative.name)}\b", p.read_text(encoding="utf-8", errors="ignore")) for p in candidates):
                fail(f"XML references missing project class {class_name}")
            # Android XML inflation needs Context and AttributeSet constructors for custom view/preference classes.
            if element.tag in ("view", "Preference", "SwitchPreference", "app.morphe.preference"):
                sources = [p.read_text(encoding="utf-8", errors="ignore") for p in candidates]
                if not any("AttributeSet" in source and "Context" in source for source in sources):
                    fail(f"XML-inflated class {class_name} has no Context/AttributeSet constructor declaration")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--patch-list", type=Path)
    parser.add_argument("--source-root", type=Path, default=Path("."))
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--expected-repo", help="Require this fork's exact release asset URL")
    parser.add_argument("--download-asset", action="store_true", help="Download the manifest asset before validating it")
    args = parser.parse_args()
    bundle_path = args.bundle
    if args.manifest:
        try:
            manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
            version = manifest["version"]
            download_url = manifest["download_url"]
        except (OSError, json.JSONDecodeError, KeyError, TypeError) as error:
            fail(f"invalid patches-bundle.json: {error}")
        if args.expected_repo:
            expected = f"https://github.com/{args.expected_repo}/releases/download/v{version}/patches-{version}.mpp"
            if download_url != expected:
                fail(f"manifest URL is not this fork's versioned asset URL: {download_url}")
        if args.download_asset:
            try:
                with urllib.request.urlopen(download_url, timeout=60) as response:
                    payload = response.read()
                with tempfile.NamedTemporaryFile(prefix="morphe-bundle-", suffix=".mpp", delete=False) as output:
                    output.write(payload)
                    bundle_path = Path(output.name)
            except Exception as error:
                fail(f"manifest release asset could not be downloaded: {error}")
        print(f"Manifest v{version}: {download_url}")

    if bundle_path is None:
        fail("provide --bundle or --manifest with --download-asset")
    if args.patch_list:
        check_source(args.source_root)
        try:
            data = json.loads(args.patch_list.read_text(encoding="utf-8"))
            patches = data["patches"]
        except (OSError, json.JSONDecodeError, KeyError, TypeError) as error:
            fail(f"invalid generated patches-list.json: {error}")
        matches = [p for p in patches if p.get("name") == PATCH_NAME]
        if len(matches) != 1:
            fail(f"expected one {PATCH_NAME!r} entry in patches-list.json, found {len(matches)}")
        if not any(pkg.get("packageName") == "com.google.android.youtube" for pkg in (matches[0].get("compatiblePackages") or [])):
            fail("magnifier patch does not target normal YouTube")
        youtube_count = sum(any(pkg.get("packageName") == "com.google.android.youtube" for pkg in (p.get("compatiblePackages") or [])) for p in patches)
        print(f"Generated list: {len(patches)} total patches; {youtube_count} for normal YouTube.")

    try:
        with zipfile.ZipFile(bundle_path) as bundle:
            names = set(bundle.namelist())
            for required in ("classes.dex", "extensions/youtube.mpe", PATCH_CLASS, "addresources/values/youtube/strings.xml"):
                if required not in names:
                    fail(f"MPP is missing {required}")
            class_data = bundle.read(PATCH_CLASS)
            if class_data.count(SETTING_KEY.encode()) != 1:
                fail("compiled magnifier patch must contain the one master toggle key exactly once")
            if PATCH_NAME.encode() not in class_data:
                fail("compiled magnifier patch name is missing")
            strings_root = ET.fromstring(bundle.read("addresources/values/youtube/strings.xml"))
            bundled_strings = [e.attrib.get("name") for e in strings_root if e.tag == "string"]
            for name in EXPECTED_STRINGS:
                if bundled_strings.count(name) != 1:
                    fail(f"MPP must contain {name} exactly once")
            for name in names:
                if name.startswith("addresources/") and name.endswith(".xml"):
                    try:
                        ET.fromstring(bundle.read(name))
                    except ET.ParseError as error:
                        fail(f"malformed bundled XML {name}: {error}")
            extension_dex = bundle.read("extensions/youtube.mpe")
            if not extension_dex.startswith(b"dex\n"):
                fail("YouTube extension .mpe is not a DEX file")
            if b"app/morphe/extension/youtube/videoplayer/MagnifierRecon" not in extension_dex:
                fail("YouTube extension dex does not contain MagnifierRecon")
    except (OSError, zipfile.BadZipFile) as error:
        fail(f"cannot read MPP: {error}")
    print("MPP contains the magnifier patch, the single settings toggle, valid XML, and the compiled YouTube extension.")


if __name__ == "__main__":
    main()
