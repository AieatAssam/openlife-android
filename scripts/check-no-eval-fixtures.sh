#!/usr/bin/env bash
# P2-04: the synthetic OCR evaluation set (eval/ocr/v1) is packaged only into
# vault's androidTest APK, under assets/v1/. Fail if an APK carries any of it.
set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 <apk>" >&2; exit 2; }
apk="$1"
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }

python3 -I - "$apk" <<'PY'
import re, sys, zipfile
pattern = re.compile(
    r"^assets/v1/"
    r"|(^|/)ocr/v1/"
    r"|(^|/)(fixtures|truth)/(screenshot|receipt|letter)-\d+\.(png|json)$"
    r"|^assets/(gate|manifest)\.json$"
)
with zipfile.ZipFile(sys.argv[1]) as apk:
    found = [name for name in apk.namelist() if pattern.search(name)]
if found:
    print("APK carries OCR evaluation fixtures:", file=sys.stderr)
    for name in found[:20]:
        print("  " + name, file=sys.stderr)
    sys.exit(1)
print("EVAL_FIXTURES_RESULT=absent")
PY
