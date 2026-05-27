#!/usr/bin/env python3
"""
Insert /** … */ blocks above Groovy types and methods missing documentation.
Uses the same heuristics as check-groovy-documentation.sh; review diffs for quality.
"""
from __future__ import annotations

import glob
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SKIP_METHOD_NAMES = frozenset(
    {"if", "while", "for", "switch", "catch", "class", "interface", "enum", "trait", "new"}
)

CLASS_RE = re.compile(
    r"^(?P<indent>\s*)(?:@\w+(?:\([^)]*\))?\s*)*(?:abstract\s+|final\s+)*"
    r"(?:class|interface|trait|enum)\s+(\w+)",
)
METH_RE = re.compile(
    r"^(?P<indent>\s*)(?:(?:private|protected|public)\s+)?(?:(?:static)\s+)*"
    r"(?:def|void|boolean|String|int|long|float|double|byte|short|char|Object|Map|List|Set|"
    r"[\w<>\[\],\.\?]+)\s+(\w+)\s*\(",
)


def has_doc_above(lines: list[str], decl_line_idx: int) -> bool:
    j = decl_line_idx - 1
    while j >= 0 and not lines[j].strip():
        j -= 1
    while j >= 0:
        s = lines[j].strip()
        if not s:
            j -= 1
            continue
        if s.startswith("@"):
            j -= 1
            continue
        if s.startswith("//"):
            return True
        if s.endswith("*/") or s.startswith("/**") or (s.startswith("*") and not s.startswith("*/")):
            k = j
            while k >= 0:
                if "/**" in lines[k]:
                    return True
                st = lines[k].strip()
                if st and not st.startswith("*") and "*/" not in lines[k]:
                    break
                k -= 1
            return False
        return False
    return False


def looks_like_method_declaration(line: str) -> bool:
    if "=" in line.split("(", 1)[0]:
        return False
    if '"""' in line or "'''" in line:
        return False
    return bool(re.search(r"\b(static|def|private|protected|public|void|boolean)\b", line))


def split_camel(name: str) -> list[str]:
    parts: list[str] = []
    buf = ""
    for ch in name:
        if ch.isupper() and buf and not buf[-1].isupper():
            parts.append(buf)
            buf = ch
        elif ch.isupper() and buf and buf[-1].isupper():
            if len(buf) > 1 and ch.islower():
                parts.append(buf[:-1])
                buf = buf[-1] + ch
            else:
                buf += ch
        else:
            buf += ch
    if buf:
        parts.append(buf)
    return [p.lower() for p in parts if p]


def extract_params(line: str) -> list[tuple[str, str]]:
    m = re.search(r"\((.*)\)\s*(?:throws\b|[\{;]|$)", line)
    if not m:
        inner = line.split("(", 1)[1]
        inner = inner.rsplit(")", 1)[0]
    else:
        inner = m.group(1)
    params: list[tuple[str, str]] = []
    depth = 0
    chunk = ""
    for ch in inner + ",":
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth = max(0, depth - 1)
        if ch == "," and depth == 0:
            piece = chunk.strip()
            chunk = ""
            if not piece:
                continue
            pm = re.match(r"(?:final\s+)?([\w<>\[\],\.\?]+)\s+(\w+)\s*(?:=.*)?$", piece)
            if pm:
                params.append((pm.group(1), pm.group(2)))
            continue
        chunk += ch
    return params


def return_type_hint(line: str) -> str | None:
    m = re.match(
        r"^\s*(?:(?:private|protected|public)\s+)?(?:(?:static)\s+)?"
        r"(def|void|boolean|String|int|long|float|double|Object|Map|List|Set|[\w<>\[\],\.\?]+)\s+\w+\s*\(",
        line,
    )
    if not m:
        return None
    t = m.group(1)
    if t in ("void", "def"):
        return None
    return t


def doc_for_private_ctor(class_name: str) -> str:
    return "Private constructor; prevents instantiation."


