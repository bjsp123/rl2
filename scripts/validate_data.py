#!/usr/bin/env python3
"""Validate rl2 data tables against string/config catalogs and code references."""

from __future__ import annotations

import csv
import re
import sys
from io import StringIO
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "assets" / "data"


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def read_csv(path: Path) -> list[dict[str, str]]:
    lines = []
    for line in read_text(path).splitlines():
        stripped = line.lstrip()
        if not stripped or stripped.startswith("#"):
            continue
        lines.append(line)
    if not lines:
        return []
    return list(csv.DictReader(StringIO("\n".join(lines))))


def java_files() -> list[Path]:
    roots = [ROOT / "rlib" / "src" / "main" / "java",
             ROOT / "rgame" / "src" / "main" / "java"]
    out: list[Path] = []
    for root in roots:
        out.extend(root.rglob("*.java"))
    return out


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//.*", "", text)
    return text


def enum_values(path: str, enum_name: str) -> set[str]:
    text = strip_comments(read_text(ROOT / path))
    m = re.search(r"\benum\s+" + re.escape(enum_name) + r"\s*\{", text)
    if not m:
        return set()
    i = m.end()
    depth = 1
    while i < len(text) and depth:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    body = text[m.end():i - 1]
    vals: set[str] = set()
    for part in body.split(","):
        token = part.strip()
        if not token or ";" in token:
            token = token.split(";", 1)[0].strip()
        m2 = re.match(r"([A-Z][A-Z0-9_]*)\b", token)
        if m2:
            vals.add(m2.group(1))
    return vals


def public_static_config_fields(path: str, class_name: str) -> set[str]:
    text = strip_comments(read_text(ROOT / path))
    fields: set[str] = set()
    pattern = re.compile(
        r"public\s+static\s+(?!final\b)(?:Color|int|float|double|long|boolean)\s+([A-Z][A-Z0-9_]*)\s*=")
    for m in pattern.finditer(text):
        fields.add(m.group(1))
    return fields


def gamebalance_config_keys() -> set[str]:
    keys = public_static_config_fields(
        "rlib/src/main/java/com/bjsp123/rl2/logic/GameBalance.java", "GameBalance")
    keys.update({
        "rules.surprise.damageMult",
        "rules.surprise.surpriseIfNoLOSNow",
        "rules.surprise.surpriseIfNoLOSLastTurn",
        "rules.surprise.allowThrow",
        "rules.surprise.allowAllTargetedAttackTypes",
    })
    keys.difference_update({
        "RULES_SURPRISE_DAMAGE_MULT",
        "RULES_SURPRISE_SURPRISE_IF_NO_LOS_NOW",
        "RULES_SURPRISE_SURPRISE_IF_NO_LOS_LAST_TURN",
        "RULES_SURPRISE_ALLOW_THROW",
        "RULES_SURPRISE_ALLOW_ALL_TARGETED_ATTACK_TYPES",
    })
    return keys


def split_list(cell: str | None) -> list[str]:
    if not cell:
        return []
    return [p.strip() for p in cell.split("|") if p.strip()]


def spawn_ref(entry: str) -> str:
    return entry.split("*", 1)[0].strip()


def drop_keyword(entry: str) -> str:
    e = entry.split("*", 1)[0].strip()
    e = e.split("+", 1)[0].strip()
    return e


def string_keys_used_in_code(all_string_keys: set[str]) -> set[str]:
    prefixes = {k.split(".", 1)[0] for k in all_string_keys if "." in k}
    literal = re.compile(r'"((?:[^"\\]|\\.)*)"')
    used: set[str] = set()
    for path in java_files():
        text = strip_comments(read_text(path))
        for m in literal.finditer(text):
            s = bytes(m.group(1), "utf-8").decode("unicode_escape")
            if "." not in s:
                continue
            if s.endswith("."):
                continue
            head = s.split(".", 1)[0]
            if head in prefixes:
                used.add(s)
    return used


