package com.aicompanion.bot.combat;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Iterative-intercept projectile aiming (T3.4 / design 6.4 engine A). Shared by bow and
 * (later) ender pearl. Uses the REAL arrow constants confirmed from AbstractArrow.tick:
 * per tick {@code pos += vel; vel *= 0.99 (drag); vel.y -= 0.05 (gravity)}.
 *
 * <p>Launch velocity direction matches {@code Projectile.shootFromRotation}:
 * {@code vy = -sin(pitch)} (Minecraft pitch: + is down), so a solved pitch feeds
 * {@code setXRot} directly with consistent signs. Flight time comes from the SAME ballistic
 * simulation (drag-aware), not {@code dist/speed}, so the lead is not underestimated.</p>
 */
public final class ProjectileAiming {

    public static final double ARROW_SPEED = 3.0;   // full-charge bow launch speed (blocks/tick)
    public static final double DRAG = 0.99;
    public static final double GRAVITY = 0.05;
    private static final int MAX_FLIGHT_TICKS = 120;
    private static final int LEAD_ITERATIONS = 3;

    public static final class Aim {
        public final float yaw;
        public final float pitch;
        public final int flightTicks;
        public final boolean reachable;

        Aim(float yaw, float pitch, int flightTicks, boolean reachable) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.flightTicks = flightTicks;
            this.reachable = reachable;
        }
    }

    private ProjectileAiming() {
    }

    /**
     * Solve aim for a moving target: iterate lead 3× using drag-aware flight time.
     *
     * @param launchOrigin arrow spawn point (bot eyeY - 0.1)
     * @param targetPoint  the point to hit right now (e.g. target body center)
     * @param targetVel    target velocity (blocks/tick)
     */
    public static Aim solveLead(Vec3 launchOrigin, Vec3 targetPoint, Vec3 targetVel) {
        Vec3 predicted = targetPoint;
        Aim aim = solve(launchOrigin, predicted);
        for (int i = 0; i < LEAD_ITERATIONS && aim.reachable; i++) {
            predicted = targetPoint.add(targetVel.scale(aim.flightTicks));
            aim = solve(launchOrigin, predicted);
        }
        return aim;
    }

    /** Solve yaw/pitch/flight-time to hit a fixed point from launchOrigin (ballistic sim). */
    public static Aim solve(Vec3 launchOrigin, Vec3 aimPoint) {
        double dx = aimPoint.x - launchOrigin.x;
        double dz = aimPoint.z - launchOrigin.z;
        double dy = aimPoint.y - launchOrigin.y;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);

        // Bisect pitch on the direct arc. vposAtD is monotonically DECREASING in pitch
        // (pitch + = down → lower at distance; pitch - = up → higher).
        float lo = -70.0F; // aimed up (high vpos at D)
        float hi = 45.0F;  // aimed down (low vpos at D)
        Sim best = null;
        float bestPitch = 0.0F;

        Sim atLo = simulate(lo, horiz);
        if (!atLo.reached || atLo.vposAtD < dy) {
            // Even aimed steeply up it cannot reach the required height → out of range.
            return new Aim(yaw, lo, atLo.reached ? atLo.tAtD : MAX_FLIGHT_TICKS, false);
        }

        for (int it = 0; it < 24; it++) {
            float mid = (lo + hi) * 0.5F;
            Sim s = simulate(mid, horiz);
            if (!s.reached) {
                // fell short → need a higher (more negative) pitch
                hi = mid;
                continue;
            }
            best = s;
            bestPitch = mid;
            if (s.vposAtD > dy) {
                lo = mid; // too high → aim lower (increase pitch)
            } else {
                hi = mid; // too low → aim higher (decrease pitch)
            }
        }
        if (best == null) {
            return new Aim(yaw, 0.0F, MAX_FLIGHT_TICKS, false);
        }
        return new Aim(yaw, bestPitch, best.tAtD, true);
    }

    private static final class Sim {
        final boolean reached;
        final double vposAtD; // vertical position when horizontal distance D is reached
        final int tAtD;

        Sim(boolean reached, double vposAtD, int tAtD) {
            this.reached = reached;
            this.vposAtD = vposAtD;
            this.tAtD = tAtD;
        }
    }

    /**
     * 2D ballistic simulation (horizontal, vertical) with the real arrow constants.
     * Horizontal launch speed = cos(pitch)·speed, vertical = -sin(pitch)·speed
     * (matching shootFromRotation). Returns the vertical position when horizontal
     * distance D is first reached, and the tick count (drag-aware flight time).
     */
    private static Sim simulate(float pitchDeg, double distanceD) {
        double pitch = Math.toRadians(pitchDeg);
        double hvel = Math.cos(pitch) * ARROW_SPEED;
        double vvel = -Math.sin(pitch) * ARROW_SPEED;
        double hpos = 0.0;
        double vpos = 0.0;

        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            double prevH = hpos;
            double prevV = vpos;
            hpos += hvel;
            vpos += vvel;
            hvel *= DRAG;
            vvel *= DRAG;
            vvel -= GRAVITY;

            if (hpos >= distanceD) {
                // linear-interpolate the vertical position at exactly distance D
                double frac = (distanceD - prevH) / (hpos - prevH);
                double vAtD = prevV + (vpos - prevV) * frac;
                return new Sim(true, vAtD, tick);
            }
        }
        return new Sim(false, vpos, MAX_FLIGHT_TICKS);
    }
}
