package data.scripts.jonahsautooutfitting;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The scoring math, with no game classes in it so the offline check can run it on the research data.
 * Formulas: docs/design.md 6-7 (research mechanics_report.md 1-3, 8; hullmods report section 8).
 *
 * A build's score = sqrt(offense x survival) x small factors, where offense = 1 / (weighted time to kill the
 * reference targets: break the shield, strip the armor, kill the hull) and survival = effective HP.
 */
final class JAOModel {
    static final int KIN = 0, HE = 1, FRAG = 2, EN = 3;
    static final float[] SHIELD_MULT = {2f, 0.5f, 0.25f, 1f}, ARMOR_MULT = {0.5f, 2f, 0.25f, 1f};
    /** Weapon bonus classes (ballistic, energy, missile), as the ship stats split them. */
    static final int BAL = 0, ENE = 1, MIS = 2;
    static final float MAX_DR = 0.85f, MIN_ARMOR = 0.05f, ENGAGE_SECONDS = 30f, REACH_SAT = 1100f;
    static final float[] KA = {10f, 15f, 20f, 25f};
    /**
     * Reference incoming fire for the survival term (0.5.14): {damage type, hit strength, share}. Kinetic guns, HE guns,
     * heavy HE (torpedoes, Hellbores), energy, fragmentation small arms.
     */
    static final float[][] INCOMING_MIX = {{KIN, 120f, 0.30f}, {HE, 350f, 0.20f}, {HE, 1000f, 0.10f}, {EN, 200f, 0.30f}, {FRAG, 60f, 0.10f}};
    /** Keeps the average vanilla hull's armor-and-hull survival where the old linear term had it (234 hulls). */
    static final float ARMOR_EHP_SCALE = 0.614f;

    /**
     * Armor and hull as effective hit points against INCOMING_MIX (0.5.14). The armor pool (KA x rating, standing for the
     * grid's cells) wears down while each hit is cut by the armor still standing, hit / (hit + armor), floor 15%; then
     * the hull takes hits through the 5% that never goes. The old term (hull + KA x armor) was linear, so armor only
     * counted as hit points: Heavy Armor went on 7% of the experts' hulls against their 28%, and armor's real worth, the
     * small arms it shrugs off, never showed. Now Heavy Armor counts most on hulls that are already well armored.
     */
    static float armorHullEhp(Ship s) {
        float a0 = Math.max(0f, s.armor), at = Math.max(0.1f, s.armorTaken), ht = Math.max(0.1f, s.hullTaken);
        float pool = KA[Math.max(0, Math.min(3, s.size))] * a0, t = 0f;
        int steps = 10;
        if (pool > 0f) {
            for (int k = 0; k < steps; k++) {
                float cur = a0 * (1f - (k + 0.5f) / steps * (1f - MIN_ARMOR));
                float rate = 0f;
                for (float[] m : INCOMING_MIX) {
                    int dt = (int) m[0];
                    rate += m[2] * ARMOR_MULT[dt] * armorFactor(m[1] * ARMOR_MULT[dt], cur, s.maxDr);
                }
                t += pool / steps / Math.max(1e-4f, rate * at);
            }
        }
        float floor = a0 * MIN_ARMOR, hr = 0f;
        for (float[] m : INCOMING_MIX) {
            int dt = (int) m[0];
            hr += m[2] * armorFactor(m[1] * ARMOR_MULT[dt], floor, s.maxDr);
        }
        float perHit = ARMOR_EHP_SCALE * (t + s.hull / Math.max(1e-4f, hr * ht));
        float linear = s.hull / ht + KA[Math.max(0, Math.min(3, s.size))] * a0 / at;
        // phase ships survive by not being hit, not by tanking (0.5.15: the per-hit curve put Heavy Armor on Dooms,
        // Harbingers and Ziggurats, where experts take Phase Anchor or Adaptive Phase Coils, Hardened Subsystems, caps)
        if (s.phase) return linear;
        return ARMOR_SHAPE * perHit + (1f - ARMOR_SHAPE) * linear;
    }

    /**
     * Weight of the per-hit armor curve against the old linear term. 1 put Heavy Armor on 43% of the experts' hulls
     * (their builds: 28%; the linear term alone: 7%), phase ships included.
     */
    static final float ARMOR_SHAPE = 0.5f;
    /** Point defense a ship needs: about 1 / 2 / 3 / 4.5 Burst PD lasers (research weapon_eval d.13; was 3.6x that). */
    /**
     * Point defense floor by hull size (dps x hit share x arc x reach x pdKill). 0.5.11 tried 60/130/250/400 (about
     * 1/2/3/5 Burst PD) and got PD-filled destroyers, the user's 11/02 complaint, so it stays 35/65/100/150; pdKill
     * and PD_EXTRA make Burst PD win the PD mounts instead.
     */
    static final float[] PD_NEED = {35f, 65f, 100f, 150f};
    /** Score for point defense past the floor, at three times the floor. */
    static final float PD_EXTRA = 0.04f;
    static final float OFFENSE_W = 0.6f;
    /** Speed counts for every role at least this much (exponent on speed against the hull's own). */
    static final float MIN_MOBILITY = 0.1f;
    /** Top speed and handling against the hull's own count up to these (more than that buys nothing in a fight). */
    static final float SPEED_CAP = 1.5f, MANEUVER_CAP = 2f;
    /** How much of a poor shot's damage still lands on a frigate / destroyer / cruiser / capital. */
    static final float[] SIZE_HIT = {0.45f, 0.7f, 0.88f, 1f};
    /**
     * Burst windows: share of armor and hull damage dealt while the target can't block (shield down, overloaded,
     * flanked), the capacity spent on them and how long they last. Load over budget the AI won't fire even then.
     */
    /**
     * BURST_CAP: share of capacity the AI spends opening every group in burst windows; 0.4 since 0.5.11 (the "2x budget"
     * rule only holds while the ship has no soft flux; research ai_behaviour_1106 SF-3). AUTOFIRE_CAP: share of capacity
     * over-budget autofire uses up (it stops at a flux level of 0.2-0.5), more on frigates and destroyers. SHIELD_USABLE:
     * shields don't come back up past 80% flux, and omni shields drop there (SF-1).
     */
    static final float AUTOFIRE_CAP = 0.3f, AUTOFIRE_CAP_FAST = 0.45f, SHIELD_USABLE = 0.8f;
    /**
     * Share of a fight the shield is up, for its upkeep (0.5.11). Counting upkeep as always on put Stabilized Shields on
     * 54% of hulls in the reference run; experts take it where upkeep is high (Paragon, Conquest, Eagle, Aurora; break-even
     * 60/120/180/300 flux/s by size at full uptime, builds_update_1106 4.2).
     */
    static final float SHIELD_UPTIME = 0.6f;
    /**
     * Seconds of dissipation a shield gets back inside one exchange of fire. 0.5.15 tried 20 (research meta_report 1:
     * Flux Distributor beats capacitors only past about 27 s): Flux Distributor barely moved (61% -> 57% of the experts'
     * hulls, theirs 19%) and Stabilized Shields fell below the experts' rate, so it stays 30.
     */
    static final float SHIELD_REGEN_SECONDS = 30f;
    /**
     * Hard damage a ship of each size takes per second while engaged (research ai_behaviour_1106 SF-6: 150 / 300 / 500 /
     * 800), and the share of it its shield blocks (the rest hits armor while the shield is down or flanked, or misses).
     */
    static final float[] INCOMING = {150f, 300f, 500f, 800f};
    static final float SHIELD_BLOCK = 0.35f;
    /** Hard flux level the AI backs off at to vent, and seconds lost per break besides the vent itself. */
    static final float DUTY_HARD = 0.6f, DUTY_OVERHEAD = 5f;

    /**
     * Share of a fight a shielded ship stays engaged (0.5.13, research ai_behaviour_1106 SF-6): incoming fire fills its
     * shield's flux to DUTY_HARD in t_e = DUTY_HARD x capacity / incoming flux, then it backs off and vents for t_r =
     * DUTY_HARD x capacity / venting rate (2x dissipation x vent rate; Safety Overrides can't vent, so plain dissipation)
     * + DUTY_OVERHEAD. Hardened Shields fill it more slowly, capacitors give more room, dissipation and Resistant Flux
     * Conduits shorten the break. Experts call Hardened Shields a safe default on every shielded ship and say the AI
     * needs some capacitors (Grievous69's 0.98 guide); the model had valued Hardened Shields only as shield HP. Charging
     * incoming fire against the guns' flux budget instead (tried first) pushed every fit to flux-cheap weapons.
     */
    static float duty(Ship s, float diss) {
        if (!s.shield) return 1f;
        float hold = DUTY_HARD * Math.max(1f, s.cap);
        // Field Modulation elite: part of the dissipation drains hard flux while the shield is up
        float in = incomingShieldFlux(s);
        float te = hold / Math.max(1f, Math.max(0.2f * in, in - s.hardDiss * Math.max(0f, diss)));
        float vent = s.noVent ? Math.max(1f, diss) : Math.max(1f, 2f * diss * Math.max(0.1f, s.ventRate));
        float tr = hold / vent + DUTY_OVERHEAD;
        return te / (te + tr);
    }

    /** Flux per second the shield takes from incoming fire: what the guns can't spend (Hardened Shields x0.8). */
    static float incomingShieldFlux(Ship s) {
        if (!s.shield) return 0f;
        return INCOMING[Math.max(0, Math.min(3, s.size))] * SHIELD_BLOCK * Math.max(0.05f, s.shieldEff * s.shieldTaken);
    }
    /** Ship systems the AI fires whenever their weapons are in range: Accelerated Ammo Feeder, High Energy Focus (SYS-2/3). */
    static final float AMMO_FEED = 1.29f, ENERGY_FOCUS = 1.075f;
    static final float BURST_SHARE = 0.5f, BURST_CAP = 0.4f, BURST_SECONDS = 10f, FLUX_LIMIT = 1.75f, FLUX_SOFT = 1.4f, FLUX_SOFT_EXP = 0.3f;
    /** Fighter wing roles. */
    static final int W_FIGHTER = 0, W_BOMBER = 1, W_INTERCEPTOR = 2, W_SUPPORT = 3;
    /**
     * The mix of fighter bays a fleet aims for: fighters for sustained pressure, bombers to finish armor, support for
     * battlecarriers, a few interceptors (players rarely put them on carriers; they do better on gunships). Fighters are
     * multi-role, so no single role should fill every bay (the user's fleet had Gunheads in every carrier bay).
     * Research build_philosophy R1.7: the average of the carrier loadouts players post.
     */
    static final float[] WING_MIX = {0.35f, 0.35f, 0.10f, 0.20f};
    /**
     * A carrier with any bomber never sends its fighters to escort (the game's fighter AI; also hulls with
     * NO_AUTO_ESCORT, like the Legion), so interceptors there lose their point defense job. A lone bomber wing on a
     * one-bay ship has no escort. Research R1.8.
     */
    static final float INTERCEPTOR_NO_ESCORT = 0.4f, LONE_BOMBER = 0.7f;
    /** Share of a carrier's ranged wings that must be fighters, interceptors or support for it to escort (CF-1). */
    static final float ESCORT_SHARE = 0.66f;
    /**
     * Wing value. Paper numbers (fighter guns as if they always fired, a mining drill as constant fire, torpedoes spread
     * over the refit cycle) were off by up to 100x: a 9-OP Gunhead scored 210 per OP, a Trident 2. So a wing is worth
     * WING_VALUE_PER_OP times its OP (what the designers priced it at), times its quality against its role's reference
     * wing on four axes (WING_AXES). User 11/03: the Broadsword is the S-tier all-rounder fighter (tough, cheap, quick to
     * refit, guns that also shoot missiles and fighters), so it is the fighters' yardstick: a wing can match it on an
     * axis but not beat it, and every axis it falls short on costs value. Specialists still win their own jobs through
     * the role balance (wingRoleFactor) and the fleet's needs.
     */
    static final float WING_VALUE_PER_OP = 20f, WING_Q_MIN = 0.5f;
    /**
     * Axis weights: firepower per OP (bombers: armor damage or shield breaking, whichever is larger), durability per OP,
     * point defense, sortie tempo (refit plus the trip out and back: players rate slow wings down).
     */
    static final float[] WING_AXES = {0.40f, 0.25f, 0.20f, 0.15f};
    /**
     * Reference wings per role (fighter, bomber, interceptor, support), first one loaded wins; none loaded = the role's
     * 75th percentile. The community's top picks (research build_philosophy 1.3): Broadsword (the user's S-tier
     * all-rounder), Dagger, Spark or Wasp, Xyphos or Sarissa.
     */
    static final String[][] WING_REF = {{"broadsword_wing"}, {"dagger_wing"}, {"spark_wing", "wasp_wing"}, {"xyphos_wing", "sarissa_wing"}};

