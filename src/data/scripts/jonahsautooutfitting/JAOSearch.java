package data.scripts.jonahsautooutfitting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The build search, with no game classes in it (the game side comes in through World), so the offline check can run it.
 *
 * Every evaluation fills the OP left over with vents and capacitors first (JAOModel.fluxFill), so a weapon or
 * hullmod only wins when it beats spending its OP on flux. Steps: greedy weapon fill (big slots first), greedy
 * hullmods, then swap passes over weapons and hullmods until nothing improves. Deterministic: candidates are in a
 * fixed order and only strict improvements are taken, so the dialog's preview and Confirm runs give the same fit.
 */
final class JAOSearch {
    interface World {
        /** Ship numbers with exactly these (non-built-in) hullmods, before vents/caps. */
        JAOModel.Ship ship(Set<String> mods);
        int hullOp();
        int modOp(String mod);
        /** Whether mod can go on next to mods (applicability, incompatibilities, items, rules). */
        boolean canAdd(String mod, Set<String> mods);
        /** OP of a candidate with these hullmods on (some hullmods change weapon OP). */
        float op(Cand c, Set<String> mods);
        /**
         * Its list OP before any discount, for how much of a mount it uses (a 2-OP IR-PD Cannon costs 0 on a Bryncore
         * hull but still fills its mount).
         */
        default float baseOp(Cand c) { return op(c, Collections.emptySet()); }
        /**
         * Filler hullmods (hull-only mods like Reinforced Bulkheads and Blast Doors, Flux Distributor/Coil) are only
         * tried once vents and capacitors are maxed: every combat use of OP comes first (research meta_report.md 10.8-9).
         */
        boolean filler(String mod);
        /** Hullmods treated as s-mods in ship() and the OP/applicability checks (0 OP, s-mod effects). */
        void setSMods(Set<String> smods);
    }

    static final class Cand {
        final JAOModel.W w;
        /** The delegate's own entry (AvailableWeapon / AvailableFighter), null offline. */
        final Object entry;
        final String stockKey;
        final float price;

        Cand(JAOModel.W w, Object entry, String stockKey, float price) { this.w = w; this.entry = entry; this.stockKey = stockKey; this.price = price; }
    }

    static final class Slot {
        final String id;
        final int size;
        final boolean hardpoint, front, bay;
        final float cover;
        final List<Cand> cands = new ArrayList<>();
        /** Kept as it is (not stripped, or owned by mod code). */
        Cand fixed;
        Cand pick;
        /** Share of its fire reaching the main target (JAOModel.bearing), set by the caller. */
        float bear = 1f;

        Slot(String id, int size, boolean hardpoint, float cover, boolean front, boolean bay) {
            this.id = id; this.size = size; this.hardpoint = hardpoint; this.cover = cover; this.front = front; this.bay = bay;
        }
    }

    final World world;
    final JAOModel.Params params;
    final List<Slot> slots;
    final Map<String, Integer> stock;
    final Set<String> mods = new LinkedHashSet<>();
    final List<String> modCands;
    /**
     * Fleet-level hullmods (ECM, Nav Relay, Escort Package, Operations Center on the flagship): worth nothing to
     * the single-ship score but real in a fleet battle, so they're the fallback for OP nothing else can use.
     */
    final List<String> fleetMods = new ArrayList<>();
    /** Non-built-in hullmods that must stay (s-mods, never-remove rules, or Strip unchecked). */
    final Set<String> keepMods;
    int evaluations;
    private final Map<String, JAOModel.Ship> shipCache = new HashMap<>();

    JAOSearch(World world, JAOModel.Params params, List<Slot> slots, Map<String, Integer> stock, List<String> modCands, Set<String> keepMods) {
        this.world = world; this.params = params; this.slots = slots; this.stock = stock;
        this.modCands = modCands; this.keepMods = keepMods;
        mods.addAll(keepMods);
        // the game lists stock in cargo-stack order, which shifts every time weapons move on or off a ship; near-ties
        // must not depend on it, so every slot sees its candidates in one fixed order (weapon, then cheapest source)
        for (Slot s : slots) s.cands.sort(CANON);
    }

    static final Comparator<Cand> CANON = Comparator.comparing((Cand c) -> c.w.id).thenComparing(c -> c.price).thenComparing(c -> c.stockKey);

