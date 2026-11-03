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
    /** Point defense a ship needs: about 1 / 2 / 3 / 4.5 Burst PD lasers (research weapon_eval d.13; was 3.6x that). */
    static final float[] PD_NEED = {35f, 65f, 100f, 150f};
    static final float OFFENSE_W = 0.6f;
    /** Speed counts for every role at least this much (exponent on speed against the hull's own). */
    static final float MIN_MOBILITY = 0.1f;
    /** How much of a poor shot's damage still lands on a frigate / destroyer / cruiser / capital. */
    static final float[] SIZE_HIT = {0.45f, 0.7f, 0.88f, 1f};
    /**
     * Burst windows: share of armor and hull damage dealt while the target can't block (shield down, overloaded,
     * flanked), the capacity spent on them and how long they last. Load over budget the AI won't fire even then.
     */
    static final float BURST_SHARE = 0.5f, BURST_CAP = 0.6f, BURST_SECONDS = 10f, FLUX_LIMIT = 1.75f, FLUX_SOFT = 1.4f, FLUX_SOFT_EXP = 0.3f;
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
     * ship, this wing included): roles past their share of WING_MIX lose value, missing ones gain (0.6-1.3).
     */
    static float wingRoleFactor(int role, float[] bays) {
        float total = 0f;
        for (float b : bays) total += b;
        if (total <= 0f) return 1f;
        float share = bays[Math.max(0, Math.min(3, role))] / total;
        return clamp(1f + 0.8f * (WING_MIX[Math.max(0, Math.min(3, role))] - share), 0.6f, 1.3f);
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
        /** Scripted extras (jao_weapon_effects.csv). */
        float shieldMultExtra = 1f, armorExtraPerHit, hitsPerSec, arcDps, shieldDebuff = 1f;
        /** 0..1 rarity score (only used with Prefer rare weapons). */
        float rarity;
        /** MagicLib interference: dissipation lost per other interference weapon on the ship (20/40/80). */
        float interference;
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
            return s;
        }
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
        float r = (base + base * pct / 100f + flat) * s.rangeMult[w.cls] * (w.beam ? s.beamMult : 1f);
        if (w.cls != MIS && r > s.threshold) r = s.threshold + (r - s.threshold) * s.pastThreshold;
        return r;
    }

    static float armorFactor(float hit, float armor) {
        if (hit <= 0f) return 1f - MAX_DR;
        return Math.max(1f - MAX_DR, hit / (hit + armor));
    }

    /** Per-weapon numbers on this ship, filled by evaluate(). */
    static final class Eff {
        float dps, flux, range, pool;
        boolean front;
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
            float dm = w.wing ? 1f : s.dmgMult[w.cls] * (w.beam ? s.beamDmgMult : 1f);
            if (w.beam && s.hsa) dm *= 1.1f;
            x.dps = w.dps * dm * rof;
            x.flux = w.wing ? 0f : w.flux * s.fluxMult[w.cls] * (w.beam ? s.beamFluxMult : 1f) * rof;
            x.pool = w.pool * dm * (w.cls == MIS ? s.ammoMult : 1f);
            // a slower missile rate (Expanded Missile Racks' s-mod penalty) delivers less of the pool within a fight
            if (w.cls == MIS && !w.wing) x.pool *= Math.min(1f, rof);
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
        float budget = Math.max(1f, diss - (s.shield ? s.upkeep : 0f));
        float uptime = load <= 0f ? 1f : Math.min(1f, (budget * ENGAGE_SECONDS + 0.5f * s.cap) / (load * ENGAGE_SECONDS));
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
        for (float[] g : groups.values()) if (g[0] > best) { best = g[0]; reng = g[1]; }
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

        // fighter bays by role across the fleet and this ship, for the role balance of each wing
        float[] bays = p.fleetWingBays.clone();
        Map<String, Integer> sameWing = new HashMap<>();
        int shipWings = 0;
        boolean shipBomber = false;
        for (Fit f : fits) {
            if (!f.w.wing) continue;
            bays[Math.max(0, Math.min(3, f.w.wingRole))] += 1f;
            shipWings++;
            shipBomber |= f.w.wingRole == W_BOMBER;
        }
        // interceptors only escort (their point defense job) when the carrier has no bomber and may auto-escort
        float escort = shipBomber || s.noAutoEscort ? INTERCEPTOR_NO_ESCORT : 1f;

        // per-weapon effective dps and the shares that matter per phase: the shield phase is a flux war at the
        // sustained rate; armor and hull go down half in burst windows (shield down, overload, flanks)
        float[] eff = new float[n], effB = new float[n];
        float pd = 0f, missilePool = 0f, missilePoolStrike = 0f;
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
                float hitf = w.beam || w.wing ? 1f : Math.min(1f, w.projSpeed / 900f) * Math.min(1f, 10f / Math.max(1f, w.spread));
                float reachPd = clamp(x.range / 500f, 0.5f, 1.2f);
                pd += x.dps * s.pdDmg * hitf * fits.get(i).cover * reachPd * (w.wing && w.interceptor ? escort : 1f);
            } else if (w.wing && w.wingPd > 0f) {
                // multi-role fighters screen against missiles and fighters with their PD-capable guns, at half weight
                pd += 0.5f * w.wingPd * x.dps * s.pdDmg;
            }
            if (x.pool > 0f && x.dps <= 0f) {
                // finite missiles: unguided ones only count when they can face the target
                float use = (w.guided || x.front) ? 1f : 0.25f;
                if (w.strike) missilePoolStrike += x.pool * use; else missilePool += x.pool * use;
            }
        }

        // time to kill each reference target
        float ttkSum = 0f, wsum = 0f, t1s = 0f, t2s = 0f, t3s = 0f;
        for (Target t : p.targets) {
            float[] ph = phases(fits, e, eff, effB, t, p, missilePool, missilePoolStrike);
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
            shieldHp = tank * (s.cap + 30f * Math.max(0f, diss - s.upkeep - 0.5f * load)) / eF;
        }
        float ka = KA[Math.max(0, Math.min(3, s.size))];
        float ehp = s.hull / Math.max(0.1f, s.hullTaken) + ka * s.armor / Math.max(0.1f, s.armorTaken) + shieldHp;
        if (s.phase) ehp *= 1.3f; // phase ships avoid much of the damage; refined later
        // peak time against a whole battle (several engagements), not one fight: a cut peak time (Safety
        // Overrides x0.33) costs combat readiness unless Hardened Subsystems offsets it
        float battle = Math.max(180f, 2f * p.fightSeconds);
        float cr = s.ppt > 0f ? 1f - 0.5f * Math.max(0f, battle - s.ppt) / battle : 1f;
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
        float pdFactor = need <= 0f ? 1f : 0.9f + 0.1f * Math.min(1f, pd / need);
        if (s.size >= 1 && need > 0f && pd < 0.5f * need) pdFactor *= 0.95f;

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
        float mobility = (s.baseSpeed > 0f ? (float) Math.pow(Math.max(0.2f, s.speed / s.baseSpeed), mw) : 1f)
                * (float) Math.pow(Math.max(0.2f, s.maneuver), 0.5f * mw + 0.04f);
        float score = (float) (Math.pow(offense, p.offenseW) * Math.pow(ehp * outrange, 1f - p.offenseW)) * cr * pdFactor * fluxFactor * groupFactor * capPenalty * mobility * cover * (1f + s.extra);
        if (out != null) {
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
        float hard = 0f, soft = 0f, debuff = 1f, armorExtra = 0f, arc = 0f;
        int n = fits.size();
        float[] eff = new float[n], effB = new float[n];
        for (int i = 0; i < n; i++) {
            float acc = accuracy(fits.get(i).w, t.size);
            eff[i] = effAll[i] * acc;
            effB[i] = effBurst[i] * acc;
        }
        float missileHit = SIZE_HIT[Math.max(0, Math.min(3, t.size))];
        pool *= 0.5f + 0.5f * missileHit;
        poolStrike *= missileHit;
        // shields go down in the same mix of sustained fire and capacity dumps (the AI opens every group while its
        // flux is all hard, to force an overload), so kinetic and HE are judged on the same footing
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            if (effB[i] <= 0f) continue;
            float sm = SHIELD_MULT[w.dmg] * w.shieldMultExtra;
            float vsShield = effB[i] * sm;
            // the AI holds flux-inefficient weapons against shields once it is above 40% flux (mechanics 3.6)
            if (!w.fireWhenInefficient && !w.wing && w.dps > 0f && t.shieldEff * sm < 0.5f * (e[i].flux / Math.max(1f, e[i].dps))) vsShield *= 0.5f;
            if (w.soft) soft += vsShield; else hard += vsShield;
            if (w.shieldDebuff > 1f) debuff = Math.max(debuff, w.shieldDebuff);
            armorExtra += w.armorExtraPerHit * w.hitsPerSec;
            arc += w.arcDps;
        }
        // missiles: kinetic ones go into shields, the rest after the shield is down
        float missileWindow = Math.max(10f, p.fightSeconds);
        float t1 = 0f;
        if (t.shield) {
            float rate = (hard + Math.max(0f, soft - t.diss)) * t.shieldEff * debuff;
            rate += missileShield(fits, e, missileWindow);
            t1 = rate <= 0.01f ? 600f : Math.min(600f, 0.6f * t.cap / rate);
        }
        // armor: pooled armor from A down to 0.2A in steps
        float t2 = 0f, a = t.armor, floor = t.armor * MIN_ARMOR;
        float missileArmor = (pool + (t.size >= 1 ? poolStrike : 0f)) / missileWindow;
        int steps = 8;
        float chunk = t.armor * 0.8f / steps;
        for (int k = 0; k < steps; k++) {
            float pa = Math.max(a, floor);
            float rate = armorExtra + 0.5f * missileArmor;
            for (int i = 0; i < n; i++) {
                if (effB[i] <= 0f) continue;
                W w = fits.get(i).w;
                float am = ARMOR_MULT[w.dmg];
                rate += 0.8f * effB[i] * am * armorFactor(w.hit * am, pa);
            }
            t2 += rate <= 0.01f ? 600f / steps : chunk / rate;
            a -= chunk;
        }
        t2 = Math.min(600f, t2 * 1.5f);
        // hull, through the leftover armor
        float hullRate = arc + 0.5f * missileArmor;
        for (int i = 0; i < n; i++) {
            if (effB[i] <= 0f) continue;
            W w = fits.get(i).w;
            hullRate += effB[i] * armorFactor(w.hit * ARMOR_MULT[w.dmg], floor);
        }
        float t3 = hullRate <= 0.01f ? 600f : Math.min(600f, t.hull / hullRate);
        return new float[]{t1, t2, t3};
    }

    static float missileShield(List<Fit> fits, Eff[] e, float window) {
        float r = 0f;
        for (int i = 0; i < fits.size(); i++) {
            W w = fits.get(i).w;
            if (e[i].pool <= 0f || e[i].dps > 0f) continue;
            if (w.dmg == KIN || w.shieldMultExtra > 1f) r += e[i].pool * SHIELD_MULT[w.dmg] * w.shieldMultExtra / window;
        }
        return r;
    }

    // ------------------------------------------------------------------ vents and capacitors

    /** Vents to cover the weapon load (+10%), then capacitors, then any OP left to vents. Returns {vents, caps}. */
    static int[] fluxFill(Ship s, List<Fit> fits, int opLeft) {
        float load = 0f;
        for (Fit f : fits) {
            W w = f.w;
            if (w.wing) continue;
            float fl = w.flux * s.fluxMult[w.cls] * (w.beam ? s.beamFluxMult : 1f) * s.rofMult[w.cls];
            load += w.pd ? 0.5f * fl : fl;
        }
        float want = load * 1.1f + (s.shield ? s.upkeep : 0f) - (s.diss - interferenceLoss(s, fits));
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
    static float accuracy(W w, int targetSize) {
        if (w.wing || w.beam) return 1f;
        int ts = Math.max(0, Math.min(3, targetSize));
        float sz = SIZE_HIT[ts];
        // semi-guided missiles (GUIDED_POOR: Sabot, Atropos) home on the target: about 0.85 against frigates
        if (w.cls == MIS) return w.guided ? 1f : w.guidedPoor ? 1f - 0.27f * (1f - sz) : sz;
        float q = 0.5f * Math.min(1f, w.projSpeed / 1000f) + 0.5f / (1f + w.spread / 5f);
        float acc = 1f - (1f - sz) * (1f - clamp(q, 0f, 1f));
        // wide-spread guns (flak, shotguns: Devastator 30 degrees) miss even big ships at range: the share of the
        // cone the target covers at 80% of the weapon's range
        if (w.spread > 10f && w.range > 0f) {
            float cone = (float) Math.toDegrees(2.0 * Math.atan(TARGET_RADIUS[ts] / (0.8f * w.range)));
            acc *= clamp(cone / w.spread, 0.15f, 1f);
        }
        return acc;
    }

    /** Rough collision radius of a frigate / destroyer / cruiser / capital, for spread. */
    static final float[] TARGET_RADIUS = {45f, 70f, 110f, 170f};

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
