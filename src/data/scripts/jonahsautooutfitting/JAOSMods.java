package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Picks the hullmods worth building in with story points (the "Build in s-mods" autofit box). S-mods are permanent,
 * so every candidate is judged by re-optimising the whole ship around it: it costs no OP and gets its s-mod effect
 * (measured on the scratch fleet member), and the freed OP goes to weapons, other hullmods, vents and capacitors.
 *
 * Greedy, best first, up to min(ship's s-mod limit left, story points minus the reserve, the per-ship setting).
 * Each pick must add at least jao_smodMinGain of the ship's score. With "Wait for the best hullmods", the same
 * search over every hullmod in the game decides whether a clearly better set exists that the player can't fit yet
 * (not known, or needs a spaceport): then only the slots its missing hullmods would take are held, the rest get the
 * best the player has, and the log says what's held for. A ship holds slots for at most jao_smodWaitDays (JAOOptimizer),
 * then builds the best it has. A hullmod that unlocks others (AI Subsystem Integration enables Emergent Threats'
 * adaptive subsystems on ordinary hulls) is valued with the known hullmods it unlocks.
 *
 * Candidates are screened cheaply (the base fit's weapons kept, hullmods/vents/caps re-chosen), then the top few get
 * a full search.
 */
final class JAOSMods {
    static final class Plan {
        final List<String> mods = new ArrayList<>();
        /** Hullmods a held slot is kept open for (not known yet, or needs a spaceport). */
        final List<String> waitingFor = new ArrayList<>();
        boolean waiting;
        String note = "";
        float gain;
    }

    final JAOSearch.World world;
    final JAOModel.Params params;
    final Supplier<List<JAOSearch.Slot>> slots;
    final Map<String, Integer> stock;
    final List<String> modCands;
    final Set<String> keep;
    final int limit;

    JAOSMods(JAOSearch.World world, JAOModel.Params params, Supplier<List<JAOSearch.Slot>> slots, Map<String, Integer> stock,
             List<String> modCands, Set<String> keep, int limit) {
        this.world = world; this.params = params; this.slots = slots; this.stock = stock;
        this.modCands = modCands; this.keep = keep; this.limit = limit;
    }

    /**
     * The s-mods to build now. While waiting for a clearly better set, only the slots its missing hullmods would take
     * stay open: the rest get the best set of hullmods the player has (so a ship waiting for one hullmod still gets its
     * other s-mods). The caller passes everything == available when waiting is off or has run out of time.
     */
    Plan plan(List<String> available, List<String> everything) {
        Plan plan = new Plan();
        if (limit <= 0) { plan.note = "no s-mod room or story points"; return plan; }
        Pick known = greedy(available, limit);
        if (JAOSettings.smodWait && everything != available) {
            Pick best = greedy(everything, limit);
            List<String> missing = new ArrayList<>();
            for (String m : best.mods) if (!available.contains(m)) missing.add(m);
            if (!missing.isEmpty() && best.score > known.score * (1f + JAOSettings.smodWaitMargin)) {
                Pick now = missing.size() < limit ? greedy(available, limit - missing.size()) : new Pick();
                plan.waiting = true;
                plan.waitingFor.addAll(missing);
                plan.mods.addAll(now.mods);
                plan.gain = now.gain;
                List<String> why = new ArrayList<>();
                for (String m : missing) why.add(name(m) + (knows(m) ? " (dock at a spaceport)" : " (not known yet)"));
                plan.note = "holding " + missing.size() + " slot" + (missing.size() > 1 ? "s" : "") + " for " + String.join(", ", why)
                        + ": " + names(best.mods) + " would be " + pct(best.score / known.score - 1f) + " better than "
                        + (known.mods.isEmpty() ? "anything you have" : names(known.mods))
                        + (now.mods.isEmpty() ? "; nothing else worth building now" : "; building " + names(now.mods) + " now (+" + pct(now.gain) + ")");
                return plan;
            }
        }
        if (known.mods.isEmpty()) { plan.note = "no hullmod worth a story point (minimum gain " + pct(JAOSettings.smodMinGain) + ")"; return plan; }
        plan.mods.addAll(known.mods);
        plan.gain = known.gain;
        plan.note = "build in " + names(known.mods) + " (+" + pct(known.gain) + ")";
        return plan;
    }

    private static final class Pick {
        final List<String> mods = new ArrayList<>();
        float score, gain;
    }

    static final int BEAM = 3;

    /**
     * Best s-mod set from candidates by a small beam search (the 3 best sets of each size are extended), because the
     * best single pick isn't always part of the best pair. Every added s-mod must add jao_smodMinGain.
     */
    private Pick greedy(List<String> candidates, int max) {
        Pick p = new Pick();
        float start = full(new LinkedHashSet<>());
        List<Set<String>> beam = new ArrayList<>();
        Map<String, Float> scores = new LinkedHashMap<>();
        beam.add(new LinkedHashSet<>());
        scores.put("", start);
        Set<String> best = new LinkedHashSet<>();
        float bestScore = start;
        for (int k = 0; k < max && !beam.isEmpty(); k++) {
            Map<String, Set<String>> next = new LinkedHashMap<>();
            Map<String, Float> nextScore = new LinkedHashMap<>();
            for (Set<String> chosen : beam) {
                float base = scores.get(key(chosen));
                JAOSearch baseFit = search(chosen);
                baseFit.run();
                // cheap screen: keep the weapons, re-choose hullmods, vents and capacitors around each candidate
                Map<String, Float> screen = new LinkedHashMap<>();
                for (String m : candidates) {
                    world.setSMods(chosen);
                    if (chosen.contains(m) || !world.canAdd(m, without(baseFit.mods, m))) continue;
                    Set<String> s = new LinkedHashSet<>(chosen);
                    s.add(m);
                    if (next.containsKey(key(s))) continue;
                    screen.put(m, screened(baseFit, s));
                }
                List<String> top = new ArrayList<>(screen.keySet());
                top.sort(Comparator.comparing((String m) -> -screen.get(m)).thenComparing(m -> m));
                for (String m : top.subList(0, Math.min(BEAM, top.size()))) {
                    Set<String> s = new LinkedHashSet<>(chosen);
                    s.add(m);
                    float v = full(s);
                    if (v <= base * (1f + JAOSettings.smodMinGain)) continue;
                    next.put(key(s), s);
                    nextScore.put(key(s), v);
                }
            }
            List<String> keys = new ArrayList<>(next.keySet());
            keys.sort(Comparator.comparing((String k2) -> -nextScore.get(k2)).thenComparing(k2 -> k2));
            beam = new ArrayList<>();
            for (String k2 : keys.subList(0, Math.min(BEAM, keys.size()))) {
                beam.add(next.get(k2));
                scores.put(k2, nextScore.get(k2));
                if (nextScore.get(k2) > bestScore) { bestScore = nextScore.get(k2); best = next.get(k2); }
            }
        }
        world.setSMods(new LinkedHashSet<>());
        p.mods.addAll(best);
        p.score = bestScore;
        p.gain = start > 0f ? bestScore / start - 1f : 0f;
        return p;
    }

    private static String key(Set<String> s) { return String.join(",", new TreeSet<>(s)); }

    private JAOSearch search(Set<String> s) {
        world.setSMods(s);
        Set<String> k = new LinkedHashSet<>(keep);
        k.addAll(s);
        List<String> cands = new ArrayList<>(modCands);
        cands.removeAll(s);
        return new JAOSearch(world, params, slots.get(), new LinkedHashMap<>(stock), cands, k);
    }

    private float full(Set<String> s) {
        JAOSearch js = search(s);
        js.run();
        return js.objective();
    }

    /** The base fit's weapons, with hullmods and flux re-chosen for these s-mods. */
    private float screened(JAOSearch baseFit, Set<String> s) {
        world.setSMods(s);
        Set<String> k = new LinkedHashSet<>(keep);
        k.addAll(s);
        List<JAOSearch.Slot> fixed = new ArrayList<>();
        for (JAOSearch.Slot sl : baseFit.slots) {
            JAOSearch.Slot c = new JAOSearch.Slot(sl.id, sl.size, sl.hardpoint, sl.cover, sl.front, sl.bay);
            c.bear = sl.bear;
            c.fixed = sl.fixed;
            c.pick = sl.pick;
            fixed.add(c);
        }
        List<String> cands = new ArrayList<>(modCands);
        cands.removeAll(s);
        JAOSearch js = new JAOSearch(world, params, fixed, new LinkedHashMap<>(), cands, k);
        for (String m : baseFit.mods) if (!k.contains(m)) js.mods.add(m);
        js.addMods();
        js.removeMods();
        return js.objective();
    }

    private static Set<String> without(Set<String> mods, String m) {
        Set<String> s = new TreeSet<>(mods);
        s.remove(m);
        return s;
    }

    /**
     * Can be an s-mod at all: not tagged no_build_in, not hidden, has combat value (jao_hullmods.csv or unknown), or a
     * value only as an s-mod (JAOShip.SMOD_VALUE: Escort Package).
     */
    static boolean buildable(String id) {
        HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
        if (spec == null || spec.isHidden() || spec.isHiddenEverywhere() || spec.hasTag(Tags.HULLMOD_NO_BUILD_IN) || spec.hasTag("dmod")) return false;
        if (JAOCompat.neverAdd(id)) return false;
        JSONObject row = JAORatings.hullmods().get(id);
        if (row != null && !"TRUE".equalsIgnoreCase(row.optString("combat", "TRUE")) && !JAOShip.SMOD_VALUE.contains(id)) return false;
        // s-mods whose s-mod effect is a penalty (Heavy Armor maneuver -25%, Expanded Missile Racks missile rate of
        // fire x0.8, Autoloader 10 s cooldown): with "Allow hullmods with drawbacks" on they stay candidates (the
        // scratch fleet member applies the penalty and the model scores it); off, never
        if (!JAOSettings.allowDrawbacks && row != null && "TRUE".equalsIgnoreCase(row.optString("smodPenalty"))) return false;
        if (row != null && "listener".equals(row.optString("how")) && !JAOShip.MODELLED.contains(id)) return false;
        return true;
    }

    /** Sector data: fleet member id -> clock timestamp when it started holding s-mod slots (plain JDK types, so saves never need this mod). */
    static final String WAITS = "jao_smod_waits";

    @SuppressWarnings("unchecked")
    private static Map<String, Long> waits() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object o = data.get(WAITS);
        if (o instanceof Map) return (Map<String, Long>) o;
        Map<String, Long> m = new HashMap<>();
        data.put(WAITS, m);
        return m;
    }

    /** Days this ship has been holding slots; with start, the wait starts now if it hadn't. */
    static float waitedDays(String memberId, boolean start) {
        Map<String, Long> w = waits();
        Long since = w.get(memberId);
        if (since == null) {
            if (!start) return 0f;
            since = Global.getSector().getClock().getTimestamp();
            w.put(memberId, since);
        }
        return Global.getSector().getClock().getElapsedDaysSince(since);
    }

    /** The ship has no s-mod room left: nothing to wait for. */
    static void stopWaiting(String memberId) {
        Object o = Global.getSector().getPersistentData().get(WAITS);
        if (o instanceof Map) ((Map<?, ?>) o).remove(memberId);
    }

    /** Every hullmod in the loaded game that could be an s-mod (for "wait for the best hullmods"). */
    static List<String> everything() {
        Set<String> out = new TreeSet<>();
        for (HullModSpecAPI spec : Global.getSettings().getAllHullModSpecs()) if (buildable(spec.getId())) out.add(spec.getId());
        return new ArrayList<>(out);
    }

    static boolean knows(String id) {
        try { return Global.getSector().getPlayerFaction().knowsHullMod(id); } catch (Throwable t) { return false; }
    }

    static String name(String id) {
        try {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            return spec == null ? id : spec.getDisplayName();
        } catch (Throwable t) {
            return id;
        }
    }

    static String names(List<String> ids) {
        List<String> n = new ArrayList<>();
        for (String id : ids) n.add(name(id));
        return String.join(" + ", n);
    }

    static String pct(float f) { return Math.round(f * 1000f) / 10f + "%"; }
}