    // ------------------------------------------------------------------ evaluation

    JAOModel.Ship ship() {
        String key = String.join(",", new TreeSet<>(mods));
        return shipCache.computeIfAbsent(key, k -> world.ship(new LinkedHashSet<>(mods)));
    }

    /** OP this search spends. What it doesn't own (kept weapons and hullmods) is already off World.hullOp(). */
    int opUsed() {
        float op = 0f;
        for (String m : mods) if (!keepMods.contains(m)) op += world.modOp(m);
        for (Slot s : slots) if (s.fixed == null && s.pick != null) op += world.op(s.pick, mods);
        return Math.round(op);
    }

    List<JAOModel.Fit> fits() {
        List<JAOModel.Fit> f = new ArrayList<>();
        for (Slot s : slots) {
            Cand c = s.fixed != null ? s.fixed : s.pick;
            if (c != null) f.add(new JAOModel.Fit(c.w, s.hardpoint, s.cover, s.front, s.bear));
        }
        return f;
    }

    /** Vents and capacitors for the OP left: {vents, caps}. */
    int[] flux() {
        JAOModel.Ship sh = ship();
        int left = world.hullOp() - opUsed();
        return JAOModel.fluxFill(sh, fits(), Math.max(0, left));
    }

    /** The objective: build score with the left-over OP in vents/caps, minus nothing; -1 when over the OP limit. */
    float objective() {
        evaluations++;
        int left = world.hullOp() - opUsed();
        if (left < 0) return -1f;
        JAOModel.Ship sh = ship();
        List<JAOModel.Fit> f = fits();
        int[] vc = JAOModel.fluxFill(sh, f, left);
        float score = JAOModel.score(JAOModel.withFlux(sh, vc[0], vc[1]), f, params) * (1f - EMPTY_COST * emptyShare());
        if (params.rareBonus > 0f) {
            // Prefer rare weapons: bonus by the slot-size-weighted rarity of what's fitted
            float wsum = 0f, r = 0f;
            for (Slot s : slots) if (s.pick != null) { float o = 1f + s.size * 2f; wsum += o; r += o * s.pick.w.rarity; }
            if (wsum > 0f) score *= 1f + params.rareBonus * r / wsum;
        }
        return score;
    }

    /**
     * A mount left empty or under-used although something in stock could fill it loses what the duel model can't
     * see (flank coverage, second targets, fighters, soaking hits), so it costs this share of the score at full
     * emptiness.
     */
    static final float EMPTY_COST = 0.5f;
    /** OP a weapon needs to count as using a small / medium / large mount fully (a 1-OP flare in a small mount is a quarter). */
    static final float[] MOUNT_OP = {4f, 8f, 16f};

    /**
     * Slot-size-weighted share (small 1, medium 2, large 4) of choosable mounts left empty or under-used: a weapon
     * uses its mount by its OP against MOUNT_OP (a small machine gun in a medium mount, or a mining laser in a small
     * one, leaves most of it unused). Players see those as empty, and so do fights. A fighter bay weighs as a large
     * mount (user 11/05: Optimize fleet left whole Legions without fighters once the fleet's role balance cut a
     * surplus role's wings to 40% of their value; an empty bay had cost nothing).
     */
    float emptyShare() {
        float all = 0f, empty = 0f;
        for (Slot s : slots) {
            if (s.fixed != null || s.cands.isEmpty()) continue;
            int sz = s.bay ? 2 : Math.max(0, Math.min(2, s.size));
            float w = sz == 0 ? 1f : sz == 1 ? 2f : 4f;
            all += w;
            if (s.pick == null) empty += w;
            else if (!s.bay) empty += w * (1f - Math.min(1f, world.baseOp(s.pick) / MOUNT_OP[sz]));
        }
        return all <= 0f ? 0f : empty / all;
    }

    /** True once the mounts are nearly all used: padding hullmods (Bulkheads, Blast Doors) come only after that. */
    boolean mountsUsed() { return emptyShare() <= 0.1f; }

    // ------------------------------------------------------------------ search

