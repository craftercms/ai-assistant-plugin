#!/usr/bin/env python3
"""
Add missing import lines when a class from this plugin is used by simple name in another package.
Run from repo root after repackage-ai-assistant-groovy.py.
"""
from __future__ import annotations

from pathlib import Path
import re
import sys

REPO = Path(__file__).resolve().parents[1]
ROOT = REPO / "authoring/scripts/classes/plugins/org/craftercms/aiassistant"
REST = REPO / "authoring/scripts/rest"


def build_class_map(root: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for path in root.rglob("*.groovy"):
        text = path.read_text(encoding="utf-8")
        m = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not m:
            continue
        pkg = m.group(1)
        cm = re.search(
            r"^(?:final\s+)?(?:abstract\s+)?class\s+(\w+)|^interface\s+(\w+)",
            text,
            re.M,
        )
        if cm:
            name = cm.group(1) or cm.group(2)
            out[name] = f"{pkg}.{name}"
    return out


def pkg_of(text: str) -> str:
    m = re.search(r"^package\s+([\w.]+)", text, re.M)
    return m.group(1) if m else ""


def needs_import(text: str, pkg: str, simple: str, fq: str) -> bool:
    if fq in text or pkg == fq.rsplit(".", 1)[0]:
        return False
    if not re.search(r"\b" + re.escape(simple) + r"\b", text):
        return False
    if not re.search(
        r"(?<![.\w])" + re.escape(simple) + r"(?=\s|[<(,;]|\s+extends|\s+implements)",
        text,
    ):
        return False
    return True


def add_imports(path: Path, class_map: dict[str, str]) -> bool:
    text = path.read_text(encoding="utf-8")
    pkg = pkg_of(text)
    needed = [
        f"import {fq}"
        for simple, fq in sorted(class_map.items(), key=lambda x: -len(x[0]))
        if needs_import(text, pkg, simple, fq)
    ]
    if not needed:
        return False
    lines = text.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    if i >= len(lines) or not lines[i].startswith("package "):
        return False
    out.append(lines[i])
    i += 1
    while i < len(lines) and lines[i].strip() == "":
        out.append(lines[i])
        i += 1
    existing = set()
    j = i
    while j < len(lines) and lines[j].startswith("import "):
        existing.add(lines[j].strip())
        out.append(lines[j])
        j += 1
    for imp in sorted(set(needed)):
        line = imp + "\n"
        if line.strip() not in existing:
            out.append(line)
    out.extend(lines[j:])
    path.write_text("".join(out), encoding="utf-8")
    return True


def main() -> int:
    class_map = build_class_map(ROOT)
    updated = []
    for base in (ROOT, REST):
        if not base.exists():
            continue
        for path in sorted(base.rglob("*.groovy")):
            if add_imports(path, class_map):
                updated.append(str(path))
    for p in updated:
        print(p)
    print(f"updated {len(updated)} files", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
