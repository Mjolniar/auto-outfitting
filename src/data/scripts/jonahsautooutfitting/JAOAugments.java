package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ESR augment placement for Optimize fleet (0.5.52, user 11/15: every Extra System Reloaded augment goes to the ship where
 * it does the most for the fleet; "Place augments" in the Optimize fleet panel, on by default, shown with ESR installed).
 *  - Only at a friendly market with a spaceport, where ESR's Extra Systems bay is (installing there is free).
 *  - Augments that move: those in fleet cargo, in this market's storage, and on the fleet's warships. With ESR's
 *    "Removing destroys the augment" on, installed augments stay where they are and only free slots are filled.
 *  - Ships that take one: warships in the plan that aren't locked or mothballed; slots by hull size (ESR's settings),
 *    one of each augment per ship, ESR's own requirement (ship system, phase, fighter bays, missile slots).
 *  - Each augment on each ship is worth the ship's build score holding it minus without (JAOFleetPlan.scoreWithAugments).
 *    The best pairs are taken first; an augment already on a ship counts STAY more there. An installed augment that
 *    isn't placed elsewhere and isn't pushed out of its slot stays where it is.
 *  - The fleet plan then fits every ship for its new augments; Apply moves them after the refit.
 */
final class JAOAugments {
    /** A move must gain more than this share over staying put. */
    static final float STAY = 0.03f;
    /** Sector persistent data key of the "Place augments" box (per save; on by default). */
    static final String PLACE = "jao_placeAugments";

    final JAOFleetPlan fleet;
    final CargoAPI cargo, storage;
    final List<FleetMemberAPI> ships = new ArrayList<>();
    final List<Set<String>> now = new ArrayList<>(), fixed = new ArrayList<>();
    final List<String> types = new ArrayList<>();
    /** Augments free to place by id: cargo, storage, and installed ones that may move. */
    final Map<String, Integer> pool = new TreeMap<>();
    int[] slots;
    float[] base;
    float[][] value;
    private int next;
    boolean done;
    /** Member id -> the ship's augments after placement, only where they change. */
    final Map<String, Set<String>> target = new LinkedHashMap<>();
    /** "Ship: +Augment, -Augment" lines for the panel and log. */
    final List<String> moves = new ArrayList<>();
    /** Augments the placement couldn't use anywhere (the model sees no gain on any ship). */
    final List<String> unused = new ArrayList<>();

    static boolean place() {
        try { return !Boolean.FALSE.equals(Global.getSector().getPersistentData().get(PLACE)); } catch (Throwable t) { return true; }
    }

    static void setPlace(boolean on) {
        try { Global.getSector().getPersistentData().put(PLACE, on); } catch (Throwable ignored) { }
    }

    /** ESR's Extra Systems bay is here: a friendly market with a spaceport. */
    static boolean bayHere(MarketAPI market) {
        try {
            return market != null && market.hasSpaceport() && !market.getFaction().isHostileTo(Factions.PLAYER);
        } catch (Throwable t) {
            return false;
        }
    }

    JAOAugments(JAOFleetPlan fleet, CargoAPI cargo, CargoAPI storage) {
        this.fleet = fleet;
        this.cargo = cargo;
        this.storage = storage;
        boolean destroys = JAOEsr.removalDestroys();
        for (JAOFleetPlan.ShipPlan s : fleet.ships) {
            FleetMemberAPI m = s.member;
            if (s.civilian || s.locked || m == null || m.isFighterWing() || m.isMothballed()) continue;
            int n = JAOEsr.slots(m.getHullSpec().getHullSize());
            Set<String> has = new LinkedHashSet<>(JAOEsr.installed(m));
            if (n <= 0 && has.isEmpty()) continue;
            ships.add(m);
            now.add(has);
            fixed.add(destroys ? new LinkedHashSet<>(has) : new LinkedHashSet<>());
            if (!destroys) for (String id : has) pool.merge(id, 1, Integer::sum);
        }
        for (String id : JAOEsr.IDS) {
            int c = JAOEsr.count(cargo, id) + JAOEsr.count(storage, id);
            if (c > 0) pool.merge(id, c, Integer::sum);
        }
        for (String id : JAOEsr.IDS) if (pool.getOrDefault(id, 0) > 0) types.add(id);
        slots = new int[ships.size()];
        for (int i = 0; i < ships.size(); i++) slots[i] = JAOEsr.slots(ships.get(i).getHullSpec().getHullSize());
        base = new float[ships.size()];
        value = new float[types.size()][ships.size()];
        done = ships.isEmpty() || types.isEmpty();
        if (done) finish();
    }