    void run() {
        prune();
        List<Slot> order = new ArrayList<>(slots);
        order.sort(Comparator.comparingInt((Slot s) -> s.bay ? 1 : 0).thenComparing((Slot s) -> -s.size)
                .thenComparing(s -> s.front ? 0 : 1).thenComparing(s -> s.id));
        // two starts (0.5.13): biggest mounts first, then smallest first; the better result stands. With flux tight a
        // flux-hungry gun taken first in a big mount can trap the whole fit (a Medusa scored a third less than its own
        // earlier fit); filling small mounts first leaves the big ones to choose within what's left
        Set<String> startMods = new LinkedHashSet<>(mods);
        improve(order);
        float first = objective();
        Map<Slot, Cand> firstPicks = new HashMap<>();
        for (Slot s : slots) firstPicks.put(s, s.pick);
        Set<String> firstMods = new LinkedHashSet<>(mods);
        clear(startMods);
        List<Slot> small = new ArrayList<>(slots);
        small.sort(Comparator.comparingInt((Slot s) -> s.bay ? 1 : 0).thenComparing((Slot s) -> s.size)
                .thenComparing(s -> s.front ? 0 : 1).thenComparing(s -> s.id));
        improve(small);
        if (objective() < first) {
            clear(startMods);
            for (Slot s : slots) {
                Cand c = firstPicks.get(s);
                if (s.fixed == null && c != null && take(c)) s.pick = c;
            }
            mods.addAll(firstMods);
        }
        // 4. never leave ordnance points unspent, nor a mount empty for a small modelled gain
        spendLeftover(order);
        fillEmpty(order);
        // 5. the same weapon from a cheaper source when one is still in stock (owned before bought or built)
        for (Slot s : order) {
            if (s.fixed != null || s.pick == null || s.pick.price <= 0f) continue;
            for (Cand c : s.cands) {
                if (c.price < s.pick.price && c.w.id.equals(s.pick.w.id) && take(c)) { give(s.pick); s.pick = c; break; }
            }
        }
    }

    /** Greedy fill in this mount order, then hullmods, swaps, hullmod trades and mount pairs until nothing improves. */
    private void improve(List<Slot> order) {
        // 1. greedy weapons and wings. Per-ship coverage judges whole loadouts only: a half-filled ship has no missiles
        // yet, so it would chase armor-crackers in the big mounts and run out of OP for the small ones
        boolean cover = params.coverage;
        params.coverage = false;
        for (Slot s : order) if (s.fixed == null) pickBest(s, false);
        params.coverage = cover;
        // 2. hullmods, 3. swaps, repeated while anything improves
        for (int round = 0; round < 4; round++) {
            boolean changed = addMods();
            for (Slot s : order) if (s.fixed == null && pickBest(s, true)) changed = true;
            if (removeMods()) changed = true;
            if (!changed) break;
        }
        // 3b. hullmod trades the steps above can't see: a costly hullmod taken early (Heavy Armor on a Conquest) can
        // block a better one (Dedicated Targeting Core) when neither removing the first nor adding the second alone helps
        for (int round = 0; round < 3 && swapMods(); round++) {
            for (Slot s : order) if (s.fixed == null) pickBest(s, true);
            addMods();
            removeMods();
        }
        // 3c. two big mounts at once (0.5.13)
        for (int round = 0; round < 2 && pairSwap(order); round++) {
            for (Slot s : order) if (s.fixed == null) pickBest(s, true);
            addMods();
            removeMods();
        }
    }

    /** Empties every choosable mount (stock given back) and resets the hullmods to these. */
    private void clear(Set<String> to) {
        for (Slot s : slots) {
            if (s.fixed != null || s.pick == null) continue;
            give(s.pick);
            s.pick = null;
        }
        mods.clear();
        mods.addAll(to);
    }

    /** OP left once vents and capacitors are filled (only when both are at their maximum). */
    int leftover() {
        int left = world.hullOp() - opUsed();
        if (left <= 0) return 0;
        int[] vc = flux();
        return Math.max(0, left - vc[0] - vc[1]);
    }

    /**
     * How much modelled score a leftover-spending step may give up when nothing neutral or better fits. Unspent OP is
     * never the best real choice (a mount filled, more PD, a padding hullmod all help in a fleet battle in ways the
     * duel model underrates), so the best available use is taken unless it costs more than this.
     */
    static final float LEFTOVER_TOLERANCE = 0.05f;