def java_enum_key(enum_value: str) -> str:
    parts = enum_value.lower().split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def main() -> int:
    errors: list[str] = []

    rows = {name: read_csv(DATA / name) for name in [
        "items.csv", "mobs.csv", "brands.csv", "strings.csv", "config.csv",
        "themedrooms.csv", "help.csv", "tips.csv",
    ]}

    strings = {r.get("key", ""): r.get("en", "") for r in rows["strings.csv"]
               if r.get("key")}
    string_keys = set(strings)
    items = {r.get("type", "") for r in rows["items.csv"] if r.get("type")}
    mobs = {r.get("type", "") for r in rows["mobs.csv"] if r.get("type")}
    brands = {r.get("brand", "") for r in rows["brands.csv"] if r.get("brand")}
    themed_rooms = {r.get("type", "") for r in rows["themedrooms.csv"] if r.get("type")}

    item_effects = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Item.java", "ItemEffect")
    throw_results = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Item.java", "ThrowResult")
    inventory_categories = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Item.java", "InventoryCategory")
    use_behaviors = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Item.java", "UseBehavior")
    mob_behaviors = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Mob.java", "Behavior")
    materials = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Mob.java", "Material")
    states = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Mob.java", "StateOfMind")
    door_closings = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Mob.java", "DoorClosingBehavior")
    buffs = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Buff.java", "BuffType")
    perks = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Perk.java", "Perk")
    visual_themes = enum_values("rlib/src/main/java/com/bjsp123/rl2/model/Level.java", "VisualTheme")
    room_vegetation = enum_values("rlib/src/main/java/com/bjsp123/rl2/logic/ThemedRoomDefinition.java", "Vegetation")
    room_surfaces = enum_values("rlib/src/main/java/com/bjsp123/rl2/logic/ThemedRoomDefinition.java", "Surface")
    special_floors = enum_values("rlib/src/main/java/com/bjsp123/rl2/logic/ThemedRoomDefinition.java", "SpecialFloor")
    loot_categories = enum_values("rlib/src/main/java/com/bjsp123/rl2/logic/ItemGenerator.java", "LootCategory")
    room_shapes = enum_values("rlib/src/main/java/com/bjsp123/rl2/logic/ThemedRoomDefinition.java", "RoomShape")
    chasm_shapes = enum_values("rlib/src/main/java/com/bjsp123/rl2/logic/ThemedRoomDefinition.java", "ChasmShape")
    decorations = enum_values("rlib/src/main/java/com/bjsp123/rl2/logic/ThemedRoomDefinition.java", "Decoration")
    placements = enum_values("rlib/src/main/java/com/bjsp123/rl2/logic/ThemedRoomDefinition.java", "Placement")

    def require_string(key: str, why: str) -> None:
        if key not in string_keys or not strings.get(key):
            errors.append(f"missing string {key} ({why})")

    for item in sorted(items):
        require_string(f"item.{item}.name", f"item {item}")
        require_string(f"item.{item}.description", f"item {item}")
    for mob in sorted(mobs):
        require_string(f"mob.{mob}.name", f"mob {mob}")
        require_string(f"mob.{mob}.description", f"mob {mob}")
    for brand in sorted(brands):
        require_string(f"brand.{brand}.name", f"brand {brand}")
        require_string(f"brand.{brand}.description", f"brand {brand}")
    for buff in sorted(buffs):
        require_string(f"buff.{buff}.name", f"buff {buff}")
        require_string(f"buff.{buff}.description", f"buff {buff}")
    for perk in sorted(perks):
        key = java_enum_key(perk)
        require_string(f"perk.{key}.name", f"perk {perk}")
        require_string(f"perk.{key}.description", f"perk {perk}")

    for key in sorted(string_keys_used_in_code(string_keys) - string_keys):
        errors.append(f"code references missing string key {key}")

    def check_enum(file_name: str, row_id: str, row: dict[str, str],
                   col: str, allowed: set[str]) -> None:
        v = (row.get(col) or "").strip()
        if v and v not in allowed:
            errors.append(f"{file_name}:{row_id} {col}={v} is not a known enum value")

    def check_list(file_name: str, row_id: str, row: dict[str, str],
                   col: str, allowed: set[str], label: str) -> None:
        for v in split_list(row.get(col)):
            if v not in allowed:
                errors.append(f"{file_name}:{row_id} {col} references unknown {label} {v}")

    for r in rows["items.csv"]:
        rid = r.get("type", "<blank>")
        check_enum("items.csv", rid, r, "material", materials)
        check_enum("items.csv", rid, r, "theme", visual_themes)
        if not (r.get("inventoryCategory") or "").strip():
            errors.append(f"items.csv:{rid} inventoryCategory is required")
        check_enum("items.csv", rid, r, "inventoryCategory", inventory_categories)
        check_enum("items.csv", rid, r, "throwEffect", item_effects)
        check_enum("items.csv", rid, r, "throwResult", throw_results)
        check_enum("items.csv", rid, r, "useBehavior", use_behaviors)
        check_enum("items.csv", rid, r, "wandEffect", item_effects)
        check_list("items.csv", rid, r, "appliesBuff", buffs, "buff")
        check_list("items.csv", rid, r, "tameOnThrow", mobs, "mob")
        target = (r.get("summonsWhenUsed") or "").strip()
        if target and target not in mobs:
            errors.append(f"items.csv:{rid} summonsWhenUsed references unknown mob {target}")

    for r in rows["mobs.csv"]:
        rid = r.get("type", "<blank>")
        check_enum("mobs.csv", rid, r, "material", materials)
        check_enum("mobs.csv", rid, r, "theme", visual_themes)
        check_enum("mobs.csv", rid, r, "behavior", mob_behaviors)
        check_enum("mobs.csv", rid, r, "doorClosing", door_closings)
        check_enum("mobs.csv", rid, r, "stateOfMind", states)
        for col in ["eatSpawnType", "mushroomEatSpawnType", "turnSpawnType"]:
            v = (r.get(col) or "").strip()
            if v and v not in mobs:
                errors.append(f"mobs.csv:{rid} {col} references unknown mob {v}")
        mob_ref_tokens = mobs | {"PLAYER"}
        for col in ["attackTypes", "fleeTypes", "attackAllExcept", "retainerTypes"]:
            check_list("mobs.csv", rid, r, col, mob_ref_tokens, "mob")
        for s in split_list(r.get("startingInventory")):
            ref = spawn_ref(s)
            if ref and ref not in items:
                errors.append(f"mobs.csv:{rid} startingInventory references unknown item {ref}")
        check_list("mobs.csv", rid, r, "startingPerks", perks, "perk")
        for s in split_list(r.get("actionBar")):
            ref = s.split(":", 1)[-1].strip()
            if ref and ref not in items:
                errors.append(f"mobs.csv:{rid} actionBar references unknown item {ref}")
        for entry in split_list(r.get("dropQuality")):
            kw = drop_keyword(entry)
            if kw in {"NONE", "STUFF"} or kw in loot_categories or kw in items:
                continue
            errors.append(f"mobs.csv:{rid} dropQuality references unknown loot/item {kw}")
        for entry in (r.get("abilities") or "").split(";"):
            parts = [p.strip() for p in entry.split(":") if p.strip()]
            if not parts:
                continue
            kind = parts[0]
            if kind == "buff" and len(parts) >= 5:
                for b in [parts[1], parts[4]]:
                    if b not in buffs:
                        errors.append(f"mobs.csv:{rid} abilities references unknown buff {b}")
            elif kind == "heal" and len(parts) >= 3:
                if parts[2] not in buffs:
                    errors.append(f"mobs.csv:{rid} abilities references unknown buff {parts[2]}")
            elif kind == "teleport" and len(parts) >= 2:
                if parts[1] not in buffs:
                    errors.append(f"mobs.csv:{rid} abilities references unknown buff {parts[1]}")
            elif kind not in {"buff", "heal", "teleport"}:
                errors.append(f"mobs.csv:{rid} abilities has unknown kind {kind}")
        for entry in (r.get("initialBuffs") or "").split(";"):
            parts = [p.strip() for p in entry.split(":") if p.strip()]
            if parts and parts[0] not in buffs:
                errors.append(f"mobs.csv:{rid} initialBuffs references unknown buff {parts[0]}")

    for r in rows["brands.csv"]:
        rid = r.get("brand", "<blank>")
        check_list("brands.csv", rid, r, "itemTypes", inventory_categories, "inventory category")
        check_enum("brands.csv", rid, r, "element", item_effects)

    for r in rows["themedrooms.csv"]:
        rid = r.get("type", "<blank>")
        check_enum("themedrooms.csv", rid, r, "theme", visual_themes)
        check_enum("themedrooms.csv", rid, r, "roomShape", room_shapes)
        check_enum("themedrooms.csv", rid, r, "chasmShape", chasm_shapes)
        check_enum("themedrooms.csv", rid, r, "vegetation", room_vegetation)
        check_enum("themedrooms.csv", rid, r, "surface", room_surfaces)
        check_enum("themedrooms.csv", rid, r, "specialFloor", special_floors)
        check_list("themedrooms.csv", rid, r, "decorations", decorations, "decoration")
        check_enum("themedrooms.csv", rid, r, "placement", placements)
        for s in split_list(r.get("mobs")):
            ref = spawn_ref(s)
            if ref and ref not in mobs:
                errors.append(f"themedrooms.csv:{rid} mobs references unknown mob {ref}")
        for s in split_list(r.get("items")):
            ref = spawn_ref(s)
            if ref and ref not in items and ref not in loot_categories:
                errors.append(f"themedrooms.csv:{rid} items references unknown loot/item {ref}")

    for r in rows["help.csv"]:
        rid = r.get("key", "<blank>")
        image = (r.get("image") or "").strip()
        if image and image not in items and image not in mobs:
            errors.append(f"help.csv:{rid} image references unknown item/mob {image}")
        if rid:
            require_string(f"{rid}.title", f"help page {rid}")
            require_string(f"{rid}.para1", f"help page {rid}")

    for r in rows["tips.csv"]:
        key = (r.get("key") or "").strip()
        if key:
            require_string(key, f"tip {key}")

    config_rows = rows["config.csv"]
    by_kind: dict[str, set[str]] = {}
    for r in config_rows:
        by_kind.setdefault(r.get("kind", ""), set()).add(r.get("key", ""))
    expected_config = {
        "gamebalance": gamebalance_config_keys(),
        "ui": public_static_config_fields(
            "rgame/src/main/java/com/bjsp123/rl2/ui/v2/UIVars.java", "UIVars"),
        "animation": {
            f"animation.{key}" for key in public_static_config_fields(
                "rgame/src/main/java/com/bjsp123/rl2/world/anim/AnimationVars.java", "AnimationVars")
        },
    }
    for kind, keys in expected_config.items():
        for key in sorted(keys - by_kind.get(kind, set())):
            errors.append(f"config.csv missing {kind},{key}")
    for kind, keys in by_kind.items():
        if kind not in expected_config:
            errors.append(f"config.csv has unknown kind {kind}")
            continue
        for key in sorted(keys - expected_config[kind]):
            errors.append(f"config.csv has unknown {kind} key {key}")

    if errors:
        print("Data validation failed:")
        for e in errors:
            print(f" - {e}")
        return 1
    print("Data validation passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