def doc_for_type(type_name: str, rel_path: str) -> str:
    words = " ".join(split_camel(type_name))
    pkg_hint = ""
    if "/spi/" in rel_path:
        pkg_hint = " SPI contract for orchestration and contrib implementations."
    elif "/engine/" in rel_path:
        pkg_hint = " Core orchestration engine helper."
    elif "/contrib/" in rel_path:
        pkg_hint = " Contrib implementation used by the plugin runtime."
    elif "/studio/" in rel_path:
        pkg_hint = " Studio integration (repository, config, HTTP)."
    elif "/secrets/" in rel_path:
        pkg_hint = " Request-scoped secret resolution context."
    return f"{type_name.replace('_', ' ')}: {words}.{pkg_hint}".strip()


def is_private_constructor_line(line: str, class_name: str | None) -> bool:
    if not class_name:
        return False
    return bool(
        re.match(
            rf"^\s*(?:private|protected|public)\s+{re.escape(class_name)}\s*\(\s*\)\s*",
            line,
        )
    )


def doc_for_method(
    method_name: str,
    line: str,
    class_name: str | None,
    rel_path: str,
) -> tuple[str, list[str]]:
    if is_private_constructor_line(line, class_name) or (
        class_name and method_name == class_name and "private" in line
    ):
        return doc_for_private_ctor(class_name or method_name), []

    params = extract_params(line)
    ret = return_type_hint(line)
    words = split_camel(method_name)
    phrase = " ".join(words) if words else method_name

    body = ""
    extra: list[str] = []

    lower = method_name.lower()
    if lower in ("bind", "clear", "currentsiteid", "currentapplicationcontext"):
        if "SecretsContext" in (class_name or ""):
            if lower == "bind":
                body = "Binds the working site id and Spring application context for this request thread."
            elif lower == "clear":
                body = "Clears thread-local secret resolution state; call from a finally block at REST boundaries."
            elif lower == "currentsiteid":
                body = "Returns the site id bound for secret macro resolution on this thread, or empty when unset."
            elif lower == "currentapplicationcontext":
                body = "Returns the application context bound for secret resolution on this thread, or null when unset."

    if not body:
        if lower.startswith("is") or lower.startswith("has"):
            body = f"True when {phrase[2:].strip() or phrase}."
        elif lower.startswith("get") and len(lower) > 3:
            body = f"Returns {phrase[3:].strip() or 'the requested value'}."
        elif lower.startswith("set") and len(lower) > 3:
            body = f"Updates {phrase[3:].strip()}."
        elif lower.startswith("load") or lower.startswith("read"):
            body = f"Loads {phrase[4:].strip() or phrase} from configuration or input."
        elif lower.startswith("resolve"):
            body = f"Resolves {phrase[7:].strip() or 'the effective value'} from request and plugin context."
        elif lower.startswith("normalize"):
            body = f"Normalizes and validates {phrase[9:].strip() or 'input'}; throws when required values are missing."
        elif lower.startswith("build"):
            body = f"Builds {phrase[5:].strip() or 'the result structure'} for tool or orchestration output."
        elif lower.startswith("attach"):
            body = f"Adds derived {phrase[6:].strip() or 'metadata'} entries to the tool result map when applicable."
        elif lower.startswith("extract"):
            body = f"Extracts {phrase[7:].strip() or 'a value'} from repository XML or related text."
        elif lower.startswith("fetch") or lower.startswith("slurp"):
            body = f"Fetches {phrase[5:].strip() or 'remote or stream content'} for tool use."
        elif lower.startswith("list"):
            body = f"Lists {phrase[4:].strip() or 'matching items'} for the model or author."
        elif lower.startswith("execute") or lower.startswith("run"):
            body = f"Runs {phrase} using Studio services and returns the tool payload."
        elif lower.startswith("apply"):
            body = f"Applies {phrase[5:].strip() or 'changes'} to repository content or orchestration state."
        elif lower.startswith("merge"):
            body = f"Merges {phrase[5:].strip() or 'inputs'} without dropping prior conversation context."
        elif lower.startswith("clip") or lower.startswith("truncate"):
            body = f"Truncates {phrase} to a safe maximum length for prompts or logs."
        elif lower.startswith("new"):
            body = f"Creates a configured {phrase[3:].strip() or 'instance'}."
        elif lower == "main":
            body = "Script entry point when executed standalone."
        else:
            body = f"{phrase[0].upper() + phrase[1:] if phrase else method_name}."

    if params and method_name not in (
        "bind",
        "clear",
    ):
        for _, pname in params:
            if pname in ("siteId", "path", "contentPath", "contentTypeId", "request", "params"):
                extra.append(f"@param {pname} Studio or repository context for this call.")
            elif pname.endswith("Id"):
                extra.append(f"@param {pname} Identifier for the target resource.")
            elif pname == "sink" or pname == "out" or pname == "result":
                extra.append(f"@param {pname} Mutable map receiving tool diagnostics or output fields.")
            else:
                extra.append(f"@param {pname} Caller-supplied input.")

    if ret in ("private", "protected", "public"):
        ret = None
    if ret and ret not in ("void", "def"):
        if ret == "boolean":
            extra.append("@return True when the check succeeds.")
        elif ret in ("String", "CharSequence"):
            extra.append("@return Text result, or empty or null when unavailable.")
        elif ret in ("Map", "List", "Set"):
            extra.append(f"@return {ret} payload for tools or orchestration.")
        else:
            extra.append(f"@return {ret} result.")

    return body.rstrip(".") + ".", extra