    /**
     * Vents and capacitors are maxed and points are still free: take the best use of them that doesn't make the ship
     * worse (any hullmod that fits, filler or not, with no minimum gain; a bigger weapon in a slot; a weapon for an
     * empty mount). Only if nothing is neutral or better, accept the least loss within LEFTOVER_TOLERANCE. Repeats
     * until nothing is left or nothing fits.
     */
    void spendLeftover(List<Slot> order) {
        for (int guard = 0; guard < 40; guard++) {
            int left = leftover();
            if (left <= 0) return;
            float base = objective();
            float bestV = -1f;
            String bestMod = null;
            Slot bestSlot = null;
            Cand bestCand = null;
            for (String m : modCands) {
                // a hullmod that costs nothing spends nothing: as "leftover" spending it was chosen for being harmless,
                // which put Yunru's zero-OP "Install:" converters on 13 ships (user 11/05); free hullmods that help come
                // in through addMods
                if (mods.contains(m) || world.modOp(m) <= 0 || world.modOp(m) > left || !world.canAdd(m, mods)) continue;
                mods.add(m);
                float v = objective();
                mods.remove(m);
                if (v > bestV) { bestV = v; bestMod = m; bestSlot = null; bestCand = null; }
            }
            // fleet hullmods score the same as nothing here, so they only win when nothing above is neutral or better
            for (String m : fleetMods) {
                if (mods.contains(m) || world.modOp(m) <= 0 || world.modOp(m) > left || !world.canAdd(m, mods)) continue;
                mods.add(m);
                float v = objective();
                mods.remove(m);
                if (v > bestV) { bestV = v; bestMod = m; bestSlot = null; bestCand = null; }
            }
            for (Slot s : order) {
                if (s.fixed != null) continue;
                Cand old = s.pick;
                float oldOp = old == null ? 0f : world.op(old, mods);
                if (old != null) give(old);
                for (Cand c : s.cands) {
                    float extra = world.op(c, mods) - oldOp;
                    if (c == old || extra <= 0f || extra > left || !take(c)) continue;
                    s.pick = c;
                    float v = objective();
                    s.pick = old;
                    give(c);
                    if (v > bestV) { bestV = v; bestMod = null; bestSlot = s; bestCand = c; }
                }
                if (old != null) take(old);
                s.pick = old;
            }
            if (bestV < base * (1f - LEFTOVER_TOLERANCE)) return;
            if (bestMod != null) {
                mods.add(bestMod);
            } else if (bestSlot != null) {
                if (bestSlot.pick != null) give(bestSlot.pick);
                take(bestCand);
                bestSlot.pick = bestCand;
            } else {
                return;
            }
        }
    }

    /** Puts the best candidate (or nothing) in s. With swap, the current pick competes too. Returns true if it changed. */
    boolean pickBest(Slot s, boolean swap) {
        Cand old = s.pick;
        if (old != null) { give(old); s.pick = null; }
        float best = objective();
        Cand bestC = null;
        if (old != null && take(old)) {
            // the current pick is the one to beat
            s.pick = old;
            float v = objective();
            s.pick = null;
            give(old);
            if (v >= best) { best = v; bestC = old; }
        }
        for (Cand c : s.cands) {
            if (c == old || !take(c)) continue;
            s.pick = c;
            float v = objective();
            s.pick = null;
            give(c);
            if (v > best * 1.0005f || (best <= 0f && v > best)) { best = v; bestC = c; }
        }
        if (bestC != null) { take(bestC); s.pick = bestC; }
        return bestC != old;
    }

    boolean addMods() {
        boolean any = false;
        while (true) {
            float base = objective();
            String best = null;
            float bestV = base * 1.002f;
            JAOModel.Ship sh = ship();
            int[] vc = flux();
            boolean surplus = vc[0] >= sh.maxVents && vc[1] >= sh.maxCaps && mountsUsed();
            for (String m : modCands) {
                if (mods.contains(m) || (!surplus && world.filler(m)) || !world.canAdd(m, mods)) continue;
                mods.add(m);
                float v = objective();
                mods.remove(m);
                if (v > bestV) { bestV = v; best = m; }
            }
            if (best == null) return any;
            mods.add(best);
            any = true;
        }
    }

