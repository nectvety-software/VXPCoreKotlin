#!/usr/bin/env python3
"""Clean-room compatibility surface checker.

Extracts printable vm_* names from user-supplied VXP binaries and compares them
with first-class handlers registered by MreRuntime.kt. It does not read or require
any vendor SDK/header/library.
"""
from __future__ import annotations
import argparse, re
from pathlib import Path

VM = re.compile(rb"(?<![A-Za-z0-9_])(vm_[A-Za-z0-9_]{2,80})")
API = re.compile(r'api\("([^"]+)"')
IGNORE = {"vm_main", "vm_res"}  # vm_res may appear as an ELF resource-section marker

def observed(path: Path) -> set[str]:
    data = path.read_bytes()
    return {m.group(1).decode("ascii") for m in VM.finditer(data)} - IGNORE

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("vxp", nargs="+")
    ap.add_argument("--runtime", default="vxp-core/src/main/kotlin/vxpcore/MreRuntime.kt")
    ns = ap.parse_args()
    runtime = Path(ns.runtime).read_text(encoding="utf-8")
    registered = set(API.findall(runtime))
    all_obs: set[str] = set()
    failed = False
    for raw in ns.vxp:
        path = Path(raw)
        obs = observed(path)
        all_obs |= obs
        missing = sorted(obs - registered)
        print(f"{path.name}: observed={len(obs)} first_class={len(obs)-len(missing)} missing={len(missing)}")
        if missing:
            failed = True
            for name in missing:
                print(f"  MISSING {name}")
    missing_all = sorted(all_obs - registered)
    print(f"TOTAL unique_observed={len(all_obs)} covered={len(all_obs)-len(missing_all)} missing={len(missing_all)}")
    return 1 if failed else 0

if __name__ == "__main__":
    raise SystemExit(main())
