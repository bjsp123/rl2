"""
Fill in default values for every blank cell in mobs.csv and items.csv.

Defaults match MobDefinition.parseRow / ItemDefinition.parseRow exactly so the
on-disk file becomes self-explanatory: a reader sees the actual value the
parser would use rather than relying on knowledge of Java-side defaults.
"""

from __future__ import annotations

import csv
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Column-by-column defaults. Cells whose default is None / empty are left
# blank (string + list columns where the parser treats empty as null/empty).
MOB_DEFAULTS: dict[str, str | None] = {
    # Identity
    "type": None,
    "name": None,
    "description": None,
    "material": "FLESH",
    "behavior": "MOB",
    # Faction relationships
    "faction": None,
    "enemyFactions": None,
    # Spawn eligibility (level-gen)
    "minDepth": "1",
    "maxDepth": "10",
    "theme": None,
    # Combat baseline
    "maxHp": "10",
    "healRate": "0",
    "accuracy": "10",
    "evasion": "5",
    "damage": "0",
    "armor": "0",
    "apDamage": "0",
    "magicResist": "0",
    # Per-level scaling
    "hpPerLevel": "2",
    "accuracyPerLevel": "1",
    "evasionPerLevel": "1",
    "damagePerLevel": "1_2",
    "armorPerLevel": "0_1",
    "apPerLevel": "0",
    # Body / movement / perception
    "size": "4",
    "moveCost": "100",
    "visionRadius": "8",
    "wakeRadius": "6",
    # Ranged
    "rangedDamage": "0",
    "rangedDistance": "0",
    "rangedCost": "0",
    "rangedRateOfFire": "0",
    "rangedDamagePerLevel": "0",
    "rangedDistancePerLevel": "0",
    # Capability flags
    "flying": "false",
    "fireImmune": "false",
    "fireSpreadOnAttack": "false",
    "poisonsOnAttack": "false",
    "terrifying": "false",
    "terrifiable": "true",
    "banishable": "false",
    # Death / spawn behaviours
    "fireExplosionRadiusOnDeath": "0",
    "teleportRate": "0",
    "eatSpawnChance": "0",
    "eatSpawnType": None,
    "mushroomEatSpawnChance": "0",
    "mushroomEatSpawnType": None,
    "turnSpawnChance": "0",
    "turnSpawnType": None,
    "kittenType": None,
    # Door / mind
    "doorClosing": "NEVER",
    "stateOfMind": "ASLEEP",
    # AI memory sets
    "attackTypes": None,
    "fleeTypes": None,
    "attackAllExcept": None,
    # Abilities
    "abilities": None,
    "initialBuffs": None,
    # Player kit
    "startingInventory": None,
    "startingPerks": None,
    "actionBar": None,
    # Sprite
    "spriteCol": "0",
    "spriteRow": "0",
    "spriteW": "1",
    "spriteH": "1",
}

ITEM_DEFAULTS: dict[str, str | None] = {
    # Identity
    "type": None,
    "name": None,
    "description": None,
    "material": "MAGIC",
    # Inventory placement
    "slot": None,
    "inventoryCategory": "ITEMS",
    "silhouetteForSlot": None,
    # Spawn eligibility (level-gen)
    "minDepth": "1",
    "maxDepth": "10",
    "theme": None,
    "guaranteedPerLevel": "false",
    # Equip stats
    "damageMin": "0",
    "damageMax": "0",
    "armorMin": "0",
    "armorMax": "0",
    "lightRadius": "0",
    # Throw
    "thrownBehavior": "NOTHING",
    "tameOnThrow": None,
    # Use behaviour + effects
    "useBehavior": "NONE",
    "useVerb": None,
    "foodValue": "0",
    "healAmount": "0",
    "appliesBuff": None,
    "buffDuration": "0",
    "selfDamageBase": "0",
    "wandElement": None,
    "summonsWhenUsed": None,
    # Floor flag
    "glows": "false",
    # Sprite
    "spriteCol": "0",
    "spriteRow": "0",
}

def normalise_bool(cell: str) -> str:
    """Excel sometimes uppercases TRUE/FALSE — lowercase for consistency."""
    s = cell.strip()
    if s == "TRUE":
        return "true"
    if s == "FALSE":
        return "false"
    return cell


def fill_csv(path: Path, defaults: dict[str, str | None]) -> None:
    text = path.read_text(encoding="utf-8")
    # Strip leading comment lines (`#` at column 0) so the rewritten file
    # carries no inline docs — those live in the Java-side javadoc instead.
    lines = text.splitlines()
    body = [ln for ln in lines if not ln.lstrip().startswith("#")]
    reader = csv.reader(body)
    rows = list(reader)
    if not rows:
        return
    header = rows[0]
    out_rows: list[list[str]] = [header]
    for row in rows[1:]:
        if not row or all(not c.strip() for c in row):
            continue
        # Pad short rows so every header column is addressable.
        while len(row) < len(header):
            row.append("")
        for i, col in enumerate(header):
            cell = normalise_bool(row[i])
            if not cell.strip():
                d = defaults.get(col)
                if d is not None:
                    cell = d
            row[i] = cell
        out_rows.append(row)

    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, lineterminator="\n", quoting=csv.QUOTE_MINIMAL)
        writer.writerows(out_rows)


def main() -> None:
    fill_csv(ROOT / "assets" / "data" / "mobs.csv", MOB_DEFAULTS)
    fill_csv(ROOT / "assets" / "data" / "items.csv", ITEM_DEFAULTS)
    print("done")


if __name__ == "__main__":
    main()
