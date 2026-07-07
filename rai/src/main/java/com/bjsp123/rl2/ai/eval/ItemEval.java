package com.bjsp123.rl2.ai.eval;

import com.bjsp123.rl2.logic.BuffSystem;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.InventoryCategory;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.StatBlock;

/**
 * Item utility heuristics. Lets goals/actions answer "is this potion worth drinking
 * right now?" / "is this weapon a real upgrade over what I'm wielding?" without
 * recomputing rlib's combat math each time.
 */
public final class ItemEval {

    private ItemEval() {}

    /** Coarse "how good is this gear for {@code wearer}" score. Used by EQUIP_BETTER. */
    public static double equipmentScore(Item it, Mob wearer) {
        if (it == null) return 0.0;
        InventoryCategory c = it.inventoryCategory;
        if (c == null || !c.isEquipment()) return 0.0;
        double base = it.getValue();
        // Weapons: damage matters most. Armor: defensive value. Amulets/gems: base value.
        if (c == InventoryCategory.WEAPON || c == InventoryCategory.OFFHAND) {
            return base + it.damage * 1.5;
        }
        if (c == InventoryCategory.ARMOR) {
            return base + it.armor * 1.25;
        }
        return base;
    }

    /** True if drinking {@code it} would help {@code drinker} right now. Mirrors
     *  MobSystem.wouldDrinkHelp without the per-turn roll. */
    public static boolean wouldDrinkHelp(Mob drinker, Item it) {
        if (it == null || it.useBehavior != UseBehavior.DRINK
                && it.useBehavior != UseBehavior.APPLYBUFF) return false;
        if (it.damage > 0) return false;
        if (it.baseChargeMax > 0 && it.charge < 1f) return false;
        Buff.BuffType primary = it.primaryBuff();
        if (primary == null) return false;
        if (BuffSystem.hasBuff(drinker, Buff.BuffType.POISONED) && removesPoison(it)) return true;
        if (BuffSystem.hasBuff(drinker, primary)) return false;
        if (primary == Buff.BuffType.REGENERATION) {
            // Drink REGEN once HP loss is meaningful. Matches the utility-side
            // gate in wouldHealHelp so the action stays enumerable from the
            // point its utility crosses the threshold.
            StatBlock sb = drinker.effectiveStats();
            return drinker.hp < 0.7 * sb.maxHp;
        }
        return true;
    }

    /** True if consuming {@code it} would heal substantial HP - lower bar than
     *  wouldDrinkHelp (only fires when actually low). Covers REGENERATION potions /
     *  APPLYBUFF tools AND regen-granting food (e.g. a healing pear): eating one when
     *  hurt is a valid heal now that food's only effect is its on-eat buff. */
    public static boolean wouldHealHelp(Mob drinker, Item it) {
        if (it == null) return false;
        if (it.useBehavior != UseBehavior.DRINK && it.useBehavior != UseBehavior.APPLYBUFF
                && it.useBehavior != UseBehavior.EAT) return false;
        if (it.damage > 0) return false;
        if (it.baseChargeMax > 0 && it.charge < 1f) return false;
        if (it.appliesBuff == null) return false;
        if (!it.appliesBuff.contains(Buff.BuffType.REGENERATION)) return false;
        StatBlock sb = drinker.effectiveStats();
        return drinker.hp < 0.7 * sb.maxHp;
    }

    /** Does {@code it} carry a buff that strips POISONED on application? */
    public static boolean removesPoison(Item it) {
        return it != null && it.appliesBuff != null
                && it.appliesBuff.contains(Buff.BuffType.REGENERATION);
    }

    /** True if this is an offensive wand we could plausibly fire (charge ready, has impact buff). */
    public static boolean isFireableWand(Item it) {
        if (it == null || it.useBehavior != UseBehavior.WAND) return false;
        return it.baseChargeMax <= 0 || it.charge >= 1f;
    }

    /* ---------- stats-based item classification ---------- */
    // Every predicate below classifies an item by its data fields (useBehavior,
    // appliesBuff, throwEffect, wandEffect, inventoryCategory) - NEVER by name
    // string. See feedback_evaluate_by_stats_not_identity.md.