    /**
     * Share of a wing's finite payload that lands: guided 1.0, poorly guided (Atropos, Sabot) 0.8, unguided torpedoes
     * and rockets 0.55, drifting bombs 0.35 (research R1.3).
     */
    static float payloadReliability(boolean bomb, boolean guided, boolean guidedPoor, float projSpeed) {
        if (guided) return 1f;
        if (guidedPoor) return 0.8f;
        if (bomb || projSpeed < 200f) return 0.35f;
        return 0.55f;
    }

    /**
     * A wing's weapons summed into wingQuality's axes (JAORatings.wingRaw in game, the bench offline). Fighters spend
     * most of a fight flying to and from the target and losing craft, so their guns count at ENGAGE of their paper rate
     * (melee weapons, like the ARMAA Gunhead's mining drill, at a third of that again; EMP at half), and finite missiles
     * land once per sortie (refit + 25 s) times how reliably they hit (payloadReliability). Bombers are rated on armor
     * damage (damage-type multiplier x hit / (hit + 500)) or shield breaking (Longbow's Sabots), whichever is larger.
     * Durability is hull + 2x armor per craft (x1.25 with a shield) per OP. Tempo is the refit plus a 2000-unit trip out
     * and back for wings that leave the carrier.
     */
    static final class WingParts {
        static final float ENGAGE = 0.35f, MELEE = 0.35f;
        float dps, hitSum, best = -1f, armorDps, shieldDps, pdDps;
        int dmg = EN;

        static float cycle(float refit) { return Math.max(10f, refit) + 25f; }

        void weapon(boolean finite, float perShot, int ammo, float cycle, float reliability, float sustDps, float empDps, float range,
                    float hit, int type, boolean pd) {
            float wd = finite ? perShot * Math.max(1, ammo) / cycle * reliability
                    : (sustDps + 0.5f * empDps) * ENGAGE * (range < 150f ? MELEE : 1f);
            dps += wd;
            hitSum += wd * hit;
            armorDps += wd * ARMOR_MULT[type] * hit / (hit + 500f);
            shieldDps += wd * SHIELD_MULT[type] * 0.5f;
            if (pd) pdDps += wd;
            if (wd > best) { best = wd; dmg = type; }
        }

        float hit() { return dps > 0f ? hitSum / dps : 0f; }
        float pdShare() { return dps > 0f ? pdDps / dps : 0f; }

        /** hp = hull + 2 x armor per craft; range = the wing's engagement range. */
        float[] axes(int role, int n, float op, float hp, boolean shield, float refit, float range, float speed) {
            float o = Math.max(1f, op), travel = range >= 2000f ? 4000f / Math.max(50f, speed) : 0f;
            return new float[]{(role == W_BOMBER ? Math.max(armorDps, shieldDps) : dps) * n / o, n * hp * (shield ? 1.25f : 1f) / o,
                    0.4f + 0.6f * pdShare(), 1f / (Math.max(5f, refit) + travel)};
        }
    }

    /** raw and ref: {firepower per OP, durability per OP, 0.4 + 0.6 x PD share, 1 / sortie seconds}. */
    static float wingQuality(float[] raw, float[] ref) {
        float q = 1f;
        for (int i = 0; i < WING_AXES.length; i++) {
            if (ref == null || ref[i] <= 0f) continue;
            float r = raw[i] > 0f ? Math.min(1f, raw[i] / ref[i]) : 0.5f;
            q *= (float) Math.pow(r, WING_AXES[i]);
        }
        return clamp(q, WING_Q_MIN, 1f);
    }

    static float wingValue(float op, float[] raw, float[] ref) {
        return WING_VALUE_PER_OP * Math.max(1f, op) * wingQuality(raw, ref);
    }

    /**
     * A 0-OP wing (Mining Pods, the Dustkeepers' Brattice, Dajeil drones) has no price to rate it at. Until 0.5.16 it
     * was rated as if it cost 10 OP, so a free Mining Pod scored about like a Broadsword and took bays on real carriers
     * (user 11/06: "they do pretty much nothing"). Its fair price is the most OP at which it still matches its role's
     * reference wing per OP on both firepower and durability, between 1 and FREE_WING_MAX_OP: a Mining Pod's four
     * mining lasers are worth about 2 OP of Xyphos. abs = WingParts.axes at op 1 (absolute firepower and durability).
     */
    static final float FREE_WING_MAX_OP = 10f;

    static float freeWingOp(float[] abs, float[] ref) {
        float op = FREE_WING_MAX_OP;
        if (ref != null) for (int i = 0; i < 2; i++) if (ref[i] > 0f) op = Math.min(op, abs[i] / ref[i]);
        return clamp(op, 1f, FREE_WING_MAX_OP);
    }

    /** WingParts.axes taken at op 1, per OP at this price. */
    static float[] perOp(float[] abs, float op) {
        float[] out = abs.clone();
        out[0] /= Math.max(1f, op);
        out[1] /= Math.max(1f, op);
        return out;
    }

    /**
     * Drones that never leave the carrier (wing range 0 or stay_in_front_of_ship) and carry only point-defense guns
     * (Mining Pods): point defense on the front of the ship, so they count like PD weapons, mostly against missiles
     * and fighters and against ships a quarter of the time (research ai_behaviour_1106 CF-5).
     */
    static boolean pdDrone(boolean home, int role, float pdShare) {
        return home && role != W_INTERCEPTOR && pdShare >= 0.99f;
    }

    /** Per column, the value at fraction p of the sorted rows; null when there are none. */
    static float[] percentile(List<float[]> rows, float p) {
        if (rows == null || rows.isEmpty()) return null;
        int cols = rows.get(0).length;
        float[] out = new float[cols];
        float[] col = new float[rows.size()];
        for (int c = 0; c < cols; c++) {
            for (int i = 0; i < rows.size(); i++) col[i] = rows.get(i)[c];
            Arrays.sort(col);
            out[c] = col[Math.round(clamp(p, 0f, 1f) * (col.length - 1))];
        }
        return out;
    }

    /**
     * How much a wing of this role is worth given the bays already filled by role (the rest of the fleet plus this
     * ship, this wing included): roles past their share of WING_MIX lose value, missing ones gain. Strong enough to
     * beat a wing's OP: a 26-OP bomber is worth three 8-OP fighters per bay, and at 0.8 / 0.6-1.3 the user's carriers
     * still filled 22 of 34 bays with bombers (JAOFleetSim, 11/05); at 3 / 0.4-1.6, 15 bombers, 10 fighters, 8 support
     * and an interceptor, two bombers and a fighter or support wing per Legion.
     */
    static float WING_PULL = 3f, WING_FACTOR_MIN = 0.4f, WING_FACTOR_MAX = 1.6f;

    static float wingRoleFactor(int role, float[] bays) {
        float total = 0f;
        for (float b : bays) total += b;
        if (total <= 0f) return 1f;
        float share = bays[Math.max(0, Math.min(3, role))] / total;
        return clamp(1f + WING_PULL * (WING_MIX[Math.max(0, Math.min(3, role))] - share), WING_FACTOR_MIN, WING_FACTOR_MAX);
    }

    /** Per weapon group past Starsector's 7, and per distinct gun type past three. */
    static final float GROUP_OVER = 0.97f, GUN_VARIETY = 0.985f;
    /** Score lost at full gun damage outside +-200 of the main battery's range. */
    static final float OFF_BAND = 0.25f;

    /** One weapon (or fighter wing) before ship bonuses. */
    static final class W {
        String id, name;
        int size, cls, dmg = EN;
        boolean beam, soft, pd, pdOnly, pdAlso, strike, guided, guidedPoor, fireWhenInefficient, wing, interceptor, bomber;
        /** Wings: W_FIGHTER / W_BOMBER / W_INTERCEPTOR / W_SUPPORT. */
        int wingRole;
        /** Wings: share of their firepower that can shoot missiles and fighters (Broadsword's machine guns), for PD. */
        float wingPd;
        /** Sustained damage/s, flux/s, damage per hit, base range; pool = total damage of finite ammo (0 = unlimited). */
        float dps, flux, hit, range, pool, burst, emp;
        float projSpeed = 1000f, spread;
        /** Turret turn rate, degrees per second (0 = unknown: no tracking limit). */
        float turnRate;
        /** Scripted extras (jao_weapon_effects.csv). */
        float shieldMultExtra = 1f, armorExtraPerHit, hitsPerSec, arcDps, shieldDebuff = 1f;
        /** Scripted extra damage per hit (explosions, applyDamage, fragments; 0.5.19): extra damage per point of the
         *  weapon's own damage, the size of one extra hit, its damage type, and whether it also lands on shields. */
        float extraFrac, extraHit;
        int extraDmg = EN;
        boolean extraShields;
        /** 0..1 rarity score (only used with Prefer rare weapons). */
        float rarity;
        /** MagicLib interference: dissipation lost per other interference weapon on the ship (20/40/80). */
        float interference;
        /** Seeker's nano-plague: % of max CR one fitted copy costs (3/6/12), scaled by the ship's plagueMult. */
        float crCost;
        /** Finite ammo: damage per second while it has ammo (0 = unknown); a fight gets no more of the pool than this x its length. */
        float poolRate;
        /** Its missiles hit friendly ships (unguided rockets and torpedoes; JAORatings). */
        boolean ff;
        /** Community AI rating as a value multiplier (JAORatings.prior; 1 = none). */
        float prior = 1f;
        /** Missiles: hit points of one missile (enemy point defense kills the weak ones; 0 = unknown). */
        float projHP;
        /** Guns with recharging ammo (Autopulse, Thermal Pulse Cannon, Storm Needler, Burst PD): dps while the charges last,
         *  charges, and charges regained per second. dps above is the recharge-limited rate. 0 = none. */
        float burstDps, ammoMax, regen;
        boolean ownedOnly;
    }

    static final class Target {
        int size;
        float armor, hull, cap, diss, shieldEff, weight = 1f;
        /** Range its main battery fights at, range hullmods included (stock loadouts: about 660/800/1000/1250). */
        float threat = 800f;
        boolean shield;
    }

