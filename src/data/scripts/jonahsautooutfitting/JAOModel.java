package data.scripts.jonahsautooutfitting;

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
    static final float[] PD_NEED = {100f, 200f, 350f, 500f};
    static final float OFFENSE_W = 0.6f;
    /** Speed counts for every role at least this much (exponent on speed against the hull's own). */
    static final float MIN_MOBILITY = 0.1f;
    /** How much of a poor shot's damage still lands on a frigate / destroyer / cruiser / capital. */
    static final float[] SIZE_HIT = {0.45f, 0.7f, 0.88f, 1f};

    /** One weapon (or fighter wing) before ship bonuses. */
    static final class W {
        String id, name;
        int size, cls, dmg = EN;
        boolean beam, soft, pd, pdOnly, strike, guided, guidedPoor, fireWhenInefficient, wing, interceptor, bomber;
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

        Fit(W w, boolean hardpoint, float cover, boolean front) { this.w = w; this.hardpoint = hardpoint; this.cover = cover; this.front = front; }
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
        }
        /** 0 auto, 1 long, 2 close. */
        int rangePref;
        Target[] targets;
    }

    // ------------------------------------------------------------------ per weapon

    static float range(W w, Ship s) {
        if (w.wing) return w.range;
        float base = w.range;
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
                load += w.pd ? 0.5f * x.flux : x.flux;
                rminAll = Math.min(rminAll, x.range);
            }
        }

        // MagicLib interference eats dissipation; then the flux budget the AI fires within (mechanics 3.4, 8.3)
        float diss = s.diss - interferenceLoss(s, fits);
        float budget = Math.max(1f, diss - (s.shield ? s.upkeep : 0f));
        float uptime = load <= 0f ? 1f : Math.min(1f, (budget * ENGAGE_SECONDS + 0.5f * s.cap) / (load * ENGAGE_SECONDS));
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

        // per-weapon effective dps and the shares that matter per phase
        float[] eff = new float[n];
        float pd = 0f, missilePool = 0f, missilePoolStrike = 0f;
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            Eff x = e[i];
            // in range at the main distance, and when the AI closes in
            float atFar = x.range >= reng ? 1f : (x.range / reng) * (x.range / reng);
            float atNear = x.range >= rclose ? 1f : (x.range / rclose) * (x.range / rclose);
            float inRange = w.wing ? 1f : (1f - close) * atFar + close * atNear;
            float d = w.wing ? x.dps * p.wingMult : x.dps * uptime * inRange * reach;
            if (w.pdOnly) d = 0f;
            else if (w.pd) d *= 0.5f; // PD weapons engage missiles and fighters first
            eff[i] = d;
            if (w.pd || w.pdOnly || w.interceptor) {
                float hitf = w.beam || w.wing ? 1f : Math.min(1f, w.projSpeed / 900f) * Math.min(1f, 10f / Math.max(1f, w.spread));
                float reachPd = clamp(x.range / 500f, 0.5f, 1.2f);
                pd += x.dps * s.pdDmg * hitf * fits.get(i).cover * reachPd;
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
            float[] ph = phases(fits, e, eff, t, p, missilePool, missilePoolStrike);
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

        // point defense floor (destroyers and up need some)
        float need = PD_NEED[Math.max(0, Math.min(3, s.size))] * p.pdFloor * p.pdMult;
        float pdFactor = need <= 0f ? 1f : 0.85f + 0.15f * Math.min(1f, pd / need);
        if (s.size >= 1 && need > 0f && pd < 0.5f * need) pdFactor *= 0.9f;

        // over budget: the AI switches groups off, so extra guns waste OP (mechanics 3.4: cap about 1.3-1.5x)
        float fluxFactor = overflux <= 1.3f ? 1f : (float) Math.pow(1.3f / overflux, 2);

        // offense weighs more than survival: more firepower also ends fights sooner, covers more targets and
        // flanks, and the AI fights in fleets, not duels (community rule: weapons first, research meta_report 10)
        // speed and handling against the hull's own: every ship has to reach the fight and turn its guns (big ships
        // that arrive late lose battles), frigates live on it; so hullmods and s-mod penalties that slow a ship cost
        float mw = Math.max(MIN_MOBILITY, p.mobilityW);
        float mobility = (s.baseSpeed > 0f ? (float) Math.pow(Math.max(0.2f, s.speed / s.baseSpeed), mw) : 1f)
                * (float) Math.pow(Math.max(0.2f, s.maneuver), 0.5f * mw + 0.04f);
        float score = (float) (Math.pow(offense, p.offenseW) * Math.pow(ehp * outrange, 1f - p.offenseW)) * cr * pdFactor * fluxFactor * capPenalty * mobility * (1f + s.extra);
        if (out != null) {
            out.put("score", score); out.put("ttk", ttk); out.put("t1", t1s / wsum); out.put("t2", t2s / wsum); out.put("t3", t3s / wsum);
            out.put("ehp", ehp); out.put("uptime", uptime); out.put("load", load); out.put("budget", budget); out.put("reng", reng);
            out.put("pd", pd); out.put("pdNeed", need); out.put("outrange", outrange); out.put("rclose", rclose);
        }
        return score;
    }

    /** Seconds to break the shield, strip the armor and kill the hull of t (mechanics 8.6). */
    static float[] phases(List<Fit> fits, Eff[] e, float[] effAll, Target t, Params p, float pool, float poolStrike) {
        float hard = 0f, soft = 0f, debuff = 1f, armorExtra = 0f, arc = 0f;
        int n = fits.size();
        float[] eff = new float[n];
        for (int i = 0; i < n; i++) eff[i] = effAll[i] * accuracy(fits.get(i).w, t.size);
        float missileHit = SIZE_HIT[Math.max(0, Math.min(3, t.size))];
        pool *= 0.5f + 0.5f * missileHit;
        poolStrike *= missileHit;
        for (int i = 0; i < n; i++) {
            W w = fits.get(i).w;
            if (eff[i] <= 0f) continue;
            float sm = SHIELD_MULT[w.dmg] * w.shieldMultExtra;
            float vsShield = eff[i] * sm;
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
                if (eff[i] <= 0f) continue;
                W w = fits.get(i).w;
                float am = ARMOR_MULT[w.dmg];
                rate += 0.8f * eff[i] * am * armorFactor(w.hit * am, pa);
            }
            t2 += rate <= 0.01f ? 600f / steps : chunk / rate;
            a -= chunk;
        }
        t2 = Math.min(600f, t2 * 1.5f);
        // hull, through the leftover armor
        float hullRate = arc + 0.5f * missileArmor;
        for (int i = 0; i < n; i++) {
            if (eff[i] <= 0f) continue;
            W w = fits.get(i).w;
            hullRate += eff[i] * armorFactor(w.hit * ARMOR_MULT[w.dmg], floor);
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
        float sz = SIZE_HIT[Math.max(0, Math.min(3, targetSize))];
        if (w.cls == MIS) return w.guided ? 1f : sz;
        float q = 0.5f * Math.min(1f, w.projSpeed / 1000f) + 0.5f / (1f + w.spread / 5f);
        return 1f - (1f - sz) * (1f - clamp(q, 0f, 1f));
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
