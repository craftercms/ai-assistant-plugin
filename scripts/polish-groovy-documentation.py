#!/usr/bin/env python3
"""Fix common low-quality blocks emitted by add-groovy-documentation.py."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PRIVATE_CTOR_RE = re.compile(
    r"^(\s*)/\*\*\s*\n"
    r"(?:\1 \* [^\n]+\n)+"
    r"\1 \*/\s*\n"
    r"\1private\s+(\w+)\s*\(\s*\)\s*\{",
    re.MULTILINE,
)

PRIVATE_CTOR_WITH_PARAMS_RE = re.compile(
    r"^(\s*)/\*\*\s*\n"
    r"(?:\1 \* [^\n]+\n)+"
    r"\1 \*/\s*\n"
    r"\1private\s+(\w+)\s*\([^)]*\)\s*\{",
    re.MULTILINE,
)

SECRETS_METHOD_DOCS = {
    "bind": (
        "Binds the working site id and Spring application context for secret resolution on this thread.",
        ["@param siteId CMS site id for macro lookup.", "@param applicationContext Spring context for secret services."],
    ),
    "clear": ("Clears thread-local secret resolution state; call from a finally block at REST boundaries.", []),
    "currentSiteId": (
        "Returns the site id bound for secret macro resolution on this thread, or empty when unset.",
        ["@return Trimmed site id, or empty when not bound."],
    ),
    "currentApplicationContext": (
        "Returns the application context bound for secret resolution on this thread, or null when unset.",
        ["@return Bound context, or null when not bound."],
    ),
}

METHOD_REPLACEMENTS = [
    (
        re.compile(
            r"(\s*)/\*\*\s*\n\1 \* Fetches input stream utf8 for tool use\.\s*\n(?:\1 \* @param streamLike[^\n]+\n)?\1 \* @return Text result[^\n]+\n\1 \*/\s*\n\1static String slurpInputStreamUtf8",
            re.MULTILINE,
        ),
        r"\1/**\n\1 * Reads an input stream to a UTF-8 string and closes the stream.\n\1 * @param streamLike Open stream from repository read APIs.\n\1 * @return File body as UTF-8 text, or null when the stream is null.\n\1 */\n\1static String slurpInputStreamUtf8",
    ),
    (
        re.compile(
            r"(\s*)/\*\*\s*\n\1 \* Normalizes and validates leading slash; throws when required values are missing\.\s*\n\1 \* @param value Caller-supplied input\.\s*\n\1 \* @param fieldName Caller-supplied input\.\s*\n\1 \* @return Text result[^\n]+\n\1 \*/\s*\n\1static String normalizeLeadingSlash",
            re.MULTILINE,
        ),
        r"\1/**\n\1 * Ensures a repository path is non-empty and starts with {@code /}.\n\1 * @param value Path or URL fragment from tool arguments.\n\1 * @param fieldName Parameter label used in validation errors.\n\1 * @return Normalized path with leading slash.\n\1 */\n\1static String normalizeLeadingSlash",
    ),
]


def polish_private_constructors(text: str) -> str:
    def repl(m: re.Match[str]) -> str:
        indent, class_name = m.group(1), m.group(2)
        decl = m.group(0).split("*/", 1)[1].strip()
        return (
            f"{indent}/**\n"
            f"{indent} * Private constructor; not for direct use.\n"
            f"{indent} */\n"
            f"{decl}"
        )

    text = PRIVATE_CTOR_WITH_PARAMS_RE.sub(repl, text)
    return PRIVATE_CTOR_RE.sub(repl, text)


def polish_secrets_context(text: str) -> str:
    if "class StudioAiAssistantSecretsContext" not in text:
        return text
    lines = text.splitlines()
    out: list[str] = []
    i = 0
    while i < len(lines):
        if i + 1 < len(lines) and lines[i].strip() == "/**":
            block_start = i
            j = i + 1
            while j < len(lines) and "*/" not in lines[j]:
                j += 1
            if j < len(lines):
                decl = lines[j + 1] if j + 1 < len(lines) else ""
                mm = re.search(r"static\s+(?:void|String|Object)\s+(\w+)\s*\(", decl)
                if mm and mm.group(1) in SECRETS_METHOD_DOCS:
                    summary, tags = SECRETS_METHOD_DOCS[mm.group(1)]
                    indent = re.match(r"^(\s*)", lines[i]).group(1)
                    out.append(f"{indent}/**")
                    out.append(f"{indent} * {summary}")
                    for t in tags:
                        out.append(f"{indent} * {t}")
                    out.append(f"{indent} */")
                    i = j + 1
                    continue
        out.append(lines[i])
        i += 1
    return "\n".join(out) + ("\n" if text.endswith("\n") else "")


def main() -> None:
    for path in sorted(ROOT.glob("authoring/scripts/**/*.groovy")):
        text = path.read_text(encoding="utf-8", errors="replace")
        original = text
        text = polish_private_constructors(text)
        for pattern, repl in METHOD_REPLACEMENTS:
            text = pattern.sub(repl, text)
        if path.name == "StudioAiAssistantSecretsContext.groovy":
            text = polish_secrets_context(text)
        if text != original:
            path.write_text(text, encoding="utf-8")
    print("Polished Groovy documentation blocks.")


if __name__ == "__main__":
    main()