    /** Ship numbers after hullmods and skills (scratch fleet member), without vents and capacitors. */
    static final class Ship {
        int size;
        float hull, armor, cap, diss, upkeep, shieldEff = 1f, arc;
        boolean shield, phase;
        float speed, baseSpeed, ppt;
        /** Turning and acceleration against the hull's own (1 = unchanged; Heavy Armor's s-mod penalty is 0.75). */
        float maneuver = 1f;
        /** The ship's own max turn rate, degrees per second (0 = unknown), and the hull's before hullmods. */
        float turnRate, baseTurn;
        /** The hull's own flux capacity (phase ships: what capacitors add to it lets them phase longer). 0 = unknown. */
        float baseCap;
        int vents, caps, maxVents, maxCaps;
        float[] rangePct = new float[3], rangeFlat = new float[3], rangeMult = {1f, 1f, 1f};
        float beamPct, beamFlat, beamMult = 1f, threshold = Float.MAX_VALUE, pastThreshold = 1f;
        float[] dmgMult = {1f, 1f, 1f}, rofMult = {1f, 1f, 1f}, fluxMult = {1f, 1f, 1f};
        float beamDmgMult = 1f, beamFluxMult = 1f, ammoMult = 1f, pdDmg = 1f, armorTaken = 1f, hullTaken = 1f, shieldTaken = 1f;
        /** Base-range adders from listener hullmods (Ballistic Rangefinder), High Scatter Amp. */
        float bfSmall, bfMedium, bfCap;
        boolean hsa;
        /** Fractional bonus for effects the model can't see (table rows only). */
        float extra;
        /** Interference penalty multiplier (Resistant Flux Conduits halve it). */
        float interferenceMult = 1f;
        /** Can't vent (Safety Overrides). */
        boolean noVent;
        /** 0 steady, 1 aggressive/reckless, 2 cautious/timid. */
        int personality;
        /** The hull never sends fighters to escort (NO_AUTO_ESCORT, e.g. the Legion). */
        boolean noAutoEscort;
        /** Base range added to weapons whose id starts with "valk" (Valkyrian Bryncore: a combat-only listener). */
        float valkRange;
        /** Where flux pressure starts and where extra guns sit switched off (JAOShip: SO and Valhalla Core run hot). */
        float fluxSoft = FLUX_SOFT, fluxLimit = FLUX_LIMIT;
        /** Share of plague CR costs this hull pays: 0 on Seeker's plague hulls, 0.666 with Hardened Subsystems. */
        float plagueMult = 1f;
        /**
         * Max combat readiness (0-1) before plague weapons: the game's 70% with skills, hullmods and d-mods on it
         * (Degraded Life Support, Faulty Automated Systems and Increased Maintenance take 5 points each).
         */
        float maxCr = CR_BASE;
        /** CR one deployment spends (0-1; 0 = only the first deployment counts). */
        float deployCr;
        /**
         * The ship's CR thresholds (CRPluginImpl, read through the game's CR plugin with the ship's stats; a stat can
         * move them): weapon and engine malfunctions below malfAt, critical malfunctions below critAt, worse speed,
         * damage and damage taken below degradeAt.
         */
        float malfAt = CR_MALF, critAt = CR_CRIT, degradeAt = CR_DEGRADE;
        /**
         * Carrier deck: fighter wings' value on this ship (deckFactor): refit time (Damaged Flight Deck +30% in 0.98a,
         * +25% in 0.98.5a), replacement rate (Expanded Deck Crew), engagement range (Malfunctioning Comms x0.6 in 0.98a,
         * x0.7 in 0.98.5a), fighter d-mods (Defective Manufactory). All read from the live stats. 1 = as the wing's own numbers.
         */
        float deck = 1f;
        /** Defensive Targeting Array (wing engagement range 0): every wing stays at the carrier (CF-4). */
        boolean dta;
        /** Turret turn rate and spread against the weapons' own (Damaged Weapon Mounts 0.75 / 1.3, Advanced Turret Gyros 1.75). */
        float turnMult = 1f, beamTurnMult = 1f, recoilMult = 1f;
        /** Weapon malfunction chance outside combat readiness (Ill-Advised Modifications 0.05). */
        float malfunction;
        /** Ballistic / energy charge capacity and recharge rate (Expanded Magazines +50% / s-mod +50% recharge). */
        float ammoBal = 1f, ammoEn = 1f, regenBal = 1f, regenEn = 1f;
        /** EMP damage taken and flux dissipation while venting (Resistant Flux Conduits 0.5 / x1.25). */
        float empTaken = 1f, ventRate = 1f;
        /**
         * Captain skills (0.5.29, JAOShip.applyCaptain). Flux dissipation and capacity per OP spent on weapons and wings
         * (Ordnance Expertise 1.5 / elite 20; ordnance() adds them for the fit's OP).
         */
        float fluxPerWeaponOp, capPerWeaponOp;
        /** Damage to frigates / destroyers / cruisers / capitals (Target Analysis), damage to fighters (Point Defense). */
        float[] sizeDmg = {1f, 1f, 1f, 1f};
        float fighterDmg = 1f;
        /** Flat range added to point-defense weapons, projectile and beam (Point Defense elite +200). */
        float pdRange, pdBeamRange;
        /** Missile hit points (Missile Specialization +25%). */
        float missileHp = 1f;
        /**
         * Energy weapon damage at full flux (Energy Weapon Mastery 0.3), full up to ewmNear and none past ewmFar
         * (600 / 1000). Scored at EWM_FLUX.
         */
        float ewm, ewmNear = 600f, ewmFar = 1000f;
        /** Share of dissipation that drains hard flux while the shield is up (Field Modulation elite 0.2). */
        float hardDiss;
        /** Most damage armor can block (85%; Polarized Armor 90%). */
        float maxDr = MAX_DR;
        /** Combat readiness lost per second past peak time (Combat Endurance x0.75; Fragile Subsystems more). */
        float crLoss = 1f;

        Ship copy() {
            Ship s = new Ship();
            s.size = size; s.hull = hull; s.armor = armor; s.cap = cap; s.diss = diss; s.upkeep = upkeep; s.shieldEff = shieldEff;
            s.arc = arc; s.shield = shield; s.phase = phase; s.speed = speed; s.baseSpeed = baseSpeed; s.maneuver = maneuver; s.ppt = ppt; s.vents = vents; s.caps = caps;
            s.maxVents = maxVents; s.maxCaps = maxCaps; s.rangePct = rangePct.clone(); s.rangeFlat = rangeFlat.clone();
            s.rangeMult = rangeMult.clone(); s.beamPct = beamPct; s.beamFlat = beamFlat; s.beamMult = beamMult;
            s.threshold = threshold; s.pastThreshold = pastThreshold; s.dmgMult = dmgMult.clone(); s.rofMult = rofMult.clone();
            s.fluxMult = fluxMult.clone(); s.beamDmgMult = beamDmgMult; s.beamFluxMult = beamFluxMult; s.ammoMult = ammoMult;
            s.pdDmg = pdDmg; s.armorTaken = armorTaken; s.hullTaken = hullTaken; s.shieldTaken = shieldTaken; s.bfSmall = bfSmall;
            s.bfMedium = bfMedium; s.bfCap = bfCap; s.hsa = hsa; s.extra = extra; s.personality = personality;
            s.interferenceMult = interferenceMult; s.noVent = noVent;
            s.noAutoEscort = noAutoEscort; s.valkRange = valkRange; s.fluxSoft = fluxSoft; s.fluxLimit = fluxLimit;
            s.turnRate = turnRate; s.baseTurn = baseTurn; s.baseCap = baseCap; s.plagueMult = plagueMult; s.maxCr = maxCr; s.deployCr = deployCr; s.deck = deck; s.dta = dta;
            s.malfAt = malfAt; s.critAt = critAt; s.degradeAt = degradeAt;
            s.turnMult = turnMult; s.beamTurnMult = beamTurnMult; s.recoilMult = recoilMult; s.malfunction = malfunction;
            s.ammoBal = ammoBal; s.ammoEn = ammoEn; s.regenBal = regenBal; s.regenEn = regenEn; s.empTaken = empTaken; s.ventRate = ventRate;
            s.fluxPerWeaponOp = fluxPerWeaponOp; s.capPerWeaponOp = capPerWeaponOp; s.sizeDmg = sizeDmg.clone(); s.fighterDmg = fighterDmg;
            s.pdRange = pdRange; s.pdBeamRange = pdBeamRange; s.missileHp = missileHp; s.ewm = ewm; s.ewmNear = ewmNear; s.ewmFar = ewmFar;
            s.hardDiss = hardDiss; s.maxDr = maxDr; s.crLoss = crLoss;
            return s;
        }
    }

    /**
     * The ship with Ordnance Expertise's flux for weaponOp ordnance points of weapons and wings (built-ins excluded, as
     * ShipVariantAPI.computeWeaponOPCost counts them). The same ship when its captain doesn't have the skill.
     */
    static Ship ordnance(Ship base, float weaponOp) {
        if ((base.fluxPerWeaponOp <= 0f && base.capPerWeaponOp <= 0f) || weaponOp <= 0f) return base;
        Ship s = base.copy();
        s.diss += s.fluxPerWeaponOp * weaponOp;
        s.cap += s.capPerWeaponOp * weaponOp;
        return s;
    }

    /** Average flux level Energy Weapon Mastery is scored at (the AI fights at about half flux). */
    static final float EWM_FLUX = 0.5f;

    /** Energy Weapon Mastery's share of its bonus at this firing distance: all of it up to ewmNear, none past ewmFar. */
    static float ewmRange(Ship s, float dist) {
        if (dist <= s.ewmNear) return 1f;
        if (dist >= s.ewmFar || s.ewmFar <= s.ewmNear) return 0f;
        return 1f - (dist - s.ewmNear) / (s.ewmFar - s.ewmNear);
    }

    /** A weapon in a slot. */
    static final class Fit {
        final W w;
        final boolean hardpoint;
        /** 0..1 share of the full circle the slot covers (PD reach), and whether it can face the bow. */
        final float cover;
        final boolean front;
        /** Share of its fire that reaches the main target (bearing()). */
        final float bear;

        Fit(W w, boolean hardpoint, float cover, boolean front) { this(w, hardpoint, cover, front, 1f); }

        Fit(W w, boolean hardpoint, float cover, boolean front, float bear) {
            this.w = w; this.hardpoint = hardpoint; this.cover = cover; this.front = front; this.bear = bear;
        }
    }

    /**
     * How much of each mount's fire reaches the main target (research weapon_eval f.3): 1 if it can face the bow, 0.8
     * within 30 degrees of it, 0.35 beyond (it shoots flankers and fighters instead, but still spends its flux). On
     * broadside hulls, where side-only mounts carry over 40% of the mount weight, the ship fights side-on: mounts within
     * 100 degrees of the bow count fully and only rear ones lose. offAxis = degrees from the bow to the mount's arc.
     */
    static float[] bearing(float[] offAxis, float[] weight) {
        float all = 0f, side = 0f;
        for (int i = 0; i < offAxis.length; i++) { all += weight[i]; if (offAxis[i] > 30f) side += weight[i]; }
        boolean broadside = all > 0f && side / all > 0.4f;
        float[] b = new float[offAxis.length];
        for (int i = 0; i < b.length; i++) {
            float off = offAxis[i];
            b[i] = broadside ? (off <= 100f ? 1f : off <= 130f ? 0.8f : 0.35f) : (off <= 0.5f ? 1f : off <= 30f ? 0.8f : 0.35f);
        }
        return b;
    }

    static final class Params {
        JAOStyle style = JAOStyle.BALANCED;
        float fightSeconds = 90f, pdFloor = 1f, rareBonus;
        /**
         * Score share a bought weapon or LPC must add per credit it costs (jao_buyGain per 10,000 credits; 0 = prices
         * ignored). Single Optimal fits only: the fleet plan uses what is owned, the order planner known blueprints.
         */
        float creditCost;
        /** Kill-layer weights (shield, armor, hull), firepower vs survival, speed, point defense and fighter weights. */
        float wS = 1f, wA = 1f, wH = 1f, offenseW = 0.6f, mobilityW, pdMult = 1f, wingMult = 1f;
        JAORole role;

