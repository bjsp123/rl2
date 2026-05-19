package com.bjsp123.rl2.world.anim;

import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.world.render.Effect;

/** Dispatches drained game events to the Animator's visual handlers. */
final class AnimationEventDispatcher {
    private AnimationEventDispatcher() {}

    static void dispatch(Animator animator, Level level, GameEvent ev) {
        if      (ev instanceof GameEvent.MobMoved m)              animator.onMobMoved(level, m);
        else if (ev instanceof GameEvent.MobMeleeAttacked m)      animator.onMobMeleeAttacked(level, m);
        else if (ev instanceof GameEvent.SurpriseAttack m)        animator.onSurpriseAttack(level, m);
        else if (ev instanceof GameEvent.MobHitFlinched m)        animator.onMobHitFlinched(level, m);
        else if (ev instanceof GameEvent.MobKilled m)             animator.onMobKilled(level, m);
        else if (ev instanceof GameEvent.MobTeleported m)         animator.onMobTeleported(m);
        else if (ev instanceof GameEvent.MagicMissileFired m)     animator.onMagicMissileFired(level, m);
        else if (ev instanceof GameEvent.WandMissileFired m)      animator.onWandMissileFired(level, m);
        else if (ev instanceof GameEvent.WandRayFired m)          animator.onWandRayFired(level, m);
        else if (ev instanceof GameEvent.ItemThrown m)            animator.onItemThrown(level, m);
        else if (ev instanceof GameEvent.DamageDealt m)           animator.onDamageDealt(level, m);
        else if (ev instanceof GameEvent.HealApplied m)           animator.onHealApplied(level, m);
        else if (ev instanceof GameEvent.MobTamed m)              animator.onMobTamed(level, m);
        else if (ev instanceof GameEvent.BuffApplied m)           animator.onBuffApplied(level, m);
        else if (ev instanceof GameEvent.BuffRemoved m)           { /* no visual */ }
        else if (ev instanceof GameEvent.BlastEffect m)           animator.onBlastEffect(level, m);
        else if (ev instanceof GameEvent.ExplosionEffect m)       animator.onExplosionEffect(level, m);
        else if (ev instanceof GameEvent.LightMoteSpawn m)        animator.stage.add(Effect.lightMote(m.pos(), Animator.RNG));
        else if (ev instanceof GameEvent.WandImpactBurst m)       animator.onWandImpactBurst(level, m);
        else if (ev instanceof GameEvent.PotionBurst m)           animator.onPotionBurst(level, m);
        else if (ev instanceof GameEvent.MobSpawned m)            animator.onMobSpawned(level, m);
        else if (ev instanceof GameEvent.SurfaceChanged m)        animator.onSurfaceChanged(level, m);
        else if (ev instanceof GameEvent.VegetationChanged m)     animator.onVegetationChanged(level, m);
        else if (ev instanceof GameEvent.RainbowBurst m)          animator.onRainbowBurst(level, m);
        else if (ev instanceof GameEvent.XPGainBurst m)           animator.onXPGainBurst(level, m);
        else if (ev instanceof GameEvent.PeriodicBuffDamage m)    animator.onPeriodicBuffDamage(level, m);
        else if (ev instanceof GameEvent.LootDropped m)           animator.onLootDropped(m);
        else if (ev instanceof GameEvent.ItemPickedUp m)          animator.onItemPickedUp(level, m);
        else if (ev instanceof GameEvent.MobKnockedBack m)        animator.onMobKnockedBack(level, m);
        else if (ev instanceof GameEvent.MobJumped m)             animator.onMobJumped(level, m);
        else if (ev instanceof GameEvent.ItemFallingIntoChasm m)  animator.onItemFallingIntoChasm(m);
        else if (ev instanceof GameEvent.MobFellThroughChasm m)   animator.onMobFellThroughChasm(m);
        else if (ev instanceof GameEvent.GrappleFired m)          animator.onGrappleFired(level, m);
        else if (ev instanceof GameEvent.MobAbilityUsed m)        animator.onMobAbilityUsed(level, m);
        else if (ev instanceof GameEvent.DoorOpened m)            animator.onDoorOpened(level, m);
        else if (ev instanceof GameEvent.DoorClosed m)            animator.onDoorClosed(level, m);
        else if (ev instanceof GameEvent.OnetimeDoorBroken m)     animator.onOnetimeDoorBroken(level, m);
    }
}