    /** True if {@code it} actually restores HP - REGENERATION potion / APPLYBUFF
     *  tool / regen food with REGEN, or an HP_UP powerup. Defensive / utility buffs
     *  like PHASE / SHIELDED / HASTED / INVISIBLE do NOT count - those grant
     *  survivability, not healing. */
    public static boolean isHealingItem(Item it) {
        if (it == null) return false;
        if (it.useBehavior == UseBehavior.DRINK || it.useBehavior == UseBehavior.APPLYBUFF
                || it.useBehavior == UseBehavior.EAT) {
            return it.appliesBuff != null
                    && it.appliesBuff.contains(Buff.BuffType.REGENERATION);
        }
        if (it.useBehavior == UseBehavior.POWERUP) {
            return it.wandEffect == Item.ItemEffect.HP_UP;
        }
        return false;
    }

    /** True if the powerup would have a real effect for {@code mob} right now.
     *  Delegates to the engine's {@link ItemSystem#powerupWouldApply} - the same
     *  predicate {@code applyPowerup} gates on - so the planner's "worth walking
     *  to" and the engine's "will actually consume" can never disagree. (An
     *  earlier local approximation drifted: it counted an HP pill as useful at
     *  hp fractionally below max, but the engine rounds the heal to 0 and leaves
     *  the pill, so auto-explore re-targeted it forever.) */
    public static boolean isUsefulPowerup(Item it, Mob mob) {
        if (it == null || mob == null) return false;
        if (it.useBehavior != UseBehavior.POWERUP) return false;
        return com.bjsp123.rl2.logic.ItemSystem.powerupWouldApply(mob, it);
    }

    /** True if {@code it} is a thrown smoke source - one input to the canEscapeFrom
     *  calculation. Identified by {@code throwEffect == SMOKE}. */
    public static boolean isSmokeBomb(Item it) {
        return it != null && it.throwEffect == Item.ItemEffect.SMOKE;
    }

    /** True if {@code it} can teleport the user - a TELEPORT-behaviour tool
     *  (e.g. TELEPORT_ORB) or a TELEPORT-effect wand (e.g. BLINKSTONE). Charge
     *  ready is required for the latter. */
    public static boolean isTeleportTool(Item it) {
        if (it == null) return false;
        if (it.useBehavior == UseBehavior.TELEPORT) return true;
        if (it.useBehavior == UseBehavior.WAND && it.wandEffect == Item.ItemEffect.TELEPORT) {
            return it.baseChargeMax <= 0 || it.charge >= 1f;
        }
        return false;
    }

    /** True if {@code it} is a JUMP-behaviour tool with charge ready. */
    public static boolean isReadyJumpTool(Item it) {
        if (it == null || it.useBehavior != UseBehavior.JUMP) return false;
        return it.baseChargeMax <= 0 || it.charge >= 1f;
    }

    /** True if {@code it} is an APPLYBUFF tool whose primary buff aids escape
     *  (HASTED, INVISIBLE, PHASE). Charge ready is required. */
    public static boolean isReadyEscapeBuffTool(Item it) {
        if (it == null) return false;
        if (it.useBehavior != UseBehavior.APPLYBUFF && it.useBehavior != UseBehavior.DRINK) return false;
        if (it.baseChargeMax > 0 && it.charge < 1f) return false;
        if (it.appliesBuff == null) return false;
        return it.appliesBuff.contains(Buff.BuffType.HASTED)
                || it.appliesBuff.contains(Buff.BuffType.INVISIBLE)
                || it.appliesBuff.contains(Buff.BuffType.PHASE);
    }

    /** True if {@code it} is a consumable (POTION / BOMB / FOOD) - the categories
     *  that stack and that the AI always wants to top up rather than leave on the
     *  floor. */
    public static boolean isConsumable(Item it) {
        if (it == null) return false;
        InventoryCategory c = it.inventoryCategory;
        return c == InventoryCategory.POTION
                || c == InventoryCategory.BOMB
                || c == InventoryCategory.FOOD;
    }
}