        /**
         * Style and role together: an explicit style sets the kill layers; Auto takes the role's. The role (when
         * used) sets firepower vs survival, speed, point defense, fighters and the preferred range.
         */
        void apply(JAOStyle st, JAORole r, int rangeSetting) {
            style = st;
            role = r;
            if (st == JAOStyle.AUTO && r != null) { wS = r.shield; wA = r.armor; wH = r.hull; }
            else { JAOStyle s = st == JAOStyle.AUTO ? JAOStyle.BALANCED : st; wS = s.shield; wA = s.armor; wH = s.hull; }
            if (r != null) { offenseW = r.offense; mobilityW = r.mobility; pdMult = r.pd; wingMult = r.wings; }
            rangePref = rangeSetting != 0 ? rangeSetting : r != null ? r.range : 0;
            // specialists (carriers, support, phase, missile boats) may lean one way; line ships may not
            coverage = (st == JAOStyle.AUTO || st == JAOStyle.BALANCED) && (r == null || r == JAORole.SKIRMISHER || r == JAORole.FLANKER
                    || r == JAORole.LINE || r == JAORole.ANCHOR || r == JAORole.BATTLE_CARRIER);
        }
        /** 0 auto, 1 long, 2 close. */
        int rangePref;
        Target[] targets;
        /** The rest of the fleet (JAOFleetContext): fighter bays by role, and copies of each wing id. */
        float[] fleetWingBays = new float[4];
        Map<String, Integer> fleetWingIds = new HashMap<>();
        /** Line ships on Auto/Balanced carry both kinetic and armor-cracking damage (COVERAGE). */
        boolean coverage;
    }

    /**
     * Per-ship coverage (research build_philosophy R2.1): the AI doesn't pick targets by damage type and ships don't
     * coordinate, so a line ship with almost no kinetic spends its time on shields, and one with no armor-cracker can't
     * finish anything. Up to x0.85 each when kinetic is under 20% of its damage or armor-cracking under 15%.
     */
    static final float COVERAGE = 0.85f, COVER_KINETIC = 0.20f, COVER_CRACK = 0.15f;

    static float coverage(float kineticShare, float crackShare) {
        return (COVERAGE + (1f - COVERAGE) * Math.min(1f, kineticShare / COVER_KINETIC))
                * (COVERAGE + (1f - COVERAGE) * Math.min(1f, crackShare / COVER_CRACK));
    }

    // ------------------------------------------------------------------ per weapon

    static float range(W w, Ship s) {
        if (w.wing) return w.range;
        float base = w.range + (s.valkRange != 0f && w.id != null && w.id.startsWith("valk") ? s.valkRange : 0f);
        if (w.cls == BAL && !w.pd) {
            float add = w.size == 0 ? s.bfSmall : w.size == 1 ? s.bfMedium : 0f;
            if (add > 0f) base = Math.max(base, Math.min(base + add, s.bfCap));
        }
        if (w.beam && s.hsa && base > 200f) base = 200f + 0.5f * (base - 200f);
        float pct = s.rangePct[w.cls] + (w.beam ? s.beamPct : 0f);
        float flat = s.rangeFlat[w.cls] + (w.beam ? s.beamFlat : 0f);
        // Point Defense elite: point-defense weapons reach 200 further
        if (w.pd || w.pdOnly) flat += w.beam ? s.pdBeamRange : s.pdRange;
        float r = (base + base * pct / 100f + flat) * s.rangeMult[w.cls] * (w.beam ? s.beamMult : 1f);
        if (w.cls != MIS && r > s.threshold) r = s.threshold + (r - s.threshold) * s.pastThreshold;
        return r;
    }

    static float armorFactor(float hit, float armor) { return armorFactor(hit, armor, MAX_DR); }

    /** maxDr: the most damage the armor blocks (85%; Polarized Armor 90%). */
    static float armorFactor(float hit, float armor, float maxDr) {
        if (hit <= 0f) return 1f - maxDr;
        return Math.max(1f - maxDr, hit / (hit + armor));
    }

    /** Per-weapon numbers on this ship, filled by evaluate(). */
    static final class Eff {
        float dps, flux, range, pool;
        /** Finite missiles: share of the pool that reaches a target the size of a capital (missileReliability x facing). */
        float rel = 1f;
        boolean front;
    }

    /**
     * Unguided rockets miss what moves, and slow ones more (0.5.8, the user: gyrojets "hit like a missile" but travel
     * slowly); guided missiles land, poorly guided ones mostly. Rockets that hit friendlies (collision MISSILE_FF: Yunru
     * gyrojets, Annihilators, Hammers, Reapers) lose a little more the harder each one hits: the user saw team kills from
     * gyrojets at 1,533 damage a rocket. Target size comes on top (SIZE_HIT).
     */
    static final float UNGUIDED_HIT = 0.75f, POOR_GUIDANCE_HIT = 0.85f, FF_RISK = 0.25f;

    static float missileReliability(W w) {
        float r = w.guided ? 1f : w.guidedPoor ? POOR_GUIDANCE_HIT : UNGUIDED_HIT;
        if (!w.guided && w.projSpeed > 0f && w.projSpeed < 600f) r *= 0.9f;
        if (w.ff) r *= 1f - FF_RISK * clamp(w.hit / 1000f, 0.2f, 1f);
        return r;
    }

    // ------------------------------------------------------------------ build score

    /** Score of a build (higher is better). vents/caps are already counted in s (cap, diss). */
    static float score(Ship s, List<Fit> fits, Params p) {
        return evaluate(s, fits, p, null);
    }

    /** Score with an optional breakdown map (for the debug log). */
    static float evaluate(Ship s, List<Fit> fits, Params p, Map<String, Float> out) {
        int n = fits.size();
        Eff[] e = new Eff[n];
        float load = 0f, rminAll = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            Eff x = new Eff();
            float rof = w.wing ? 1f : s.rofMult[w.cls];
            // wings fly from this ship's deck: its refit time, replacement rate and engagement range (deckFactor)
            float dm = w.wing ? s.deck : s.dmgMult[w.cls] * (w.beam ? s.beamDmgMult : 1f);
            if (w.beam && s.hsa) dm *= 1.1f;
            float base = w.wing ? w.dps : chargedDps(w, s, p.fightSeconds);
            float fluxScale = w.dps > 0f ? base / w.dps : 1f;
            x.dps = base * dm * rof * w.prior;
            x.flux = w.wing ? 0f : w.flux * fluxScale * s.fluxMult[w.cls] * (w.beam ? s.beamFluxMult : 1f) * rof;
            x.pool = w.pool * dm * (w.cls == MIS ? s.ammoMult : 1f) * w.prior;
            // a slower missile rate (Expanded Missile Racks' s-mod penalty) delivers less of the pool within a fight
            if (w.cls == MIS && !w.wing) x.pool *= Math.min(1f, rof);
            // a launcher can't fire faster than its rate: in a fight it delivers at most rate x fight length, however deep
            // the magazine (0.5.6: a CFT Twin Rocket holds 100 s of fire, and Expanded Missile Racks doubled what the 90-s
            // fight credited it with, so missile racks and deep slow launchers won every ship in the all-weapons run)
            if (w.poolRate > 0f && !w.wing) x.pool = Math.min(x.pool, w.poolRate * dm * rof * Math.max(10f, p.fightSeconds));
            x.range = range(w, s);
            x.front = fits.get(i).front;
            e[i] = x;
            if (!w.wing) {
                // plain point defense mostly waits for missiles and fighters; dual-role guns (PD + PD_ALSO, e.g.
                // Devastator) shoot ships whenever there's nothing else and pay full flux for it
                load += w.pd && !w.pdAlso ? 0.5f * x.flux : x.flux;
                rminAll = Math.min(rminAll, x.range);
            }
        }

        // MagicLib interference eats dissipation; then the flux budget the AI fires within (mechanics 3.4, 8.3)
        float diss = s.diss - interferenceLoss(s, fits);
        // shield upkeep only runs while the shield is up (SHIELD_UPTIME of a fight)
        float budget = Math.max(1f, diss - (s.shield ? SHIELD_UPTIME * s.upkeep : 0f));
        // over-budget autofire only spends capacity up to a flux level of 0.2-0.5 (0.5.11, research ai_behaviour_1106
        // SF-3): about 0.3 of it, 0.45 on frigates and destroyers, which are usually faster than what they fight
        float autoCap = s.size <= 1 ? AUTOFIRE_CAP_FAST : AUTOFIRE_CAP;
        float uptime = load <= 0f ? 1f : Math.min(1f, (budget * ENGAGE_SECONDS + autoCap * s.cap) / (load * ENGAGE_SECONDS));
        // burst windows (target overloaded, venting or flanked; mechanics 3.3-3.4: the AI opens every group up to 2x its
        // budget while its flux is hard and low): guns past the sustained budget still fire, paid from capacity
        float burstUp = load <= 0f ? 1f : Math.min(1f, (budget + BURST_CAP * s.cap / BURST_SECONDS) / load);
        float overflux = load / budget;

