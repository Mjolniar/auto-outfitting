package data.scripts.jonahsautooutfitting;

import assortment_of_things.frontiers.FrontiersUtils;
import assortment_of_things.frontiers.SettlementData;
import assortment_of_things.frontiers.ui.SettlementCustomProduction;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionProductionAPI;
import com.fs.starfarer.api.campaign.FactionProductionAPI.ProductionItemType;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.Map;
import java.util.Set;

/**
 * Random Assortment of Things' Frontiers settlement (RAT 3.4.0). Its Ordnance Manufacturing facility (weapon_construction)
 * takes custom production orders for every weapon and fighter wing the player knows, from a budget of its own (150,000 at
 * most, +10,000 a month, more with other facilities), at RAT's cost multiplier (1.2) paid when ordered, delivered in 10
 * to 60 days to the settlement's storage, which RAT keeps in the planet's own storage. Orders are placed the way RAT's
 * order screen places them: a copy of the player's production list holding only these items, at RAT's multiplier,
 * handed to its SettlementCustomProduction (notifyProductionSelected), which takes the credits and the budget and
 * queues the delivery. RAT doesn't check the budget there (its screen does), so order() does. Only touched through
 * JAOProduction when assortment_of_things is enabled, so the mod runs without RAT.
 */
final class JAORatSettlement {
    static final String FACILITY = "weapon_construction";

    private JAORatSettlement() { }

    /** Throws if the RAT classes this needs aren't what they were built against. */
    static void probe() {
        FrontiersUtils.INSTANCE.getFrontiersData();
    }

    /** The settlement, or null when there is none. */
    static SettlementData any() {
        return FrontiersUtils.INSTANCE.getFrontiersData().getActiveSettlement();
    }

    /** The settlement when it can take weapon orders now (Ordnance Manufacturing working, planet not hostile), else null. */
    static SettlementData orders() {
        SettlementData s = any();
        if (s == null || !s.isAvailable() || !s.hasFacility(FACILITY)) return null;
        return s;
    }

    /** What the order panel shows about the settlement; null when it can't take weapon orders. */
    static JAOProduction.Settlement info() {
        SettlementData s = orders();
        if (s == null) return null;
        JAOProduction.Settlement out = new JAOProduction.Settlement();
        String n = s.getName();
        out.name = n == null || n.trim().isEmpty() ? s.getPrimaryPlanet().getName() : n.trim();
        out.budget = (int) s.getCurrentProductionBudget();
        out.maxBudget = (int) s.getStats().getMaxProductionBudget().getModifiedValue();
        out.perMonth = (int) s.getStats().getProductionBudgetPerMonth().getModifiedValue();
        out.costMult = new SettlementCustomProduction(s).getCostMult();
        out.pricing = blankOrder(out.costMult);
        return out;
    }

    /** A copy of the player's production list with nothing in it, priced at this multiplier (the copy has lists of its own). */
    static FactionProductionAPI blankOrder(float costMult) {
        FactionProductionAPI p = Global.getSector().getPlayerFaction().getProduction().clone();
        p.clear();
        p.setCostMult(costMult);
        return p;
    }

    /** Owned stock at the settlement: its storage (in the planet's storage, unless that market was counted already) and its open orders. */
    static void addStock(Map<String, Integer> weapons, Map<String, Integer> wings, Set<MarketAPI> counted) {
        SettlementData s = any();
        if (s == null) return;
        MarketAPI m = s.getPrimaryPlanet() == null ? null : s.getPrimaryPlanet().getMarket();
        if (m != null && counted.add(m)) {
            CargoAPI c = Misc.getStorageCargo(m);
            if (c != null) JAOFleetPlan.addCargo(c, weapons, wings);
        }
        for (SettlementData.ProductionData d : s.getProductionOrders()) {
            if (d != null && d.getCargo() != null) JAOFleetPlan.addCargo(d.getCargo(), weapons, wings);
        }
    }

    /**
     * One settlement order for these items. Returns {units, credits}; {0, 0} when nothing was placed. Throws if the
     * order costs more than the settlement's budget or the player's credits, or RAT refuses it.
     */
    static int[] order(Map<String, Integer> weapons, Map<String, Integer> wings) {
        SettlementData s = orders();
        if (s == null) throw new IllegalStateException("the settlement can't take weapon orders now");
        SettlementCustomProduction rat = new SettlementCustomProduction(s);
        FactionProductionAPI p = blankOrder(rat.getCostMult());
        int units = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean wing = pass == 1;
            for (Map.Entry<String, Integer> e : (wing ? wings : weapons).entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                if (p.addItem(wing ? ProductionItemType.FIGHTER : ProductionItemType.WEAPON, e.getKey(), e.getValue())) units += e.getValue();
            }
        }
        if (units <= 0) return new int[]{0, 0};
        int cost = p.getTotalCurrentCost();
        if (cost > s.getCurrentProductionBudget() + 0.5f)
            throw new IllegalStateException("the order costs " + cost + ", over the settlement's budget of " + (int) s.getCurrentProductionBudget());
        if (cost > Global.getSector().getPlayerFleet().getCargo().getCredits().get() + 0.5f)
            throw new IllegalStateException("the order costs " + cost + ", more than your credits");
        rat.notifyProductionSelected(p);
        return new int[]{units, cost};
    }
}
