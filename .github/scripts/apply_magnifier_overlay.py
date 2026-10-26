from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path


def git_show(ref: str, path: str) -> str:
    return subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    ).stdout


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="")


def overlay_settings(source: str, destination: Path) -> None:
    old = git_show(source, "extensions/youtube/src/main/java/app/morphe/extension/youtube/settings/Settings.java")
    current = destination.read_text(encoding="utf-8")
    start_marker = "    // Magnifier appearance settings are controlled in the player Style panel."
    end_marker = "    // Video"
    start = old.find(start_marker)
    end = old.find(end_marker, start)
    class_marker = "public class Settings extends SharedYouTubeSettings {"
    if start < 0 or end < 0:
        raise SystemExit("Magnifier settings block markers changed; refusing to overlay.")
    block = old[start:end].rstrip()
    if "MAGNIFIER_ENABLED" not in current:
        if class_marker not in current:
            raise SystemExit("Upstream Settings class marker changed; refusing to overlay.")
        current = current.replace(class_marker, class_marker + "\n" + block + "\n\n", 1)
    else:
        required = (
            "MAGNIFIER_ENABLED",
            "MAGNIFIER_BORDER_THICKNESS",
            "MAGNIFIER_BORDER_OPACITY",
            "MAGNIFIER_BORDER_COLOR",
        )
        if not all(name in current for name in required):
            raise SystemExit("Partial magnifier settings block found; refusing an unsafe overlay.")
        if current.count('"morphe_magnifier_enabled"') != 1:
            raise SystemExit("Expected exactly one magnifier preference key.")
    write_text(destination, current)


def overlay_strings(source: str, destination: Path) -> None:
    old = git_show(source, "patches/src/main/resources/addresources/values/youtube/strings.xml")
    current = destination.read_text(encoding="utf-8")
    names = ("morphe_magnifier_enabled_title", "morphe_magnifier_enabled_summary")
    additions: list[str] = []
    for name in names:
        pattern = rf'^\s*<string name="{re.escape(name)}">.*?</string>\s*$'
        matches = re.findall(pattern, old, flags=re.MULTILINE)
        if len(matches) != 1:
            raise SystemExit(f"Expected exactly one source string for {name}.")
        existing = len(re.findall(rf'<string name="{re.escape(name)}">', current))
        if existing == 0:
            additions.append(matches[0])
        elif existing != 1:
            raise SystemExit(f"Duplicate resource {name}; refusing to overlay.")
    if additions:
        closing = current.rfind("</resources>")
        if closing < 0:
            raise SystemExit("Could not find </resources> in YouTube strings.")
        current = current[:closing].rstrip() + "\n" + "\n".join(additions) + "\n" + current[closing:]
    write_text(destination, current)


def overlay_gradle_credentials(destination: Path) -> None:
    current = destination.read_text(encoding="utf-8")
    replacements = (
        (
            'username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR"))',
            'username = providers.gradleProperty("gpr.user")\n                    .orElse(providers.environmentVariable("MORPHE_GPR_USER"))\n                    .getOrElse(System.getenv("GITHUB_ACTOR"))',
            "MORPHE_GPR_USER",
        ),
        (
            'password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN"))',
            'password = providers.gradleProperty("gpr.key")\n                    .orElse(providers.environmentVariable("MORPHE_GPR_KEY"))\n                    .getOrElse(System.getenv("GITHUB_TOKEN"))',
            "MORPHE_GPR_KEY",
        ),
    )
    for old, new, marker in replacements:
        if marker in current:
            continue
        if old not in current:
            raise SystemExit(f"Upstream package credential expression changed; refusing to modify settings.gradle.kts ({marker}).")
        current = current.replace(old, new, 1)
    write_text(destination, current)


def main() -> None:
    parser = argparse.ArgumentParser(description="Reapply only the fork's YouTube magnifier overlay to an upstream checkout.")
    parser.add_argument("--source-ref", required=True, help="Fork ref containing the reviewed magnifier source, e.g. origin/dev")
    args = parser.parse_args()
    files = (
        "extensions/youtube/src/main/java/app/morphe/extension/youtube/videoplayer/MagnifierRecon.java",
        "patches/src/main/kotlin/app/morphe/patches/youtube/layout/player/buttons/MagnifierReconPatch.kt",
    )
    for relative in files:
        write_text(Path(relative), git_show(args.source_ref, relative))
    overlay_settings(
        args.source_ref,
        Path("extensions/youtube/src/main/java/app/morphe/extension/youtube/settings/Settings.java"),
    )
    overlay_strings(
        args.source_ref,
        Path("patches/src/main/resources/addresources/values/youtube/strings.xml"),
    )
    overlay_gradle_credentials(Path("settings.gradle.kts"))
    print(f"Applied magnifier overlay from {args.source_ref}; source revision {subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()}.")


if __name__ == "__main__":
    main()
