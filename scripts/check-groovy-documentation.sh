#!/usr/bin/env bash
# Reports Groovy types and methods under authoring/scripts/ missing a /** */ block on the previous line.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 << 'PY'
import re, glob, sys

SKIP_METHOD_NAMES = frozenset({
    'if', 'while', 'for', 'switch', 'catch', 'class', 'interface', 'enum', 'trait', 'new'
})

def has_doc_above(lines, decl_line_idx):
    """True when a /** … */ or // comment immediately precedes the declaration (annotations allowed)."""
    j = decl_line_idx - 1
    while j >= 0 and not lines[j].strip():
        j -= 1
    while j >= 0:
        s = lines[j].strip()
        if not s:
            j -= 1
            continue
        if s.startswith('@'):
            j -= 1
            continue
        if s.startswith('//'):
            return True
        if s.endswith('*/') or s.startswith('/**') or (s.startswith('*') and not s.startswith('*/')):
            k = j
            while k >= 0:
                if '/**' in lines[k]:
                    return True
                if lines[k].strip() and not lines[k].strip().startswith('*') and '*/' not in lines[k]:
                    break
                k -= 1
            return False
        return False
    return False

class_re = re.compile(
    r'^(?P<indent>\s*)(?:@\w+(?:\([^)]*\))?\s*)*(?:abstract\s+|final\s+)*'
    r'(?:class|interface|trait|enum)\s+(\w+)',
)
meth_re = re.compile(
    r'^(?P<indent>\s*)(?:(?:private|protected|public)\s+)?(?:(?:static)\s+)*'
    r'(?:def|void|boolean|String|int|long|float|double|byte|short|char|Object|Map|List|Set|'
    r'[\w<>\[\],\.\?]+)\s+(\w+)\s*\(',
)

def looks_like_method_declaration(line):
    """Exclude assignments, string false positives, and call expressions."""
    if '=' in line.split('(', 1)[0]:
        return False
    if '"""' in line or "'''" in line:
        return False
    return bool(re.search(r'\b(static|def|private|protected|public|void|boolean)\b', line))

violations = []
for path in sorted(glob.glob('authoring/scripts/**/*.groovy', recursive=True)):
    text = open(path, encoding='utf-8', errors='replace').read()
    lines = text.splitlines()
    for i, line in enumerate(lines):
        cm = class_re.match(line)
        if cm and not has_doc_above(lines, i):
            violations.append((path, i + 1, 'type', cm.group(1)))
        mm = meth_re.match(line)
        if mm and looks_like_method_declaration(line):
            name = mm.group(2)
            if name in SKIP_METHOD_NAMES:
                continue
            if name[0].isupper() and name.endswith('Exception'):
                continue
            if not has_doc_above(lines, i):
                violations.append((path, i + 1, 'method', name))

if not violations:
    print('OK: all Groovy types and methods have a preceding doc comment (heuristic).')
    sys.exit(0)

from collections import Counter
by_file = Counter(p for p, _, _, _ in violations)
print(f'Missing doc comment: {len(violations)} declaration(s) in {len(by_file)} file(s)\n')
for path, line, kind, name in violations[:80]:
    print(f'  {path}:{line} ({kind} {name})')
if len(violations) > 80:
    print(f'  … and {len(violations) - 80} more')
sys.exit(1)
PY