    /** Modelled score a weapon may cost to fill an empty mount (the user 11/02: empty-looking mounts). */
    static final float FILL_TOLERANCE = 0.02f;

    /**
     * Fills each mount still empty with its best weapon when that costs at most FILL_TOLERANCE of the score (its OP comes
     * out of vents and capacitors): the duel model can't see what an empty mount loses in a fleet battle (0.5.13: an
     * Enforcer left a medium mount empty for hullmods).
     */
    void fillEmpty(List<Slot> order) {
        for (Slot s : order) {
            if (s.fixed != null || s.bay || s.pick != null || s.cands.isEmpty()) continue;
            float base = objective(), bestV = base * (1f - FILL_TOLERANCE);
            Cand best = null;
            for (Cand c : s.cands) {
                if (!take(c)) continue;
                s.pick = c;
                float v = objective();
                s.pick = null;
                give(c);
                if (v >= bestV) { bestV = v; best = c; }
            }
            if (best != null && take(best)) s.pick = best;
        }
    }

    /** Candidates tried per mount in pairSwap: the best few by what each scores alone in that mount. */
    static final int PAIR_CANDS = 4;

    /**
     * Tries every pair of medium and large mounts with their best PAIR_CANDS candidates each (by what each scores alone
     * in its mount, the rest as fitted) and takes the best pair that beats the current fit. Returns true if it changed.
     */
    boolean pairSwap(List<Slot> order) {
        List<Slot> big = new ArrayList<>();
        for (Slot s : order) if (s.fixed == null && !s.bay && s.size >= 1 && !s.cands.isEmpty()) big.add(s);
        if (big.size() < 2) return false;
        Map<Slot, List<Cand>> top = new HashMap<>();
        for (Slot s : big) {
            Cand keep = s.pick;
            if (keep != null) give(keep);
            List<Cand> scored = new ArrayList<>();
            Map<Cand, Float> v = new HashMap<>();
            for (Cand c : s.cands) {
                if (!take(c)) continue;
                s.pick = c;
                v.put(c, objective());
                s.pick = null;
                give(c);
                scored.add(c);
            }
            if (keep != null) take(keep);
            s.pick = keep;
            scored.sort((a, b) -> Float.compare(v.get(b), v.get(a)) != 0 ? Float.compare(v.get(b), v.get(a)) : a.w.id.compareTo(b.w.id));
            // the best PAIR_CANDS weapons, every source of each (one owned copy may already sit in another mount while a
            // blueprint can still build one: source-neutral like pickBest)
            Set<String> ids = new LinkedHashSet<>();
            for (Cand c : scored) if (ids.size() < PAIR_CANDS) ids.add(c.w.id);
            List<Cand> kept = new ArrayList<>();
            for (Cand c : s.cands) if (ids.contains(c.w.id)) kept.add(c);
            top.put(s, kept);
        }
        float base = objective(), bestV = base * 1.002f;
        Slot bestA = null, bestB = null;
        Cand bestCa = null, bestCb = null;
        for (int i = 0; i < big.size(); i++) {
            for (int j = i + 1; j < big.size(); j++) {
                Slot a = big.get(i), b = big.get(j);
                Cand oa = a.pick, ob = b.pick;
                if (oa != null) give(oa);
                if (ob != null) give(ob);
                Set<String> doneA = new HashSet<>();
                for (Cand ca : top.get(a)) {
                    if (doneA.contains(ca.w.id) || !take(ca)) continue;
                    doneA.add(ca.w.id);
                    a.pick = ca;
                    Set<String> doneB = new HashSet<>();
                    for (Cand cb : top.get(b)) {
                        if (doneB.contains(cb.w.id)) continue;
                        if (ca.w.id.equals(oa == null ? null : oa.w.id) && cb.w.id.equals(ob == null ? null : ob.w.id)) continue;
                        if (!take(cb)) continue;
                        doneB.add(cb.w.id);
                        b.pick = cb;
                        float v = objective();
                        if (v > bestV) { bestV = v; bestA = a; bestB = b; bestCa = ca; bestCb = cb; }
                        b.pick = null;
                        give(cb);
                    }
                    a.pick = null;
                    give(ca);
                }
                if (oa != null) take(oa);
                if (ob != null) take(ob);
                a.pick = oa;
                b.pick = ob;
            }
        }
        if (bestA == null) return false;
        if (bestA.pick != null) give(bestA.pick);
        if (bestB.pick != null) give(bestB.pick);
        take(bestCa);
        take(bestCb);
        bestA.pick = bestCa;
        bestB.pick = bestCb;
        return true;
    }

