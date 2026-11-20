package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.DModManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * D-mod repairs for Optimize fleet (0.5.75, user 11/20: "use either money or domain-grade parts, depending on what the
 * player has access to ... and what is cheaper, and include that in the budget"; "weapons should always come first";
 * a box per ship type; its own on/off). 0.5.76 (user: "Why not ... 1 D mod removed off of a flagship, like one at a
 * time? ... I'm only going to have the parts for one capital ship removal"): parts go one d-mod at a time.
 *  - Value of a d-mod: how much the ship's build score rises without it (JAOFleetPlan.scoreVariant), as a share, times
 *    the ship's deployment points, x2 on the flagship; a d-mod the score doesn't see (Increased Maintenance and the
 *    like) is worth a little, so it goes last.
 *  - Parts: the game's refit repair (ModPickerDialogV3, 0.98.5a-RC8): hull.getPartsPerDmod() per d-mod, taken from fleet
 *    cargo, then from storage at the docked market; works anywhere. Compared at the parts' base price.
 *  - Credits: the game's restoration (FleetMember.getRestorationCost): the higher base value of the D-hull and its base
 *    hull x baseRestoreCostMult (1.2) x baseRestoreCostMultPerDMod (1.2) per d-mod left; it clears the whole ship, only
 *    docked at a market with a spaceport, and counts against what's left of the credit limit.
 *  - allocate(): the best value per cost first, a d-mod with parts or a whole ship with credits (only a ship that took
 *    no parts), while parts and credits last.
 *  - Removing the d-mods and turning the D-hull back into its hull is done the way the refit screen's repair does it.
 */
final class JAODmods {
    static final String PARTS = "parts";
    static final int NONE = 0, WITH_PARTS = 1, WITH_CREDITS = 2;

    private JAODmods() { }

    /** One ship's repair: its removable d-mods, how it's paid, and what it costs. */
    static final class Repair {
        final JAOFleetPlan.ShipPlan ship;
        final List<String> dmods;
        int how, parts, credits;
        Repair(JAOFleetPlan.ShipPlan ship, List<String> dmods) { this.ship = ship; this.dmods = dmods; }

        /** "Degraded Engines and Faulty Power Grid with 6 Domain-grade Parts" / "a restoration for 172,800 credits (3 d-mods)". */
        String describe() {
            if (how == WITH_CREDITS) return "a restoration for " + com.fs.starfarer.api.util.Misc.getDGSCredits(credits) + " (" + dmods.size() + " d-mod" + (dmods.size() > 1 ? "s" : "") + ")";
            List<String> names = new ArrayList<>();
            for (String id : dmods) names.add(JAOSMods.name(id));
            return com.fs.starfarer.api.util.Misc.getAndJoined(names) + " with " + parts + " Domain-grade Parts";
        }
    }

    static int partsPerDmod(ShipHullSpecAPI hull) {
        try { return Math.max(1, hull.getPartsPerDmod()); } catch (Throwable t) { return 1; }
    }

    static float partsPrice() {
        try { return Global.getSettings().getCommoditySpec(PARTS).getBasePrice(); } catch (Throwable t) { return 0f; }
    }

    /** The game's restoration price for these many d-mods. */
    static int restoreCost(ShipHullSpecAPI hull, int dmods) {
        if (hull == null || dmods <= 0) return 0;
        float mult = 1.2f, per = 1.2f;
        try { mult = Global.getSettings().getFloat("baseRestoreCostMult"); per = Global.getSettings().getFloat("baseRestoreCostMultPerDMod"); } catch (Throwable ignored) { }
        return restoreCost(baseValue(hull), dmods, mult, per);
    }

    static int restoreCost(float baseValue, int dmods, float mult, float perDmod) {
        float v = baseValue * mult;
        for (int i = 0; i < dmods; i++) v *= perDmod;
        return (int) v;
    }

    /**
     * How one ship gets repaired: parts if there are enough, credits if restoration is open here and the credits are
     * left; when both are open, whichever costs less (parts at their base price). NONE when neither.
     */
    static int choose(int partsCost, int partsLeft, float partPrice, int credits, double creditsLeft, boolean restoreHere) {
        boolean parts = partsCost > 0 && partsCost <= partsLeft;
        boolean cash = restoreHere && credits > 0 && credits <= creditsLeft;
        if (parts && cash) return partsCost * partPrice <= credits ? WITH_PARTS : WITH_CREDITS;
        return parts ? WITH_PARTS : cash ? WITH_CREDITS : NONE;
    }

    /** Value of a d-mod the score doesn't see, per deployment point (so it goes after every d-mod that matters). */
    static final float MIN_GAIN = 0.002f;
    /** The flagship's d-mods count double. */
    static final float FLAGSHIP_WEIGHT = 2f;

    /** A d-mod's value: the score's rise without it as a share, times deployment points (flagship x2); at least MIN_GAIN x DP. */
    static float value(float scoreWith, float scoreWithout, float dp, boolean flagship) {
        float d = Math.max(1f, dp);
        float rise = scoreWith > 0f && scoreWithout > scoreWith ? (scoreWithout - scoreWith) / scoreWith : 0f;
        return Math.max(MIN_GAIN, rise) * d * (flagship ? FLAGSHIP_WEIGHT : 1f);
    }

    /** What allocate() takes: per ship, which d-mods come off with parts, and whether it's restored for credits instead. */
    static final class Allocation {
        boolean[][] parts;
        boolean[] restored;
        int partsUsed;
        double creditsUsed;
    }

    /**
     * Best value per cost first: one d-mod with parts (ppd[i] parts at partPrice each) or a whole ship with credits (the
     * restoration price for the d-mods it still has), while parts and credits last. gain[i][k]: d-mod k of ship i.
     */
    static Allocation allocate(float[][] gain, int[] ppd, float[] baseValue, int partsHave, float partPrice, double credits, boolean restoreHere,
                               float mult, float perDmod) {
        int n = gain.length;
        Allocation a = new Allocation();
        a.parts = new boolean[n][];
        a.restored = new boolean[n];
        for (int i = 0; i < n; i++) a.parts[i] = new boolean[gain[i].length];
        int partsLeft = partsHave;
        double left = credits;
        while (true) {
            int bi = -1, bk = -1;
            float best = 0f;
            for (int i = 0; i < n; i++) {
                if (a.restored[i]) continue;
                int remaining = 0;
                float restGain = 0f;
                for (int k = 0; k < gain[i].length; k++) {
                    if (a.parts[i][k]) continue;
                    remaining++;
                    restGain += gain[i][k];
                    if (ppd[i] <= partsLeft) {
                        float r = gain[i][k] / Math.max(1f, ppd[i] * Math.max(0.01f, partPrice));
                        if (r > best) { best = r; bi = i; bk = k; }
                    }
                }
                // a restoration clears the whole ship: only for a ship that took no parts
                if (restoreHere && remaining > 0 && remaining == gain[i].length) {
                    int cost = restoreCost(baseValue[i], remaining, mult, perDmod);
                    if (cost > 0 && cost <= left) {
                        float r = restGain / cost;
                        if (r > best) { best = r; bi = i; bk = -1; }
                    }
                }
            }
            if (bi < 0) break;
            if (bk >= 0) {
                a.parts[bi][bk] = true;
                partsLeft -= ppd[bi];
                a.partsUsed += ppd[bi];
            } else {
                int remaining = 0;
                for (boolean x : a.parts[bi]) if (!x) remaining++;
                int cost = restoreCost(baseValue[bi], remaining, mult, perDmod);
                a.restored[bi] = true;
                left -= cost;
                a.creditsUsed += cost;
            }
        }
        return a;
    }

    /** Scores a variant of a ship (JAOFleetPlan.scoreVariant). */
    interface Scorer { float score(JAOFleetPlan.ShipPlan s, ShipVariantAPI v); }

    /**
     * The repairs that fit: the planned ships of the ticked types with removable d-mods, each d-mod valued by the score
     * without it, then allocate(). One Repair per ship: the d-mods its parts remove, or its restoration.
     */
    static List<Repair> plan(List<JAOFleetPlan.ShipPlan> ships, int partsHave, double creditsLeft, boolean restoreHere,
                             java.util.function.Predicate<JAOFleetPlan.ShipPlan> skip, Scorer scorer) {
        List<JAOFleetPlan.ShipPlan> cand = new ArrayList<>();
        List<List<String>> dm = new ArrayList<>();
        // 0.5.77: ships picked in the panel are the only ones repaired (the type boxes then don't matter); none picked: the types
        java.util.Set<String> picks = JAOFleetOptions.repairPicks();
        boolean only = false;
        for (JAOFleetPlan.ShipPlan s : ships) if (picks.contains(s.member.getId())) { only = true; break; }
        for (JAOFleetPlan.ShipPlan s : ships) {
            if (s.plan == null || s.locked || s.keep || (skip != null && skip.test(s))) continue;
            if (only ? !picks.contains(s.member.getId()) : !JAOFleetOptions.repairType(s.member.getHullSpec().getHullSize(), s.civilian)) continue;
            List<String> d = JAOOptimizer.removableDmods(s.plan);
            if (d.isEmpty()) continue;
            cand.add(s);
            dm.add(d);
        }
        int n = cand.size();
        float[][] gain = new float[n][];
        int[] ppd = new int[n];
        float[] base = new float[n];
        for (int i = 0; i < n; i++) {
            JAOFleetPlan.ShipPlan s = cand.get(i);
            List<String> d = dm.get(i);
            ppd[i] = partsPerDmod(s.member.getHullSpec());
            base[i] = baseValue(s.member.getHullSpec());
            gain[i] = new float[d.size()];
            float with = 0f;
            try { with = scorer == null ? 0f : scorer.score(s, s.plan); } catch (Throwable ignored) { }
            boolean flag = false;
            try { flag = s.member.isFlagship(); } catch (Throwable ignored) { }
            for (int k = 0; k < d.size(); k++) {
                float without = with;
                try {
                    if (scorer != null && with > 0f) {
                        ShipVariantAPI v = s.plan.clone();
                        DModManager.removeDMod(v, d.get(k));
                        without = scorer.score(s, v);
                    }
                } catch (Throwable ignored) { }
                gain[i][k] = value(with, without, dp(s.member), flag);
            }
        }
        float mult = 1.2f, per = 1.2f;
        try { mult = Global.getSettings().getFloat("baseRestoreCostMult"); per = Global.getSettings().getFloat("baseRestoreCostMultPerDMod"); } catch (Throwable ignored) { }
        Allocation a = allocate(gain, ppd, base, partsHave, partsPrice(), creditsLeft, restoreHere, mult, per);
        List<Repair> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<String> d = dm.get(i);
            if (a.restored[i]) {
                Repair r = new Repair(cand.get(i), d);
                r.how = WITH_CREDITS;
                int remaining = 0;
                for (boolean x : a.parts[i]) if (!x) remaining++;
                r.credits = restoreCost(base[i], remaining, mult, per);
                out.add(r);
                continue;
            }
            List<String> take = new ArrayList<>();
            for (int k = 0; k < d.size(); k++) if (a.parts[i][k]) take.add(d.get(k));
            if (take.isEmpty()) continue;
            Repair r = new Repair(cand.get(i), take);
            r.how = WITH_PARTS;
            r.parts = take.size() * ppd[i];
            out.add(r);
        }
        out.sort(Comparator.comparingDouble((Repair r) -> -dp(r.ship.member)));
        return out;
    }

    /** The higher base value of a hull and its base hull (what the restoration price starts from). */
    static float baseValue(ShipHullSpecAPI hull) {
        ShipHullSpecAPI base = null;
        try { base = Global.getSettings().getHullSpec(hull.getBaseHullId()); } catch (Throwable ignored) { }
        return Math.max(hull.getBaseValue(), base == null ? 0f : base.getBaseValue());
    }

    /** Domain-grade Parts in fleet cargo and storage at the docked market (the refit screen's own sources). */
    static int partsHave(CargoAPI cargo, MarketAPI market) {
        float n = 0f;
        try { if (cargo != null) n += Math.round(cargo.getCommodityQuantity(PARTS)); } catch (Throwable ignored) { }
        try {
            CargoAPI st = market == null ? null : com.fs.starfarer.api.util.Misc.getStorageCargo(market);
            if (st != null) n += Math.round(st.getCommodityQuantity(PARTS));
        } catch (Throwable ignored) { }
        return Math.round(n);
    }

    /** Restoration with credits: docked at a market with a spaceport. */
    static boolean restoreHere(MarketAPI market) {
        try { return market != null && market.hasSpaceport(); } catch (Throwable t) { return false; }
    }

    static float dp(FleetMemberAPI m) {
        try { return m.getDeploymentPointsCost(); } catch (Throwable t) { return 1f; }
    }

    /**
     * Repairs one ship's variant the way the refit screen does: each d-mod comes off (one built into the hull it goes
     * back to is suppressed), and with none left the D-hull turns back into its hull.
     */
    static void repair(ShipVariantAPI v, List<String> dmods) {
        for (String id : dmods) DModManager.removeDMod(v, id);
        if (DModManager.getNumDMods(v) > 0) return;
        ShipHullSpecAPI h = v.getHullSpec();
        ShipHullSpecAPI base = h.getDParentHull();
        if (!h.isDefaultDHull() && !h.isRestoreToBase()) base = h;
        if (base == null && h.isRestoreToBase()) base = h.getBaseHull();
        if (base != null && base != h) v.setHullSpecAPI(base);
    }

    /** Takes parts from fleet cargo first, then storage at the market (the refit screen's order). */
    static void takeParts(CargoAPI cargo, MarketAPI market, int n) {
        int have = Math.round(cargo.getCommodityQuantity(PARTS));
        int a = Math.min(have, n);
        if (a > 0) cargo.removeCommodity(PARTS, a);
        n -= a;
        if (n <= 0 || market == null) return;
        CargoAPI st = com.fs.starfarer.api.util.Misc.getStorageCargo(market);
        if (st == null) return;
        int b = Math.min(Math.round(st.getCommodityQuantity(PARTS)), n);
        if (b > 0) st.removeCommodity(PARTS, b);
    }
}
