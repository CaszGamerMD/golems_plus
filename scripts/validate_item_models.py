#!/usr/bin/env python3
"""Check every registered Golems Plus item has a 26.2 item definition.

The new 26.2 assets/<namespace>/items/<id>.json entry is required even
when the older models/item/<id>.json file exists. Block items may
reference a 3D block model directly.
"""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
assets = root / "src/main/resources/assets/golems_plus"
src = (root / "src/main/java/com/caszgamermd/golemsplus/world/WorksiteContent.java").read_text()
items = set(re.findall(r'(?:blockItem|golemItem)\("([^"]+)"', src))
items.update(re.findall(
    r'ResourceKey<Item>\s+\w+\s*=\s*ResourceKey\.create\(\s*Registries\.ITEM\s*,\s*GolemsPlus\.id\("([^"]+)"\)',
    src,
))
# Additional registrations using a line break between the key and identifier.
items.update(re.findall(
    r'ResourceKey<Item>\s+\w+\s*=\s*ResourceKey\.create\(\s*Registries\.ITEM\s*,\s*GolemsPlus\.id\("([^"]+)"\)',
    src.replace("\n", " "),
))
errors = []
for name in sorted(items):
    path = assets / "items" / f"{name}.json"
    if not path.exists():
        errors.append(f"{name}: missing 26.2 items/{name}.json")
        continue
    data = json.loads(path.read_text())
    model = data.get("model", {}).get("model")
    if not isinstance(model, str) or ":" not in model:
        errors.append(f"{name}: no model reference")
        continue
    namespace, relative = model.split(":", 1)
    if namespace == "golems_plus":
        target = assets / "models" / f"{relative}.json"
        if not target.exists():
            errors.append(f"{name}: missing {target.relative_to(assets)}")
    elif namespace != "minecraft":
        errors.append(f"{name}: unexpected model namespace {namespace}")
print(f"Audited {len(items)} registered item models")
if errors:
    raise SystemExit("\n".join(errors))
print("All registered item definitions and model references exist.")
