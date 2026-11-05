package data.scripts.jonahsautooutfitting;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a set of ships carries, and the pull it puts on one more ship (no game classes: the fleet plan, the fleet
 * analysis and the offline bench share it). User 11/05: Optimal across the whole fleet still came out short on
 * anti-shield and lopsided on fighter roles, because each ship balanced against the fleet as it stood, with a pull too
 * weak to change many picks, and anti-shield counted kinetic only.
 *  - Firepower by damage type: guns at sustained damage/s, dual-role PD guns at 0.6, finite missiles at their load over
 *    90 s, wings at their value (interceptors are point defense, plain PD shoots missiles: both left out).
 *  - Anti-shield = how fast the fleet fills enemy flux bars (user 11/05: "flux them out", then shred armor and hull):
 *    shield damage per point of damage, kinetic counting 1 (energy 0.5, HE 0.25, fragmentation 0.125). Beams count in
 *    full: the user notes their constant pressure suits the AI, which can't time a quick flux-out the way a player does.
 *    The user's 11/05 fleet was 54% fragmentation, mostly Industrial Evolution's Cryo Artillery, and 16% kinetic.
 *    Armor-cracking = hit x armor multiplier of 300 or more (HE 150+, energy 300+, torpedoes, burst beams).
 *  - Fighter bays by role (JAOModel.W_*) and copies of each wing.
 */
final class JAOFleetMix {
    /**
     * Firepower the fleet aims for: anti-shield half its damage (players run two kinetic guns to one HE; energy and
     * mixed fleets less) and 30% armor-cracking (research meta 4.2, build_philosophy R2.2).
     */
    static float SHIELD_TARGET = 0.50f, ARMOR_TARGET = 0.30f;
    /**
     * How hard a ship leans toward what the rest of the fleet is short of: its shield (armor) kill weight x (1 + PULL x
     * shortfall), held to LEAN_MIN..LEAN_MAX. The model puts half or more of a kill on the hull, so a gentle pull moved
     * nothing: on the user's 11/05 fleet (JAOFleetSim) anti-shield went 43% -> 44% at 2.5 / 1.6, 46% at 10 / 3, for
     * about 1% of the ships' own scores; 20 / 5 reached 50% but swung between passes.
     */
    static float PULL = 10f, LEAN_MIN = 0.6f, LEAN_MAX = 3f;
    /** Below this many other ships the fleet's mix says little (a lone ship fits for itself). */
    static final int MIN_SHIPS = 3;

    final float[] dmg = new float[4];
    float armorDps, shieldDps, total;
    final float[] wingBays = new float[4];
    final Map<String, Integer> wingIds = new TreeMap<>();
    int ships;

    /** One fitted weapon or wing. */
    void add(JAOModel.W w) {
        if (w == null) return;
        int d = Math.max(0, Math.min(3, w.dmg));
        if (w.wing) {
            int role = Math.max(0, Math.min(3, w.wingRole));
            wingBays[role] += 1f;
            wingIds.merge(w.id, 1, Integer::sum);
            if (w.interceptor || w.dps <= 0f) return;
            addDamage(d, w.dps, w.hit);
            return;
        }
        if (w.pdOnly || (w.pd && !w.pdAlso)) return;
        float dps = w.dps > 0f ? w.dps : w.pool / 90f;
        if (w.pd) dps *= 0.6f;
        if (dps > 0f) addDamage(d, dps, w.hit);
    }

    private void addDamage(int d, float dps, float hit) {
        dmg[d] += dps;
        total += dps;
        shieldDps += dps * JAOModel.SHIELD_MULT[d] / 2f;
        if (hit * JAOModel.ARMOR_MULT[d] >= 300f) armorDps += dps;
    }

    /** One ship's weapons and wings. */
    void addShip(List<JAOModel.W> ws) {
        for (JAOModel.W w : ws) add(w);
        ships++;
    }

    void add(JAOFleetMix o) {
        for (int i = 0; i < 4; i++) { dmg[i] += o.dmg[i]; wingBays[i] += o.wingBays[i]; }
        armorDps += o.armorDps;
        shieldDps += o.shieldDps;
        total += o.total;
        for (Map.Entry<String, Integer> e : o.wingIds.entrySet()) wingIds.merge(e.getKey(), e.getValue(), Integer::sum);
        ships += o.ships;
    }

    float kineticShare() { return total > 0f ? dmg[JAOModel.KIN] / total : 0f; }
    float shieldShare() { return total > 0f ? shieldDps / total : 0f; }
    float armorShare() { return total > 0f ? armorDps / total : 0f; }

    static float lean(float shortfall) {
        return JAOModel.clamp(1f + PULL * shortfall, LEAN_MIN, LEAN_MAX);
    }

    /**
     * Leans one ship's kill-phase weights toward what this mix (the rest of the fleet) is short of: more weight on
     * shields when anti-shield is scarce, on armor when armor-cracking is, less when there's plenty. Only with
     * MIN_SHIPS or more, and only for the Auto/Balanced styles (an explicit style is the player's).
     */
    void complement(JAOModel.Params p) {
        if (ships < MIN_SHIPS || total <= 0f) return;
        p.wS *= lean(SHIELD_TARGET - shieldShare());
        p.wA *= lean(ARMOR_TARGET - armorShare());
    }

    /** Wing balance inputs for the model: bays by role and copies of each wing in the rest of the fleet. */
    void wings(JAOModel.Params p) {
        p.fleetWingBays = wingBays.clone();
        p.fleetWingIds = new java.util.HashMap<>(wingIds);
    }

    /** "kinetic 28%, anti-shield 38%, armor-cracking 49%, bays 17/1/1/7" for logs. */
    String describe() {
        return "kinetic " + Math.round(100f * kineticShare()) + "%, anti-shield " + Math.round(100f * shieldShare()) + "%, armor-cracking "
                + Math.round(100f * armorShare()) + "%, bays " + (int) wingBays[0] + "/" + (int) wingBays[1] + "/" + (int) wingBays[2] + "/" + (int) wingBays[3];
    }
}
