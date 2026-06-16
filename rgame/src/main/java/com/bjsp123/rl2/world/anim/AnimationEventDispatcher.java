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
        else if (ev instanceof GameEvent.BuffRemoved m)           animator.onBuffRemoved(level, m);
        else if (ev instanceof GameEvent.BlastEffect m)           animator.onBlastEffect(level, m);
        else if (ev instanceof GameEvent.ExplosionEffect m)       animator.onExplosionEffect(level, m);
        else if (ev instanceof GameEvent.LightMoteSpawn m)        {
            // Source-specific vertical lift (lamps emit higher than items)
            // is carried on the event so the renderer just applies it.
            Effect mote = Effect.lightMote(m.pos(), Animator.RNG);
            mote.pixelOffsetY = m.pixelOffsetY();
            animator.stage.add(mote);
        }
        else if (ev instanceof GameEvent.HearthSparkSpawn m)      {
            animator.stage.add(Effect.hearthSpark(m.pos(), Animator.RNG));
        }
        else if (ev instanceof GameEvent.WandImpactBurst m)       animator.onWandImpactBurst(level, m);
        else if (ev instanceof GameEvent.PotionBurst m)           animator.onPotionBurst(level, m);
        else if (ev instanceof GameEvent.MobSpawned m)            animator.onMobSpawned(level, m);
        else if (ev instanceof GameEvent.SurfaceChanged m)        animator.onSurfaceChanged(level, m);
        else if (ev instanceof GameEvent.VegetationChanged m)     animator.onVegetationChanged(level, m);
        else if (ev instanceof GameEvent.RainbowBurst m)          animator.onRainbowBurst(level, m);
        else if (ev instanceof GameEvent.XPGainBurst m)           animator.onXPGainBurst(level, m);
        else if (ev instanceof GameEvent.PeriodicBuffDamage m)    animator.onPeriodicBuffDamage(level, m);
        else if (ev instanceof GameEvent.LootDropped m)           animator.onLootDropped(m);
        else if (ev instanceof GameEvent.ItemCreated m)           animator.onItemCreated(m);
        else if (ev instanceof GameEvent.ItemPickedUp m)          animator.onItemPickedUp(level, m);
        else if (ev instanceof GameEvent.MobKnockedBack m)        animator.onMobKnockedBack(level, m);
        else if (ev instanceof GameEvent.MobJumped m)             animator.onMobJumped(level, m);
        else if (ev instanceof GameEvent.MobPhaseDodged m)        animator.onMobPhaseDodged(level, m);
        else if (ev instanceof GameEvent.ItemFallingIntoChasm m)  animator.onItemFallingIntoChasm(m);
        else if (ev instanceof GameEvent.MobFellThroughChasm m)   animator.onMobFellThroughChasm(m);
        else if (ev instanceof GameEvent.GrappleFired m)          animator.onGrappleFired(level, m);
        else if (ev instanceof GameEvent.MobAbilityUsed m)        animator.onMobAbilityUsed(level, m);
        else if (ev instanceof GameEvent.DoorOpened m)            animator.onDoorOpened(level, m);
        else if (ev instanceof GameEvent.DoorClosed m)            animator.onDoorClosed(level, m);
        else if (ev instanceof GameEvent.OnetimeDoorBroken m)     animator.onOnetimeDoorBroken(level, m);
        else if (ev instanceof GameEvent.BeaconActivated m)       animator.onBeaconActivated(m);
        else if (ev instanceof GameEvent.PlayerTeleportOut m)     animator.onPlayerTeleportOut(m);
        else if (ev instanceof GameEvent.PlayerTeleportIn m)      animator.onPlayerTeleportIn(m);
        else if (ev instanceof GameEvent.InwardSpiralSpawn m)     {
            // Beacons emit BOTH a spiral particle and a co-located light
            // mote (both lifted 42 px above the sprite base) so the two
            // ambient streams share an origin around the lit upper half
            // of the beacon. Plain lamps still get LightMoteSpawn on its
            // own tile-anchored cadence.
            animator.stage.add(Effect.inwardSpiralParticle(m.pos(), Animator.RNG));
            animator.stage.add(Effect.beaconLightMote(m.pos(), Animator.RNG));
        }
    }
}