    /** Replaces one hullmod with another when the trade beats both (best trade first). Returns true if one was made. */
    boolean swapMods() {
        float base = objective();
        String bestOut = null, bestIn = null;
        float bestV = base * 1.002f;
        for (String out : new ArrayList<>(mods)) {
            if (keepMods.contains(out)) continue;
            mods.remove(out);
            for (String in : modCands) {
                if (in.equals(out) || mods.contains(in) || world.filler(in) || !world.canAdd(in, mods)) continue;
                mods.add(in);
                float v = objective();
                mods.remove(in);
                if (v > bestV) { bestV = v; bestOut = out; bestIn = in; }
            }
            mods.add(out);
        }
        if (bestOut == null) return false;
        mods.remove(bestOut);
        mods.add(bestIn);
        return true;
    }

    boolean removeMods() {
        boolean any = false;
        for (String m : new ArrayList<>(mods)) {
            if (keepMods.contains(m)) continue;
            float base = objective();
            mods.remove(m);
            if (objective() > base * 1.001f) { any = true; continue; }
            mods.add(m);
        }
        return any;
    }

    boolean take(Cand c) {
        Integer q = stock.get(c.stockKey);
        if (q == null || q <= 0) return false;
        stock.put(c.stockKey, q - 1);
        return true;
    }

    void give(Cand c) { stock.merge(c.stockKey, 1, Integer::sum); }

    /**
     * Keeps the best few weapons per slot and damage role, by what each adds to an empty build: the strongest four
     * outright and the best four for their OP (value per OP alone kept only cheap guns, so the big rare ones never got
     * a look). Every source of a kept weapon stays (cargo, storage, market, blueprint), owned before bought, so
     * duplicates don't crowd others out.
     */
    void prune() {
        for (Slot s : slots) {
            Set<String> distinct = new HashSet<>();
            for (Cand c : s.cands) distinct.add(c.w.id);
            if (distinct.size() <= 24) continue;
            Map<Cand, Float> v = new HashMap<>(), vOp = new HashMap<>();
            for (Cand c : s.cands) {
                float op = Math.max(1f, world.op(c, mods));
                float one = objectiveOneWeapon(s, c);
                v.put(c, one);
                vOp.put(c, one / (float) Math.sqrt(op));
            }
            Map<String, List<Cand>> byRole = new HashMap<>();
            for (Cand c : s.cands) byRole.computeIfAbsent(role(c.w), k -> new ArrayList<>()).add(c);
            Set<Cand> keep = new HashSet<>();
            for (List<Cand> l : byRole.values()) {
                for (Map<Cand, Float> by : List.of(v, vOp)) {
                    l.sort((a, b) -> Float.compare(by.get(b), by.get(a)) != 0 ? Float.compare(by.get(b), by.get(a)) : a.w.id.compareTo(b.w.id));
                    Set<String> ids = new HashSet<>();
                    for (Cand c : l) if (ids.size() < 4 || ids.contains(c.w.id)) { ids.add(c.w.id); keep.add(c); }
                }
            }
            List<Cand> kept = new ArrayList<>(keep);
            kept.sort(CANON);
            s.cands.clear();
            s.cands.addAll(kept);
        }
    }

    private float objectiveOneWeapon(Slot s, Cand c) {
        List<JAOModel.Fit> f = Collections.singletonList(new JAOModel.Fit(c.w, s.hardpoint, s.cover, s.front, s.bear));
        return JAOModel.score(ship(), f, params);
    }

    static String role(JAOModel.W w) {
        if (w.wing) return w.interceptor ? "wing_int" : w.bomber ? "wing_bomb" : "wing";
        if (w.pd || w.pdOnly) return "pd";
        if (w.cls == JAOModel.MIS) return w.strike ? "strike" : "missile";
        return "dmg" + w.dmg;
    }
}
