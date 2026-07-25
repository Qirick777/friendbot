package com.aicompanion.bot.perception;

import com.aicompanion.bot.AICompanionBot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Fact-collection layer (T3.1 / design ch.2 & 6.2 & A.2). Every tick it snapshots what the
 * bot knows: own health/hunger, the user's state, nearby targets with measured stats, incoming
 * projectiles, fall state, and an expected-damage estimate. All mob stats are read from
 * attributes/interfaces — no name hardcoding.
 */
public class Perception {

    public static final double PERCEPTION_RANGE = 24.0;
    public static final float SAFE_FALL = 3.0F;
    private static final double ARROW_DAMAGE_ESTIMATE = 6.0; // rough incoming-arrow damage

    // --- collected facts (A.2) ---
    public float botHealth;
    public float userHealth = -1.0F;
    public int hunger;
    public float foodSaturation;
    public final List<TargetInfo> targets = new ArrayList<>();
    public final List<Projectile> incoming = new ArrayList<>();
    public float fallDistance;
    public float expectedDamage;
    public boolean userFalling;
    public boolean userInBed;

    @Nullable
    public ServerPlayer user;

    /** Collect all facts for this tick. */
    public void gather(AICompanionBot bot) {
        ServerLevel level = (ServerLevel) bot.level();
        Vec3 botPos = bot.position();
        AABB box = bot.getBoundingBox().inflate(PERCEPTION_RANGE);

        // Self.
        botHealth = bot.getHealth();
        hunger = bot.getFoodData().getFoodLevel();
        foodSaturation = bot.getFoodData().getSaturationLevel();
        fallDistance = bot.fallDistance;

        // User = nearest real player that is not the bot.
        user = nearestUser(level, bot, botPos);
        if (user != null) {
            userHealth = user.getHealth();
            userFalling = user.fallDistance > SAFE_FALL;
            userInBed = user.isSleeping();
        } else {
            userHealth = -1.0F;
            userFalling = false;
            userInBed = false;
        }

        // Targets: nearby mobs (measurement-based; class query, not name).
        targets.clear();
        // The bot is a ServerPlayer, never a Mob, so it is not part of this query.
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive())) {
            double d = Math.sqrt(mob.distanceToSqr(botPos));
            targets.add(new TargetInfo(mob, d, user));
        }
        targets.sort((a, b) -> Double.compare(a.distance, b.distance));

        // Incoming projectiles: heading toward the bot.
        incoming.clear();
        for (Projectile p : level.getEntitiesOfClass(Projectile.class, box, Entity::isAlive)) {
            // The bot's OWN arrows are not incoming threats. Without this the reflex layer treated
            // every shot the bot fired as an attack on itself and evaded continuously, which broke
            // band keeping while kiting.
            if (p.getOwner() == bot) {
                continue;
            }
            if (isHeadingToward(p, botPos)) {
                incoming.add(p);
            }
        }

        // Expected damage estimate (melee in reach targeting bot + incoming projectiles).
        float dmg = 0.0F;
        for (TargetInfo t : targets) {
            if (t.distance <= t.reach && (t.entity instanceof Mob m) && m.getTarget() == bot) {
                dmg += (float) t.attackDamage;
            }
        }
        dmg += (float) (incoming.size() * ARROW_DAMAGE_ESTIMATE);
        expectedDamage = dmg;
    }

    @Nullable
    private static ServerPlayer nearestUser(ServerLevel level, AICompanionBot bot, Vec3 botPos) {
        ServerPlayer best = null;
        double bestSq = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            if (p == bot || !p.isAlive()) {
                continue;
            }
            double sq = p.distanceToSqr(botPos);
            if (sq < bestSq) {
                bestSq = sq;
                best = p;
            }
        }
        return best;
    }

    private static boolean isHeadingToward(Projectile p, Vec3 target) {
        Vec3 vel = p.getDeltaMovement();
        if (vel.lengthSqr() < 1.0E-6) {
            return false;
        }
        Vec3 toTarget = target.subtract(p.position());
        return vel.dot(toTarget) > 0.0; // moving generally toward the bot
    }

    /** One-line dump for the /bot perception command. */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("[PERCEPTION] botHp=").append(String.format("%.1f", botHealth))
                .append(" hunger=").append(hunger).append("/").append(String.format("%.1f", foodSaturation))
                .append(" fall=").append(String.format("%.2f", fallDistance))
                .append(" expDmg=").append(String.format("%.1f", expectedDamage))
                .append(" user=").append(user == null ? "none" : String.format("hp%.1f falling=%b bed=%b",
                        userHealth, userFalling, userInBed))
                .append(" targets=").append(targets.size())
                .append(" incoming=").append(incoming.size());
        for (TargetInfo t : targets) {
            sb.append("\n  - ").append(t);
        }
        return sb.toString();
    }
}