def format_doc_block(indent: str, summary: str, tags: list[str]) -> list[str]:
    lines = [f"{indent}/**", f"{indent} * {summary}"]
    for tag in tags:
        lines.append(f"{indent} * {tag}")
    lines.append(f"{indent} */")
    return lines


def collect_violations(path: Path, lines: list[str]) -> list[tuple[int, str, str, str]]:
    class_name = None
    out: list[tuple[int, str, str, str]] = []
    for i, line in enumerate(lines):
        cm = CLASS_RE.match(line)
        if cm and not has_doc_above(lines, i):
            class_name = cm.group(1)
            out.append((i, "type", class_name, line))
        mm = METH_RE.match(line)
        if mm and looks_like_method_declaration(line):
            name = mm.group(2)
            if name in SKIP_METHOD_NAMES:
                continue
            if name[0].isupper() and name.endswith("Exception"):
                continue
            if not has_doc_above(lines, i):
                out.append((i, "method", name, line))
    return out


def process_file(path: Path) -> int:
    rel = str(path.relative_to(ROOT)).replace("\\", "/")
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    violations = collect_violations(path, lines)
    if not violations:
        return 0

    class_name = None
    for line in lines:
        cm = CLASS_RE.match(line)
        if cm:
            class_name = cm.group(1)
            break

    added = 0
    for line_idx, kind, name, decl_line in sorted(violations, key=lambda v: v[0], reverse=True):
        indent_m = re.match(r"^(\s*)", decl_line)
        indent = indent_m.group(1) if indent_m else ""
        if kind == "type":
            summary = doc_for_type(name, rel)
            block = format_doc_block(indent, summary, [])
        else:
            summary, tags = doc_for_method(name, decl_line, class_name, rel)
            block = format_doc_block(indent, summary, tags)
        lines[line_idx:line_idx] = block
        added += 1

    path.write_text("\n".join(lines) + ("\n" if text.endswith("\n") else ""), encoding="utf-8")
    return added


def main() -> int:
    total = 0
    files = 0
    for pattern in ("authoring/scripts/**/*.groovy",):
        for p in sorted(ROOT.glob(pattern)):
            n = process_file(p)
            if n:
                files += 1
                total += n
    print(f"Added doc blocks for {total} declaration(s) in {files} file(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
