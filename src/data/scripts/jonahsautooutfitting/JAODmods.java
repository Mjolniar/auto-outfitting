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
 * a box per ship type; its own on/off). After the plan (weapons, hullmods, s-mods, s-mod changes), what's left of the
 * credit limit and the Domain-grade Parts in fleet cargo and storage here repair the planned ships, one ship whole at a
 * time, the most d-mods times deployment points per credit first.
 *  - Parts: the game's refit repair (ModPickerDialogV3, 0.98.5a-RC8): hull.getPartsPerDmod() per d-mod, taken from fleet
 *    cargo, then from storage at the docked market; works anywhere. Counted at the parts' base price to compare.
 *  - Credits: the game's restoration (FleetMember.getRestorationCost): the higher base value of the D-hull and its base
 *    hull x baseRestoreCostMult (1.2) x baseRestoreCostMultPerDMod (1.2) per d-mod; only docked at a market with a
 *    spaceport. Counts against the credit limit.
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
        ShipHullSpecAPI base = null;
        try { base = Global.getSettings().getHullSpec(hull.getBaseHullId()); } catch (Throwable ignored) { }
        float v = Math.max(hull.getBaseValue(), base == null ? 0f : base.getBaseValue());
        return restoreCost(v, dmods, mult, per);
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

    /** Ship order: most d-mods times deployment points per credit (parts at their price) first. */
    static float priority(int dmods, float dp, float cost) {
        return dmods * Math.max(1f, dp) / Math.max(1f, cost);
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

    /** The repairs that fit: the planned ships of the ticked types with removable d-mods, best value per cost first. */
    static List<Repair> plan(List<JAOFleetPlan.ShipPlan> ships, int partsHave, double creditsLeft, boolean restoreHere, java.util.function.Predicate<JAOFleetPlan.ShipPlan> skip) {
        List<Repair> all = new ArrayList<>();
        float price = partsPrice();
        for (JAOFleetPlan.ShipPlan s : ships) {
            if (s.plan == null || s.locked || s.keep || (skip != null && skip.test(s))) continue;
            if (!JAOFleetOptions.repairType(s.member.getHullSpec().getHullSize(), s.civilian)) continue;
            List<String> d = JAOOptimizer.removableDmods(s.plan);
            if (d.isEmpty()) continue;
            Repair r = new Repair(s, d);
            r.parts = d.size() * partsPerDmod(s.member.getHullSpec());
            r.credits = restoreCost(s.member.getHullSpec(), d.size());
            all.add(r);
        }
        all.sort(Comparator.comparingDouble((Repair r) -> -priority(r.dmods.size(), dp(r.ship.member),
                Math.min(r.parts * price > 0f ? r.parts * price : Float.MAX_VALUE, r.credits > 0 ? r.credits : Float.MAX_VALUE))));
        List<Repair> out = new ArrayList<>();
        int partsLeft = partsHave;
        double left = creditsLeft;
        for (Repair r : all) {
            r.how = choose(r.parts, partsLeft, price, r.credits, left, restoreHere);
            if (r.how == WITH_PARTS) partsLeft -= r.parts;
            else if (r.how == WITH_CREDITS) left -= r.credits;
            else continue;
            out.add(r);
        }
        return out;
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