    /** Scores pairs for about budgetMs; true when placement is done. */
    boolean step(long budgetMs) {
        if (done) return true;
        long end = System.nanoTime() + budgetMs * 1_000_000L;
        int n = ships.size(), cells = n * (types.size() + 1);
        while (next < cells && System.nanoTime() < end) {
            int s = next % n, t = next / n - 1;
            FleetMemberAPI m = ships.get(s);
            try {
                if (t < 0) base[s] = fleet.scoreWithAugments(m, fixed.get(s));
                else {
                    String id = types.get(t);
                    if (fixed.get(s).contains(id) || !JAOEsr.canInstall(m, id)) value[t][s] = -1f;
                    else {
                        Set<String> with = new LinkedHashSet<>(fixed.get(s));
                        with.add(id);
                        value[t][s] = fleet.scoreWithAugments(m, with) - base[s];
                    }
                }
            } catch (Throwable e) {
                Global.getLogger(JAOAugments.class).warn("Auto Outfitting: augment placement couldn't score " + m.getShipName(), e);
                if (t >= 0) value[t][s] = -1f;
            }
            next++;
        }
        if (next < cells) return false;
        finish();
        return true;
    }

    String progress() {
        int n = Math.max(1, ships.size()), cells = n * (types.size() + 1);
        return "Placing augments (" + Math.min(next, cells) + " of " + cells + ")";
    }

    /**
     * Placements for the best total value: v[t][s] is augment type t on ship s (0 or less: not there), avail[t] items of
     * each type, left[s] free slots, has[s] the types a ship holds already (one of each per ship). Best pair first, then
     * swaps of two placements' types, moves to a free slot and trades for an unplaced type while they gain. Returns
     * {type, ship} pairs; avail, left and has are updated.
     */
    static List<int[]> assign(float[][] v, int[] avail, int[] left, List<Set<Integer>> has) {
        int types = v.length, ships = types == 0 ? 0 : v[0].length;
        List<int[]> placed = new ArrayList<>();
        while (true) {
            int bt = -1, bs = -1;
            float best = 0f;
            for (int t = 0; t < types; t++) {
                if (avail[t] <= 0) continue;
                for (int s = 0; s < ships; s++) if (left[s] > 0 && !has.get(s).contains(t) && v[t][s] > best) { best = v[t][s]; bt = t; bs = s; }
            }
            if (bt < 0) break;
            placed.add(new int[]{bt, bs});
            has.get(bs).add(bt);
            left[bs]--;
            avail[bt]--;
        }
        for (int iter = 0; iter < 200; iter++) {
            boolean better = false;
            for (int i = 0; i < placed.size(); i++) {
                for (int j = i + 1; j < placed.size(); j++) {
                    int[] a = placed.get(i), b = placed.get(j);
                    int ta = a[0], sa = a[1], tb = b[0], sb = b[1];
                    if (sa == sb || ta == tb || has.get(sa).contains(tb) || has.get(sb).contains(ta)) continue;
                    if (v[tb][sa] <= 0f || v[ta][sb] <= 0f) continue;
                    if (v[tb][sa] + v[ta][sb] > (v[ta][sa] + v[tb][sb]) * 1.0001f + 1e-6f) {
                        has.get(sa).remove(ta); has.get(sa).add(tb);
                        has.get(sb).remove(tb); has.get(sb).add(ta);
                        a[0] = tb; b[0] = ta;
                        better = true;
                    }
                }
            }
            for (int[] a : placed) {
                for (int s = 0; s < ships; s++) {
                    if (s == a[1] || left[s] <= 0 || has.get(s).contains(a[0]) || v[a[0]][s] <= v[a[0]][a[1]] * 1.0001f + 1e-6f) continue;
                    has.get(a[1]).remove(a[0]); left[a[1]]++;
                    has.get(s).add(a[0]); left[s]--;
                    a[1] = s;
                    better = true;
                }
                for (int t = 0; t < types; t++) {
                    if (avail[t] <= 0 || t == a[0] || has.get(a[1]).contains(t) || v[t][a[1]] <= v[a[0]][a[1]] * 1.0001f + 1e-6f) continue;
                    has.get(a[1]).remove(a[0]); avail[a[0]]++;
                    has.get(a[1]).add(t); avail[t]--;
                    a[0] = t;
                    better = true;
                }
            }
            if (!better) break;
        }
        return placed;
    }