        // engagement range: shortest range in the heaviest non-missile, non-PD group (mechanics 3.2-3.3)
        Map<String, float[]> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            if (w.wing || w.pd || w.cls == MIS) continue;
            float wt = (w.size == 0 ? 1f : w.size == 1 ? 3f : 7f) * (fits.get(i).hardpoint ? 2f : 1f) + (w.cls == BAL ? 1f : 0f);
            float[] g = groups.computeIfAbsent(w.id, k -> new float[]{0f, Float.MAX_VALUE});
            g[0] += wt;
            g[1] = Math.min(g[1], e[i].range);
        }
        float reng = 0f, best = -1f;
        String mainId = null;
        for (Map.Entry<String, float[]> ge : groups.entrySet()) {
            float[] g = ge.getValue();
            if (g[0] > best || (g[0] == best && mainId != null && ge.getKey().compareTo(mainId) < 0)) { best = g[0]; reng = g[1]; mainId = ge.getKey(); }
        }
        if (best < 0f) {
            // missile boats and carriers: stand off at their missile range (or a carrier distance)
            reng = 1500f;
            for (int i = 0; i < n; i++) if (!fits.get(i).w.wing && fits.get(i).w.cls == MIS) reng = Math.min(reng, e[i].range);
        }
        if (s.personality == 0) reng = Math.max(100f, reng - 100f);
        else if (s.personality == 1 && rminAll < Float.MAX_VALUE) reng = Math.max(100f, Math.min(reng, Math.max(0.7f * rminAll, rminAll - 200f)));
        float satLo = 500f, sat = p.rangePref == 1 ? REACH_SAT + 300f : p.rangePref == 2 ? 800f : REACH_SAT;
        float reach = 0.7f + 0.3f * clamp((reng - satLo) / (sat - satLo), 0f, 1f);
        // Two distances (mechanics 3.3): the ship fights at its main battery's range, but whenever it has the flux
        // edge or the target overloads, the AI closes to its shortest gun (aggressive officers: most of the time).
        // Short guns on a long-range ship pull it into the range of everything that out-ranges it.
        float rclose = reng;
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            if (!w.wing && !w.pd && !w.pdOnly && w.cls != MIS) rclose = Math.min(rclose, e[i].range);
        }
        if (s.personality == 1 && rminAll < Float.MAX_VALUE) rclose = Math.min(rclose, Math.max(0.7f * rminAll, rminAll - 200f));
        rclose = Math.max(100f, rclose);
        float close = s.personality == 1 ? 0.8f : s.personality == 2 ? 0.15f : 0.4f;
        // staying power against each enemy class by who out-ranges whom, at both distances
        float k = p.rangePref == 1 ? 0.6f : p.rangePref == 2 ? 0.3f : 0.5f, rs = 0f, rw = 0f;
        for (Target t : p.targets) {
            float far = 1f + k * clamp((reng - t.threat) / 400f, -1f, 0.75f);
            float near = 1f + k * clamp((rclose - t.threat) / 400f, -1f, 0.75f);
            rs += t.weight * ((1f - close) * far + close * near);
            rw += t.weight;
        }
        float outrange = rw > 0f ? rs / rw : 1f;

        // Energy Weapon Mastery (captain skill, a combat listener the stats don't carry): energy weapons deal up to +30% at
        // high flux, in full up to 600 from the target and none past 1000; scored at EWM_FLUX at both distances
        if (s.ewm > 0f) {
            for (int i = 0; i < n; i++) {
                W w = fits.get(i).w;
                if (w.wing || w.cls != ENE) continue;
                float f = (1f - close) * ewmRange(s, Math.min(e[i].range, reng)) + close * ewmRange(s, Math.min(e[i].range, rclose));
                float m = 1f + s.ewm * EWM_FLUX * f;
                e[i].dps *= m;
                e[i].pool *= m;
            }
        }

        // fighter bays by role across the fleet and this ship, for the role balance of each wing
        float[] bays = p.fleetWingBays.clone();
        Map<String, Integer> sameWing = new HashMap<>();
        int shipWings = 0, ranged = 0, escorts = 0, screens = 0;
        float shortWing = Float.MAX_VALUE, longWing = 0f;
        for (Fit f : fits) {
            if (!f.w.wing) continue;
            bays[Math.max(0, Math.min(3, f.w.wingRole))] += 1f;
            shipWings++;
            if (f.w.range <= 0f) continue; // range-0 wings always stay with the carrier
            ranged++;
            if (f.w.wingRole != W_BOMBER) escorts++;
            if (f.w.wingRole == W_INTERCEPTOR || f.w.wingRole == W_SUPPORT) screens++;
            if (f.w.wingRole != W_SUPPORT) { shortWing = Math.min(shortWing, f.w.range); longWing = Math.max(longWing, f.w.range); }
        }
        // interceptors only do their escort (point defense) job when the carrier goes into escort mode: the game wants
        // at least 66% of its ranged wings to be fighters, interceptors or support (bombers count against it), and 75%
        // with half of them interceptors or support under an aggressive or reckless captain (0.5.11, research
        // ai_behaviour_1106 CF-1; the old rule "any bomber means no escort" was wrong for 3-4-bay carriers)
        float escortShare = ranged > 0 ? escorts / (float) ranged : 1f, screenShare = ranged > 0 ? screens / (float) ranged : 0f;
        boolean defend = escortShare >= ESCORT_SHARE && (s.personality != 1 || (escortShare >= 0.75f && screenShare >= 0.5f));
        float escort = !defend || s.noAutoEscort ? INTERCEPTOR_NO_ESCORT : 1f;
        // every wing strikes only as far as the shortest ranged non-support wing reaches (CF-2): one short wing caps the rest
        float reachCap = shortWing == Float.MAX_VALUE || longWing <= 0f ? 1f
                : 0.85f + 0.15f * clamp((Math.max(1500f, 0.9f * shortWing) - 1500f) / Math.max(1f, Math.max(1500f, 0.9f * longWing) - 1500f), 0f, 1f);

        // per-weapon effective dps and the shares that matter per phase: the shield phase is a flux war at the
        // sustained rate; armor and hull go down half in burst windows (shield down, overload, flanks)
        float[] eff = new float[n], effB = new float[n];
        float pd = 0f, missilePool = 0f, missilePoolStrike = 0f;
        // point defense shoots missiles and fighters about equally (Point Defense: +50% to each)
        float pdDmg = 0.5f * (s.pdDmg + s.fighterDmg);
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            Eff x = e[i];
            // in range at the main distance, and when the AI closes in
            float atFar = x.range >= reng ? 1f : (x.range / reng) * (x.range / reng);
            float atNear = x.range >= rclose ? 1f : (x.range / rclose) * (x.range / rclose);
            float inRange = w.wing ? 1f : (1f - close) * atFar + close * atNear;
            // off-axis mounts reach the main target only part of the time
            float bear = w.wing ? 1f : fits.get(i).bear;
            // wings: worth less in a role the fleet already has plenty of, and a little less per copy of the same wing
            // elsewhere in the fleet or on this ship (fighters are multi-role; one wing in every bay isn't a fleet)
            float wingBalance = 1f;
            if (w.wing) {
                int seen = sameWing.merge(w.id, 1, Integer::sum) - 1;
                int elsewhere = Math.min(6, p.fleetWingIds.getOrDefault(w.id, 0));
                wingBalance = wingRoleFactor(w.wingRole, bays) * (float) Math.pow(0.97, elsewhere) * (float) Math.pow(0.95, seen);
                if (w.wingRole == W_INTERCEPTOR) wingBalance *= escort;
                if (w.wingRole == W_BOMBER && shipWings == 1) wingBalance *= LONE_BOMBER;
                if (w.wingRole != W_SUPPORT && w.range > 0f) wingBalance *= reachCap;
                // Defensive Targeting Array keeps every wing at the carrier (CF-4): bombers have nothing to strike,
                // interceptors and point-defense wings gain +50% against fighters and missiles
                if (s.dta) wingBalance *= w.wingRole == W_BOMBER ? 0.2f : w.wingRole == W_INTERCEPTOR || w.wingPd >= 0.5f ? 1.3f : w.wingRole == W_SUPPORT ? 1f : 0.8f;
            }
            float d = w.wing ? x.dps * p.wingMult * wingBalance : x.dps * uptime * inRange * reach * bear;
            float db = w.wing ? d : x.dps * (BURST_SHARE * burstUp + (1f - BURST_SHARE) * uptime) * inRange * reach * bear;
            // PD weapons engage missiles and fighters first: plain PD hits ships a quarter of the time, dual-role guns
            // (PD_ALSO) most of it (research weapon_eval f.7)
            float pdShare = w.pdOnly ? 0f : !w.pd ? 1f : w.pdAlso ? 0.6f : 0.25f;
            d *= pdShare;
            db *= pdShare;
            eff[i] = d;
            effB[i] = db;
            if (w.pd || w.pdOnly || w.interceptor) {
                float hitf = w.beam || w.wing ? 1f : Math.min(1f, w.projSpeed / 900f) * Math.min(1f, 10f / Math.max(1f, w.spread * s.recoilMult));
                // turrets track missiles and fighters at the ship's turn rate (Damaged Weapon Mounts slow them, Advanced
                // Turret Gyros speed them up)
                if (!w.wing && !fits.get(i).hardpoint) hitf *= pdTrack(w.beam ? s.beamTurnMult : s.turnMult);
                float reachPd = clamp(x.range / 500f, 0.5f, 1.2f);
                pd += x.dps * pdDmg * hitf * fits.get(i).cover * reachPd * (w.wing && w.interceptor ? escort : 1f) * (w.wing ? 1f : pdKill(x.dps * pdDmg, x.range));
            } else if (w.wing && w.wingPd > 0f) {
                // multi-role fighters screen against missiles and fighters with their PD-capable guns, at half weight
                pd += 0.5f * w.wingPd * x.dps * pdDmg;
            }
            if (x.pool > 0f && x.dps <= 0f) {
                // finite missiles: unguided ones only count when they can face the target, and miss more (missileReliability)
                float use = ((w.guided || x.front) ? 1f : 0.25f) * (w.wing ? 1f : missileReliability(w));
                x.rel = use;
                if (w.strike) missilePoolStrike += x.pool * use; else missilePool += x.pool * use;
            }
        }

        // time to kill each reference target
        float ttkSum = 0f, wsum = 0f, t1s = 0f, t2s = 0f, t3s = 0f;
        for (Target t : p.targets) {
            float[] ph = phases(fits, e, eff, effB, t, p, missilePool, missilePoolStrike, s, mainId);
            float ttk = p.wS * ph[0] + p.wA * ph[1] + p.wH * ph[2];
            ttkSum += t.weight * ttk;
            wsum += t.weight;
            t1s += t.weight * ph[0]; t2s += t.weight * ph[1]; t3s += t.weight * ph[2];
        }
        float ttk = Math.max(0.5f, ttkSum / Math.max(0.001f, wsum));
        float offense = 1f / ttk;

        // survival (effective HP) and peak time
        float tank = 0f, shieldHp = 0f;
        if (s.shield) {
            float eF = Math.max(0.05f, s.shieldEff * s.shieldTaken);
            tank = (0.5f + 0.4f * clamp((1f - eF) / 0.4f, 0f, 1f)) * (float) Math.sqrt(Math.min(1f, s.arc / 180f));
            if (s.armor >= 1000f) tank *= 0.8f;
            shieldHp = tank * (SHIELD_USABLE * s.cap + SHIELD_REGEN_SECONDS * Math.max(0f, diss - SHIELD_UPTIME * s.upkeep - 0.5f * load)) / eF;
        }
        float ehp = armorHullEhp(s) + shieldHp;
        // phase ships avoid much of the damage, longer the more flux they have to phase on (0.5.15: experts max their
        // capacitors; a flat x1.3 made Heavy Armor the best use of their OP)
        if (s.phase) ehp *= 1f + PHASE_EHP * (float) Math.sqrt(Math.max(0.5f, s.baseCap > 0f ? s.cap / s.baseCap : 1f));
        // peak time against a whole battle (several engagements), not one fight: a cut peak time (Safety
        // Overrides x0.33) costs combat readiness unless Hardened Subsystems offsets it
        float battle = Math.max(180f, 2f * p.fightSeconds);
        // past peak time CR falls at the ship's rate (Combat Endurance x0.75)
        float cr = s.ppt > 0f ? Math.max(0f, 1f - 0.5f * s.crLoss * Math.max(0f, battle - s.ppt) / battle) : 1f;
        // a range cap (Safety Overrides keeps 25% past 450) wastes long guns: penalise their share of the firepower,
        // and not being able to vent costs a little more (research meta_report 10.11: SO only with short guns)
        float capPenalty = 1f;
        if (s.threshold < Float.MAX_VALUE && s.pastThreshold < 1f) {
            float all = 0f, longGuns = 0f;
            for (int i = 0; i < n; i++) {
                W w = fits.get(i).w;
                if (w.wing || w.pd || w.cls == MIS) continue;
                all += e[i].dps;
                if (w.range > s.threshold + 100f) longGuns += e[i].dps;
            }
            if (all > 0f) capPenalty -= 0.6f * longGuns / all;
        }
        if (s.noVent) capPenalty *= 0.93f;

        // point defense floor (destroyers and up need some); past the need, PD only counts as half a gun
        float need = PD_NEED[Math.max(0, Math.min(3, s.size))] * p.pdFloor * p.pdMult;
        // past the floor, more point defense still stops more missiles and fighters, up to about twice the floor (0.5.11:
        // with no credit past the floor, small energy mounts filled with LR PD lasers at 50 dps where the community puts
        // 2-3 Burst PD lasers at 210; research meta_report 5)
        float pdFactor = need <= 0f ? 1f : 0.9f + 0.1f * Math.min(1f, pd / need) + PD_EXTRA * clamp((pd - need) / (2f * need), 0f, 1f);
        if (s.size >= 1 && need > 0f && pd < 0.5f * need) pdFactor *= 0.95f;
        // a ship with no point defense at all backs away from every incoming missile (tournament commentary, research
        // tournaments_1106 rule 1): frigates too
        if (pd <= 0f && need > 0f) pdFactor *= 0.95f;

        // far over budget the AI keeps groups switched off even in burst windows (mechanics 3.4: f up to 2;
        // research meta R91: 2x is the workable limit), so guns past that waste OP
        // gentle pressure from 1.25x (community target about 1x for AI ships, 1.5x hard cap), so a ship only goes past
        // it for a gun clearly worth it; past FLUX_LIMIT the extra guns sit switched off
        float fluxFactor = overflux <= s.fluxSoft ? 1f : (float) Math.pow(s.fluxSoft / Math.min(overflux, s.fluxLimit), FLUX_SOFT_EXP);
        if (overflux > s.fluxLimit) fluxFactor *= s.fluxLimit / overflux;

        // weapon groups: every distinct weapon is its own group and Starsector has 7; past that the closest groups
        // merge (mechanics 3.1). Point defense merging with point defense is harmless, so all PD counts as one group;
        // a gun merged with a different gun fires at the wrong range or flux. Fewer, repeated gun types also keep one
        // range band and let the AI link fire, so each distinct gun past three costs a little
        java.util.Set<String> groupIds = new java.util.HashSet<>(), gunIds = new java.util.HashSet<>();
        for (Fit f : fits) {
            if (f.w.wing) continue;
            groupIds.add(f.w.pd || f.w.pdOnly ? "#pd" : f.w.id);
            if (!f.w.pd && !f.w.pdOnly && f.w.cls != MIS) gunIds.add(f.w.id);
        }
        float groupFactor = (float) (Math.pow(GROUP_OVER, Math.max(0, groupIds.size() - 7)) * Math.pow(GUN_VARIETY, Math.max(0, gunIds.size() - 3)));
        // one range band (research weapon_eval e.3, meta 10.2): gun damage more than 200 away from the main battery's
        // range either never fires (longer guns wait for the ship) or drags the ship in (shorter ones)
        float gunDps = 0f, offBand = 0f;
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            if (w.wing || w.pd || w.pdOnly || w.cls == MIS || e[i].dps <= 0f) continue;
            gunDps += e[i].dps;
            if (Math.abs(e[i].range - reng) > 200f) offBand += e[i].dps;
        }
        if (gunDps > 0f) groupFactor *= 1f - OFF_BAND * offBand / gunDps;

        // line ships carry both kinetic and an armor-cracker (finite missiles counted over the fight)
        float cover = 1f;
        if (p.coverage && !s.noVent) {
            float all = 0f, kin = 0f, crack = 0f;
            int mains = 0;
            for (int i = 0; i < n; i++) {
                W w = fits.get(i).w;
                if (w.pdOnly || (w.pd && !w.pdAlso)) continue; // plain PD shoots missiles; dual-role guns count
                float d = eff[i] + (e[i].pool > 0f && e[i].dps <= 0f ? e[i].pool / Math.max(30f, p.fightSeconds) : 0f);
                if (d <= 0f) continue;
                if (!w.wing && !w.pd) mains++;
                all += d;
                if (w.dmg == KIN) kin += d;
                if (w.hit * ARMOR_MULT[w.dmg] >= 300f) crack += d;
            }
            if (mains >= 3 && all > 0f) cover = coverage(kin / all, crack / all);
        }

        // offense weighs more than survival: more firepower also ends fights sooner, covers more targets and
        // flanks, and the AI fights in fleets, not duels (community rule: weapons first, research meta_report 10)
        // speed and handling against the hull's own: every ship has to reach the fight and turn its guns (big ships
        // that arrive late lose battles), frigates live on it; so hullmods and s-mod penalties that slow a ship cost
        float mw = Math.max(MIN_MOBILITY, p.mobilityW);
        // capped (0.5.6): Yunru's joke "Let's Bayblade!" multiplies turn rate by 50 and was worth more than any gun, so it
        // went on every ship once all hullmods were known; past half again the speed or twice the handling, more is no use
        // turning matters to slow hulls that aim hardpoints by turning (0.5.11: Auxiliary Thrusters went on 59% of hulls,
        // nimble frigates included, against the community's "slow hardpoint ships": Dominator, Legion, Onslaught,
        // Executor, Conquest, Invictus; research meta_report 3.1): full weight at 20 degrees a second or slower
        float hpW = 0f, gunW = 0f;
        for (Fit f : fits) {
            if (f.w.wing || f.w.pd || f.w.pdOnly || f.w.cls == MIS) continue;
            float wt = f.w.size == 0 ? 1f : f.w.size == 1 ? 2f : 4f;
            gunW += wt;
            if (f.hardpoint) hpW += wt;
        }
        float turnNeed = (s.baseTurn > 0f ? clamp(TURN_REF / s.baseTurn, 0.3f, 1f) : 1f) * (0.6f + 0.4f * (gunW > 0f ? hpW / gunW : 0f));
        float mobility = (s.baseSpeed > 0f ? (float) Math.pow(clamp(s.speed / s.baseSpeed, 0.2f, SPEED_CAP), mw) : 1f)
                * (float) Math.pow(clamp(s.maneuver, 0.2f, MANEUVER_CAP), (0.5f * mw + 0.04f) * turnNeed);
        // combat readiness the ship starts its fights at: its own max CR (skills, hullmods, d-mods) less what Seeker's
        // nano-plague takes (3/6/12 points per plague weapon or LPC on a hull it harms, Coda Launcher 3), through the
        // game's CR rules (crStart); a d-mod ship near 50% pays more for the same plague weapon than a healthy one
        float plague = 0f;
        for (int i = 0; i < n; i++) plague += fits.get(i).w.crCost;
        plague *= s.plagueMult;
        float startCr = crStart(s, s.maxCr - plague / 100f);
        // weapons that malfunction outside CR (Ill-Advised Modifications) fire less
        float malf = 1f - clamp(MALFUNCTION_COST * s.malfunction, 0f, 0.5f);
        // EMP resistance and faster venting (Resistant Flux Conduits; 0.5.12, "absolute necessity on any battleship" late
        // game against Remnant, Threat and Omega EMP, research builds_update_1106 4.2): small, larger on big hulls
        // (faster venting counts through duty())
        float resist = 1f + EMP_W[Math.max(0, Math.min(3, s.size))] * clamp(1f - s.empTaken, 0f, 1f);
        float stay = duty(s, diss);
        float score = (float) (Math.pow(offense, p.offenseW) * Math.pow(ehp * outrange, 1f - p.offenseW)) * cr * pdFactor * fluxFactor * groupFactor * capPenalty * mobility * cover * startCr * malf * resist * stay * (1f + s.extra);
        if (out != null) {
            if (plague > 0f) out.put("plague", plague);
            if (s.maxCr != CR_BASE || plague > 0f) { out.put("maxCr", s.maxCr - plague / 100f); out.put("crStart", startCr); }
            int tier = crTier(s.maxCr - plague / 100f, s.malfAt, s.critAt);
            if (tier > 0) out.put("crTier", (float) tier);
            if (s.deck != 1f) out.put("deck", s.deck);
            if (malf < 1f) out.put("malf", malf);
            if (s.shield) out.put("stay", stay);
            out.put("score", score); out.put("ttk", ttk); out.put("t1", t1s / wsum); out.put("t2", t2s / wsum); out.put("t3", t3s / wsum);
            out.put("ehp", ehp); out.put("uptime", uptime); out.put("load", load); out.put("budget", budget); out.put("reng", reng);
            out.put("pd", pd); out.put("pdNeed", need); out.put("outrange", outrange); out.put("rclose", rclose);
            out.put("burstUp", burstUp); out.put("groups", (float) groupIds.size()); out.put("guns", (float) gunIds.size());
            out.put("cover", cover);
        }
        return score;
    }

    /** Seconds to break the shield, strip the armor and kill the hull of t (mechanics 8.6). */
    static float[] phases(List<Fit> fits, Eff[] e, float[] effAll, float[] effBurst, Target t, Params p, float pool, float poolStrike) {
        return phases(fits, e, effAll, effBurst, t, p, pool, poolStrike, null, null);
    }

    static float[] phases(List<Fit> fits, Eff[] e, float[] effAll, float[] effBurst, Target t, Params p, float pool, float poolStrike, float spreadMult) {
        Ship s = new Ship();
        s.recoilMult = spreadMult;
        return phases(fits, e, effAll, effBurst, t, p, pool, poolStrike, s, null);
    }

    /**
     * mainId: the heaviest non-missile group (the one the AI aims and fires by hand); personality as Ship.personality.
     * pool and poolStrike are the old summed missile pools, no longer used: missiles are priced one by one from e[].
     */
    static float[] phases(List<Fit> fits, Eff[] e, float[] effAll, float[] effBurst, Target t, Params p, float pool, float poolStrike,
                          Ship ship, String mainId) {
        float spreadMult = ship == null ? 1f : ship.recoilMult;
        int personality = ship == null ? 0 : ship.personality;
        float hard = 0f, soft = 0f, debuff = 1f, armorExtra = 0f, arc = 0f;
        int n = fits.size();
        int ts = Math.max(0, Math.min(3, t.size));
        float[] eff = new float[n], effB = new float[n];
        for (int i = 0; i < n; i++) {
            Fit fit = fits.get(i);
            float acc = accuracy(fit.w, t.size, spreadMult) * (ship == null ? 1f : track(fit, ship, ts));
            eff[i] = effAll[i] * acc;
            effB[i] = effBurst[i] * acc;
        }
        float missileHit = SIZE_HIT[ts];
        // the shield-efficiency hold (0.5.11, research ai_behaviour_1106 SF-5, AttackAIModule): only the main group the
        // AI fires by hand is held back against a facing shield, by a vote of how flux-efficient each weapon is there
        // against what the captain tolerates; autofire groups always fire
        float holdAt = personality == 1 ? 0.75f : personality == 2 ? 1f : 0.9f;
        // shields go down in the same mix of sustained fire and capacity dumps (the AI opens every group while its
        // flux is all hard, to force an overload), so kinetic and HE are judged on the same footing
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            if (effB[i] <= 0f) continue;
            float sm = SHIELD_MULT[w.dmg] * w.shieldMultExtra;
            float vsShield = effB[i] * sm;
            if (!w.fireWhenInefficient && !w.wing && w.dps > 0f && mainId != null && mainId.equals(w.id)) {
                float r = t.shieldEff * sm / Math.max(0.05f, e[i].flux / Math.max(1f, e[i].dps));
                vsShield *= 1f - 0.6f * clamp((holdAt - r) / 0.6f, 0f, 1f);
            } else if (mainId == null && !w.fireWhenInefficient && !w.wing && w.dps > 0f && t.shieldEff * sm < 0.5f * (e[i].flux / Math.max(1f, e[i].dps))) {
                vsShield *= 0.5f; // callers without the main group (old rule)
            }
            if (w.soft) soft += vsShield; else hard += vsShield;
            // scripted extra damage that also lands on shields follows the hits that were fired (hard flux)
            if (w.extraFrac > 0f && w.extraShields && sm > 0f) hard += vsShield / sm * w.extraFrac * SHIELD_MULT[w.extraDmg];
            if (w.shieldDebuff > 1f) debuff = Math.max(debuff, w.shieldDebuff);
            armorExtra += w.armorExtraPerHit * w.hitsPerSec;
            arc += w.arcDps;
        }
        // finite missiles one by one (0.5.11, research ai_behaviour_1106 MI-1/MI-2): the share of the pool that reaches
        // this target (torpedoes by its size), then the same armor math as guns; the old summed pool at a flat 0.5
        // against armor under-rated HE torpedoes about 2.4x and over-rated fragmentation missiles about 13x
        float missileWindow = Math.max(10f, p.fightSeconds);
        float[] mRate = new float[n];
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            if (e[i].pool <= 0f || e[i].dps > 0f) continue;
            mRate[i] = e[i].pool * e[i].rel * missileUse(w, ts) * pdSurvival(w, ship == null ? 1f : ship.missileHp) / missileWindow;
        }
        // free-fire missiles (deep magazines: Annihilators, Swarmers, Pilums) go whenever the target is in range, mostly
        // into a raised shield (the shield AI faces incoming missiles); conserved ones wait for an opening (research
        // ai_behaviour_1106 Q1, MI-3). Non-kinetic free-fire rate splits between shield and armor
        float[] mShield = new float[n];
        if (t.shield) {
            for (int i = 0; i < n; i++) {
                W w = fits.get(i).w;
                if (mRate[i] <= 0f || w.dmg == KIN || w.shieldMultExtra > 1f || !freeFire(w)) continue;
                mShield[i] = mRate[i] * FREE_FIRE_SHIELD;
                mRate[i] -= mShield[i];
            }
        }
        float t1 = 0f;
        if (t.shield) {
            float rate = (hard + Math.max(0f, soft - t.diss)) * t.shieldEff * debuff;
            for (int i = 0; i < n; i++) {
                W w = fits.get(i).w;
                if (mRate[i] > 0f && (w.dmg == KIN || w.shieldMultExtra > 1f)) rate += mRate[i] * SHIELD_MULT[w.dmg] * w.shieldMultExtra;
                rate += mShield[i] * SHIELD_MULT[w.dmg] * t.shieldEff;
                if (w.extraFrac > 0f && w.extraShields) rate += mShield[i] * w.extraFrac * SHIELD_MULT[w.extraDmg] * t.shieldEff;
            }
            t1 = rate <= 0.01f ? 600f : Math.min(600f, 0.6f * t.cap / rate);
        }
        // armor: pooled armor from A down to 0.2A in steps
        float t2 = 0f, a = t.armor, floor = t.armor * MIN_ARMOR;
        int steps = 8;
        float chunk = t.armor * 0.8f / steps;
        for (int k = 0; k < steps; k++) {
            float pa = Math.max(a, floor);
            float rate = armorExtra;
            for (int i = 0; i < n; i++) {
                W w = fits.get(i).w;
                float am = ARMOR_MULT[w.dmg];
                if (mRate[i] > 0f) rate += mRate[i] * am * armorFactor(w.hit * am, pa);
                if (w.extraFrac > 0f) {
                    float ae = ARMOR_MULT[w.extraDmg];
                    rate += (mRate[i] + 0.8f * Math.max(0f, effB[i])) * w.extraFrac * ae * armorFactor(w.extraHit * ae, pa);
                }
                if (effB[i] <= 0f) continue;
                rate += 0.8f * effB[i] * am * armorFactor(w.hit * am, pa);
            }
            t2 += rate <= 0.01f ? 600f / steps : chunk / rate;
            a -= chunk;
        }
        t2 = Math.min(600f, t2 * 1.5f);
        // hull, through the leftover armor
        float hullRate = arc;
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            if (mRate[i] > 0f) hullRate += mRate[i] * armorFactor(w.hit * ARMOR_MULT[w.dmg], floor);
            if (w.extraFrac > 0f) hullRate += (mRate[i] + Math.max(0f, effB[i])) * w.extraFrac * armorFactor(w.extraHit * ARMOR_MULT[w.extraDmg], floor);
            if (effB[i] <= 0f) continue;
            hullRate += effB[i] * armorFactor(w.hit * ARMOR_MULT[w.dmg], floor);
        }
        float t3 = hullRate <= 0.01f ? 600f : Math.min(600f, t.hull / hullRate);
        // Target Analysis: more damage to this target's size, so each phase goes faster
        float sd = ship == null ? 1f : Math.max(0.1f, ship.sizeDmg[ts]);
        if (sd != 1f) { t1 /= sd; t2 /= sd; t3 /= sd; }
        return new float[]{t1, t2, t3};
    }

    /**
     * Share of a finite missile's pool fired at a target of size ts (0 frigate .. 3 capital). STRIKE weapons follow the
     * AI's gates (research ai_behaviour_1106 MI-2): unguided torpedoes go only at slow, non-strafing destroyers and up,
     * guided ones at anything but frigates. Other missiles hit smaller targets less (SIZE_HIT).
     */
    static float missileUse(W w, int ts) {
        int s = Math.max(0, Math.min(3, ts));
        if (w.strike) return (w.guided || w.guidedPoor ? STRIKE_USE_GUIDED : STRIKE_USE_UNGUIDED)[s];
        return 0.5f + 0.5f * SIZE_HIT[s];
    }

    static final float[] STRIKE_USE_UNGUIDED = {0.05f, 0.3f, 0.7f, 0.95f}, STRIKE_USE_GUIDED = {0.05f, 0.95f, 1f, 1f};

    /** Score per point of EMP damage avoided, by hull size; per point of extra vent rate. */
    static final float[] EMP_W = {0f, 0.01f, 0.025f, 0.05f};
    /** Times a gun's charges refill and empty again in a fight (lulls between engagements, overload windows). */
    static final float MAG_REFILLS = 3f;

    /**
     * A recharging-ammo gun's average dps over a fight (0.5.12): the recharge-limited rate plus the stored charges, emptied
     * about MAG_REFILLS times. Before, only the recharge rate counted (Autopulse 300 of its 1,500, Thermal Pulse Cannon 250
     * of 1,250), so Expanded Magazines (+50% charges; as an s-mod +50% recharge too), which experts take first on those
     * ships, was worth nothing.
     */
    static float chargedDps(W w, Ship s, float fight) {
        if (w.regen <= 0f || w.burstDps <= w.dps * 1.05f || w.ammoMax <= 0f) return w.dps;
        float ammo = w.cls == BAL ? s.ammoBal : w.cls == ENE ? s.ammoEn : 1f;
        float regen = w.cls == BAL ? s.regenBal : w.cls == ENE ? s.regenEn : 1f;
        float sust = Math.min(w.burstDps, w.dps * regen);
        float ratio = w.burstDps / Math.max(1f, sust);
        float mag = ratio > 1.01f ? w.ammoMax * ammo / (w.regen * regen * (ratio - 1f)) : 0f;
        return sust + (w.burstDps - sust) * Math.min(1f, MAG_REFILLS * mag / Math.max(30f, fight));
    }

    /**
     * Share of missiles that get past the target's point defense, by missile hit points (0.5.11): 50-HP rockets
     * (Annihilator, Swarmer) about 0.67, a 150-HP Harpoon 0.86, a 500-HP Reaper 0.95. Before, an Annihilator rack's
     * 10,000 damage on paper beat a Harpoon rack's 2,250 in 71 mounts of the reference run; the community rates them C+
     * and A+.
     */
    static float pdSurvival(W w) { return pdSurvival(w, 1f); }

    /** hpMult: the ship's missile hit points (Missile Specialization +25%). */
    static float pdSurvival(W w, float hpMult) {
        if (w.wing || w.projHP <= 0f) return 1f;
        return 1f / (1f + PD_KILL * 100f / (w.projHP * Math.max(0.1f, hpMult)));
    }

    static final float PD_KILL = 0.25f;

    /**
     * Whether one point-defense weapon can kill a missile before it lands (0.5.11): the AI's PD picks a random missile
     * in range (research ai_behaviour_1106 Q4), so a weapon must kill a ~300-HP missile (Sabot, Atropos) in the ~1 s a
     * 400-speed missile spends per 400 units of its range. Burst PD 0.9, Vulcan and Flak about 1, LR PD and PD lasers
     * a third: the community's 2-3 Burst PD per cruiser, where the model had filled small mounts with LR PD lasers.
     */
    static float pdKill(float dps, float range) {
        return clamp(dps * Math.max(0.5f, range / 400f) / 300f, 0.2f, 1f);
    }

    /** Missiles the AI fires freely rather than saving for an opening: deep magazines (20+ shots), not STRIKE. */
    static boolean freeFire(W w) {
        return !w.strike && w.pool > 0f && w.hit > 0f && w.pool / w.hit >= 20f;
    }

    /** Share of a free-fire missile's non-kinetic damage that lands on a raised shield, against a shielded target. */
    static final float FREE_FIRE_SHIELD = 0.6f;

    /**
     * Whether a turret keeps up with a target of size ts (research ai_behaviour_1106 AIM-2): a frigate orbiting at 600 su
     * crosses about 19 degrees a second, a destroyer 9, a cruiser 5, a capital 3; large turrets turn 7-12. A bow-facing
     * mount also gets half the ship's own turn. Beams need the barrel on target too but no lead, so half the effect;
     * hardpoints, missiles and wings aren't limited here (the ship aims hardpoints by turning).
     */
    static float track(Fit f, Ship s, int ts) {
        W w = f.w;
        if (w.wing || w.cls == MIS || f.hardpoint || w.turnRate <= 0f) return 1f;
        float turn = w.turnRate * (w.beam ? s.beamTurnMult : s.turnMult) + (f.front ? 0.5f * s.turnRate : 0f);
        float k = clamp(turn / CROSSING[Math.max(0, Math.min(3, ts))], TRACK_MIN, 1f);
        return w.beam ? 0.5f + 0.5f * k : k;
    }

    static final float[] CROSSING = {19f, 9f, 5f, 3f};
    /** Turn rate (degrees a second) at and below which extra handling counts fully. */
    static final float TURN_REF = 20f;
    /** Phase ships' survival bonus at their stock flux capacity (grows with the square root of added capacity). */
    static final float PHASE_EHP = 0.3f;

    /** Grievous69's tier letters as numbers (research meta_report 8): SS 10 .. F 1; 0 = unknown. */
    static float tierScore(String tier) {
        if (tier == null) return 0f;
        String t = tier.trim().toUpperCase(java.util.Locale.ROOT);
        String[] k = {"SS", "S+", "S-", "S", "A+", "A-", "A", "B+", "B-", "B", "C+", "C-", "C", "D", "F"};
        float[] v = {10f, 9.5f, 8.5f, 9f, 8f, 7f, 7.5f, 6.5f, 5.5f, 6f, 5f, 4f, 4.5f, 3f, 1f};
        for (int i = 0; i < k.length; i++) if (t.startsWith(k[i])) return v[i];
        return 0f;
    }

    /** Weight of the community tier on a weapon's value: about 2% per tier step around B. */
    static final float TIER_WEIGHT = 0.08f;

    static float tierPrior(float score) { return 1f + TIER_WEIGHT * (score - 6f) / 4f; }
    static final float TRACK_MIN = 0.35f;

    /**
     * Kinetic missiles into shields, with the same hit chances as against armor (0.5.8: they counted every round as a hit
     * whatever the target's size or the missile's guidance, so kinetic rockets ran the fleet's anti-shield up to 70%).
     */
    static float missileShield(List<Fit> fits, Eff[] e, float window, float sizeHit) {
        int ts = 0;
        for (int k = 0; k < SIZE_HIT.length; k++) if (Math.abs(SIZE_HIT[k] - sizeHit) < 1e-4f) ts = k;
        float r = 0f;
        for (int i = 0; i < fits.size(); i++) {
            W w = fits.get(i).w;
            if (e[i].pool <= 0f || e[i].dps > 0f) continue;
            float hit = e[i].rel * missileUse(w, ts);
            if (w.dmg == KIN || w.shieldMultExtra > 1f) r += e[i].pool * hit * SHIELD_MULT[w.dmg] * w.shieldMultExtra / window;
        }
        return r;
    }

    // ------------------------------------------------------------------ vents and capacitors

    static final float VENT_TARGET = 1.25f;

    /** Vents to cover the weapon load (+25%), then capacitors, then any OP left to vents. Returns {vents, caps}. */
    static int[] fluxFill(Ship s, List<Fit> fits, int opLeft) {
        float load = 0f;
        for (Fit f : fits) {
            W w = f.w;
            if (w.wing) continue;
            float fl = w.flux * s.fluxMult[w.cls] * (w.beam ? s.beamFluxMult : 1f) * s.rofMult[w.cls];
            load += w.pd ? 0.5f * fl : fl;
        }
        // the AI vents only in safe windows long enough to empty its flux, and never with a missile inbound, so AI ships
        // want dissipation past their load before capacity (0.5.11, research ai_behaviour_1106 SF-2: 1.25, was 1.1)
        float want = load * VENT_TARGET + (s.shield ? SHIELD_UPTIME * s.upkeep : 0f) - (s.diss - interferenceLoss(s, fits));
        int v = Math.max(0, Math.min(Math.min(s.maxVents, opLeft), (int) Math.ceil(want / 10f)));
        if (s.phase) v = Math.min(v, Math.max(0, opLeft / 2)); // phase ships lean on capacitors
        int c = Math.max(0, Math.min(s.maxCaps, opLeft - v));
        int rest = opLeft - v - c;
        if (rest > 0) v = Math.min(s.maxVents, v + rest);
        return new int[]{v, c};
    }

    static Ship withFlux(Ship base, int vents, int caps) {
        Ship s = base.copy();
        s.vents = vents; s.caps = caps;
        s.diss += vents * 10f;
        s.cap += caps * 200f;
        return s;
    }

    /** MagicLib interference: with 2+ interference weapons, each costs (count - 1) x its strength in dissipation. */
    static float interferenceLoss(Ship s, List<Fit> fits) {
        int count = 0;
        for (Fit f : fits) if (f.w.interference > 0f) count++;
        if (count < 2) return 0f;
        float loss = 0f;
        for (Fit f : fits) if (f.w.interference > 0f) loss += (count - 1) * f.w.interference;
        return loss * s.interferenceMult;
    }

    /**
     * Share of a weapon's shots that land on a target of this size. Beams, wings and guided missiles track; slow,
     * inaccurate projectiles and unguided missiles miss small agile ships (a frigate dodges much of a 600-speed
     * spray; a capital doesn't).
     */
    static float accuracy(W w, int targetSize) { return accuracy(w, targetSize, 1f); }

    /** spreadMult: the ship's recoil (Damaged Weapon Mounts x1.3, Ill-Advised Modifications x2 widen every gun's spread). */
    static float accuracy(W w, int targetSize, float spreadMult) {
        if (w.wing || w.beam) return 1f;
        int ts = Math.max(0, Math.min(3, targetSize));
        float sz = SIZE_HIT[ts];
        // semi-guided missiles (GUIDED_POOR: Sabot, Atropos) home on the target: about 0.85 against frigates
        if (w.cls == MIS) return w.guided ? 1f : w.guidedPoor ? 1f - 0.27f * (1f - sz) : sz;
        float spread = w.spread * Math.max(0.1f, spreadMult);
        float q = 0.5f * Math.min(1f, w.projSpeed / 1000f) + 0.5f / (1f + spread / 5f);
        float acc = 1f - (1f - sz) * (1f - clamp(q, 0f, 1f));
        // wide-spread guns (flak, shotguns: Devastator 30 degrees) miss even big ships at range: the share of the
        // cone the target covers at 80% of the weapon's range
        if (spread > 10f && w.range > 0f) {
            float cone = (float) Math.toDegrees(2.0 * Math.atan(TARGET_RADIUS[ts] / (0.8f * w.range)));
            acc *= clamp(cone / spread, 0.15f, 1f);
        }
        return acc;
    }

    /** Rough collision radius of a frigate / destroyer / cruiser / capital, for spread. */
    static final float[] TARGET_RADIUS = {45f, 70f, 110f, 170f};

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    // ------------------------------------------------------------------ combat readiness, carrier deck, d-mods

    /** Every ship's max CR before skills and d-mods (CRPluginImpl "Basic maintenance"). */
    static final float CR_BASE = 0.7f;
    /**
     * Share of hits that depends on how well the AI aims: CRPluginImpl's aim accuracy is 1.5 x CR - 0.5 (0.55 at 70%,
     * 1 at 100%, 0 at 33%), and a gun crew that leads its shots badly still lands most of them on a ship.
     */
    static final float CR_AIM = 0.25f;
    /** Score lost per point of weapon malfunction chance (Ill-Advised Modifications 0.05 costs 7.5%). */
    static final float MALFUNCTION_COST = 1.5f;

    /**
     * CRPluginImpl's thresholds at the default malfunction range: weapon and engine malfunctions below 40%, critical
     * malfunctions below 20%, worse performance below 50%, better above 70% (the improve line doesn't move).
     */
    static final float CR_MALF = 0.4f, CR_CRIT = 0.2f, CR_DEGRADE = 0.5f, CR_IMPROVE = 0.7f;

    /** crEffect at the default thresholds. */
    static float crEffect(float c) { return crEffect(c, CR_MALF, CR_CRIT, CR_DEGRADE); }

    /**
     * What a deployment at combat readiness c (0-1) is worth against one at the usual 70%, by the game's CR rules
     * (CRPluginImpl): the AI aims worse as CR falls; above 70% damage, speed and damage taken get up to 10% better at
     * 100%; below the degrade line (50%) up to 10% worse at 0; weapons and engines malfunction below malf (40%: up to
     * 10% / 7.5%) and critically below crit (20%). The thresholds are the ship's own (JAOShip reads them from the game).
     * The user 11/06: the Coda Launcher (Seeker) takes max CR off its ship; so do d-mods.
     */
    static float crEffect(float c, float malf, float crit, float degrade) {
        c = clamp(c, 0f, 1f);
        float aim = clamp(1.5f * c - 0.5f, 0f, 1f);
        float e = (1f - CR_AIM + CR_AIM * aim) / (1f - CR_AIM + CR_AIM * 0.55f);
        if (c > CR_IMPROVE) e *= 1f + 0.12f * (c - CR_IMPROVE) / (1f - CR_IMPROVE);
        else if (degrade > 0f && c < degrade) e *= 1f - 0.12f * (degrade - c) / degrade;
        if (malf > 0f && c < malf) e *= 1f - 0.2f * (malf - c) / malf;
        if (crit > 0f && c < crit) e *= 1f - 0.4f * (crit - c) / crit;
        return Math.max(0.05f, e);
    }

    /**
     * The malfunction risk tier the game shows for CR c (CRPluginImpl.getMalfunctionString): 0 none, 1 Low (below the
     * malfunction threshold), 2 Serious (below the midpoint of the two thresholds; 0.98.5a, where 0.98a used a fixed 30%),
     * 3 Critical (below the critical threshold). The chances themselves are linear in CR in both versions, so the tier
     * is a label: the fit log names it.
     */
    static int crTier(float c, float malf, float crit) {
        if (c < crit) return 3;
        if (c < 0.5f * (crit + malf)) return 2;
        if (c < malf) return 1;
        return 0;
    }

    static final String[] CR_TIERS = {"None", "Low", "Serious", "Critical"};

    /** crStart at the default thresholds. */
    static float crStart(float maxCr, float deployCr) {
        Ship s = new Ship();
        s.deployCr = deployCr;
        return crStart(s, maxCr);
    }

    /**
     * Starting CR across a battle: the first deployment at max CR and the next one a deployment's CR lower (ships fight
     * again before they recover; max CR is also the ceiling CR recovers to). Plague weapons and d-mods that cut max CR
     * cost little on a healthy 70% ship and a lot on one already near the 50% and 40% lines.
     */
    static float crStart(Ship s, float maxCr) {
        if (s.deployCr <= 0f) return crEffect(maxCr, s.malfAt, s.critAt, s.degradeAt);
        return 0.5f * (crEffect(maxCr, s.malfAt, s.critAt, s.degradeAt) + crEffect(maxCr - s.deployCr, s.malfAt, s.critAt, s.degradeAt));
    }

    /**
     * The CR a refit away from a market costs (HullVariantSpec.computeVariantDiffCRImpact, the same in 0.98a and
     * 0.98.5a): removedOp = OP of what came off or was replaced (weapons and wings x crLossMultForRefitWeapons, hullmods x
     * crLossMultForRefitHullmods, one per vent or capacitor removed), allOp = the same for every change both ways, both
     * x crLossMultForRefit. The loss is the larger of removedOp / hull OP and allOp / hull OP, the second capped at what
     * the ship has above the malfunction threshold.
     */
    static float refitCrLoss(float removedOp, float allOp, float hullOp, float baseCr, float malf) {
        if (hullOp <= 0f) return 0f;
        return Math.max(removedOp / hullOp, Math.min(Math.max(0f, baseCr - malf), allOp / hullOp));
    }

    /**
     * Share of a fight an escort spends within reach of a bigger friendly ship, for the Escort Package s-mod (full effect
     * within 700 of it, fading out to 1200).
     */
    static final float ESCORT_UPTIME = 0.6f;

    /**
     * Escort Package as an s-mod (EscortPackage.applyEPEffect): {shield, armor} damage taken cut near a bigger friendly
     * ship. bonus = EscortPackage.SHIELD_BONUS (10%), doubled for a destroyer next to a capital. 0.98.5a (armorToo):
     * shields and armor, destroyers 20%, cruisers 10%. 0.98a: shields only, destroyers only. Frigates and capitals can't
     * mount it.
     */
    static float[] escortSMod(int size, boolean armorToo, float bonus) {
        if (size == 1) return new float[]{2f * bonus, armorToo ? 2f * bonus : 0f};
        if (size == 2 && armorToo) return new float[]{bonus, bonus};
        return new float[]{0f, 0f};
    }

    /**
     * A carrier's deck against the wings' own numbers (1 = vanilla deck): fighters away longer per refit (Damaged
     * Flight Deck +30% refit time: x0.88; 0.98.5a +25%: x0.89), a replacement rate that falls slower and recovers
     * faster (Expanded Deck Crew x0.85 / +25%: x1.08), a shorter leash (Malfunctioning Comms x0.6 engagement range:
     * x0.94; 0.98.5a x0.7: x0.955), times fighter d-mods applied to the craft themselves (fighterMult, JAOCompat.wingMult).
     */
    static float deckFactor(float refitMult, float decreaseMult, float increaseMult, float rangeMult, float fighterMult) {
        float r = refitMult > 0f ? refitMult : 1f, d = decreaseMult > 0f ? decreaseMult : 1f, in = increaseMult > 0f ? increaseMult : 1f;
        float f = (float) (Math.pow(1f / r, 0.5) * Math.pow(1f / d, 0.25) * Math.pow(in, 0.15));
        f *= 0.85f + 0.15f * clamp(rangeMult > 0f ? rangeMult : 1f, 0f, 1.5f);
        return clamp(f * (fighterMult > 0f ? fighterMult : 1f), 0.3f, 1.6f);
    }

    /**
     * Phase Anchor's value on a phase ship, as a fractional score bonus: 0.06 for free cloak activation and the
     * emergency dive, plus 0.06 per extra x1 of dissipation and weapon recharge while phased. 0.12 at 0.98a's x2,
     * 0.09 at 0.98.5a's x1.5 (JAOShip.phaseAnchorMult reads it from the game).
     */
    static float phaseAnchorExtra(float dissMult) {
        return PHASE_ANCHOR_BASE + PHASE_ANCHOR_PER_MULT * clamp(dissMult - 1f, 0f, 3f);
    }

    static final float PHASE_ANCHOR_BASE = 0.06f, PHASE_ANCHOR_PER_MULT = 0.06f;

    /** Point defense hits against the ship's turret turn rate (x0.75: 0.93, Advanced Turret Gyros x1.75: 1.15). */
    static float pdTrack(float turnMult) {
        return (float) Math.pow(clamp(turnMult > 0f ? turnMult : 1f, 0.4f, 2f), 0.25);
    }
}