    private void finish() {
        done = true;
        int n = ships.size(), nt = types.size();
        // staying put counts STAY more
        float[][] v = new float[nt][n];
        for (int t = 0; t < nt; t++) for (int s = 0; s < n; s++) {
            float x = value[t][s];
            v[t][s] = x > 0f && now.get(s).contains(types.get(t)) ? x * (1f + STAY) : x;
        }
        int[] availT = new int[nt], left = new int[n];
        for (int t = 0; t < nt; t++) availT[t] = pool.getOrDefault(types.get(t), 0);
        List<Set<Integer>> hasT = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            Set<Integer> h = new LinkedHashSet<>();
            for (String id : fixed.get(s)) if (types.contains(id)) h.add(types.indexOf(id));
            hasT.add(h);
            left[s] = slots[s] - fixed.get(s).size();
        }
        assign(v, availT, left, hasT);
        List<Set<String>> has = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            Set<String> h = new LinkedHashSet<>(fixed.get(s));
            for (int t : hasT.get(s)) h.add(types.get(t));
            has.add(h);
        }
        Map<String, Integer> avail = new TreeMap<>();
        for (int t = 0; t < nt; t++) avail.put(types.get(t), availT[t]);
        // an installed augment nobody placed stays on its ship while it has a slot
        for (int s = 0; s < n; s++) {
            for (String id : now.get(s)) {
                if (has.get(s).contains(id) || left[s] <= 0 || avail.getOrDefault(id, 0) <= 0) continue;
                has.get(s).add(id);
                left[s]--;
                avail.merge(id, -1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : avail.entrySet()) if (e.getValue() > 0) unused.add(e.getValue() + " " + JAOEsr.name(e.getKey()));
        for (int s = 0; s < n; s++) {
            Set<String> after = has.get(s), before = now.get(s);
            if (after.equals(before)) continue;
            target.put(ships.get(s).getId(), after);
            List<String> parts = new ArrayList<>();
            for (String id : after) if (!before.contains(id)) parts.add("+" + JAOEsr.name(id));
            for (String id : before) if (!after.contains(id)) parts.add("-" + JAOEsr.name(id));
            moves.add(JAOOfficers.shipName(ships.get(s)) + ": " + String.join(", ", parts));
        }
        fleet.augmentPlan.putAll(target);
        if (!moves.isEmpty()) Global.getLogger(JAOAugments.class).info("Auto Outfitting: augment placement: " + String.join("; ", moves));
    }

    /**
     * Moves the augments (after the refit): first off the ships that give one up, into cargo, then onto their new ships
     * from cargo, then storage. Returns augments installed.
     */
    int apply() {
        if (target.isEmpty()) return 0;
        Map<String, FleetMemberAPI> byId = new LinkedHashMap<>();
        for (FleetMemberAPI m : ships) byId.put(m.getId(), m);
        for (Map.Entry<String, Set<String>> e : target.entrySet()) {
            FleetMemberAPI m = byId.get(e.getKey());
            if (m == null) continue;
            for (String id : new ArrayList<>(JAOEsr.installed(m))) if (!e.getValue().contains(id)) JAOEsr.uninstall(m, id, cargo);
        }
        int installed = 0;
        for (Map.Entry<String, Set<String>> e : target.entrySet()) {
            FleetMemberAPI m = byId.get(e.getKey());
            if (m == null) continue;
            Set<String> has = JAOEsr.installed(m);
            for (String id : e.getValue()) {
                if (has.contains(id)) continue;
                if (!JAOEsr.take(cargo, storage, id)) {
                    Global.getLogger(JAOAugments.class).warn("Auto Outfitting: augment " + id + " for " + m.getShipName() + " wasn't in cargo or storage any more");
                    continue;
                }
                if (JAOEsr.install(m, id)) installed++;
                else cargo.addSpecial(new com.fs.starfarer.api.campaign.SpecialItemData(id, null), 1f);
            }
        }
        Global.getLogger(JAOAugments.class).info("Auto Outfitting: augments placed: " + String.join("; ", moves));
        return installed;
    }
}
