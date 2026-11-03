package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import data.kaysaar.aotd.tot.produciton.specs.AoTDProductionSpec;
import data.kaysaar.aotd.tot.produciton.specs.AoTDProductionSpecManager;
import data.kaysaar.aotd.vok.campaign.econ.produciton.manager.AoTDProductionManager;
import data.kaysaar.aotd.vok.campaign.econ.produciton.order.AoTDProductionOrderData;
import data.kaysaar.aotd.vok.campaign.econ.produciton.order.AoTDProductionOrderSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ashes of the Domain: Vaults of Knowledge production, which replaces the game's custom production screen. Orders are
 * placed the way AoTD's own Confirm button does it: a new AoTDProductionOrderSnapshot, one AoTDProductionOrderData per
 * item (spec id, spec, amount), then initSnapshotConsumption(), which registers the order with AoTD's production
 * manager and the trade contract that brings in its resources. Each unit is paid when it's finished. Only touched
 * through JAOProduction when aotd_vok is enabled, so the mod runs without AoTD.
 */
final class JAOAotdProduction {
    private JAOAotdProduction() { }

    /** Throws if the AoTD classes this needs aren't what they were built against, or AoTD's item list isn't loaded. */
    static void probe() {
        AoTDProductionManager.getInstance().getCurrentSnapshots();
        if (AoTDProductionSpecManager.weaponProdSpecs == null || AoTDProductionSpecManager.weaponProdSpecs.isEmpty())
            throw new IllegalStateException("AoTD's production item list is empty");
    }

    static AoTDProductionSpec spec(boolean wing, String id) {
        return wing ? AoTDProductionSpecManager.getFighterSpec(id) : AoTDProductionSpecManager.getWeaponSpec(id);
    }

    /** AoTD's price per unit, or 0 when AoTD can't build it for the player. */
    static int unitCost(boolean wing, String id) {
        AoTDProductionSpec s = spec(wing, id);
        if (s == null || !s.isLearnedByFaction(Global.getSector().getPlayerFaction())) return 0;
        return s.getProductionCost();
    }

    /** Units still to come from AoTD orders, by weapon or wing id. */
    static void addIncoming(Map<String, Integer> weapons, Map<String, Integer> wings) {
        for (AoTDProductionOrderSnapshot snap : AoTDProductionManager.getInstance().getCurrentSnapshots()) {
            for (AoTDProductionOrderData d : snap.productionData) {
                int left = d.getRemainingUnits();
                if (left <= 0) continue;
                if (d.getType() == AoTDProductionSpec.AoTDProductionSpecType.WEAPON) weapons.merge(itemId(d), left, Integer::sum);
                else if (d.getType() == AoTDProductionSpec.AoTDProductionSpecType.FIGHTER) wings.merge(itemId(d), left, Integer::sum);
            }
        }
    }

    /** The weapon or wing id behind an order line (its spec's underlying spec), else the order's id. */
    private static String itemId(AoTDProductionOrderData d) {
        try {
            Object u = d.getSpec() == null ? null : d.getSpec().getUnderlyingSpec();
            if (u instanceof WeaponSpecAPI) return ((WeaponSpecAPI) u).getWeaponId();
            if (u instanceof FighterWingSpecAPI) return ((FighterWingSpecAPI) u).getId();
        } catch (Throwable ignored) { }
        return d.getId();
    }

    /** One AoTD order for these (wing?, id) -> count items. Returns the units ordered (0: nothing placed). */
    static int order(Map<String, Integer> weapons, Map<String, Integer> wings) {
        AoTDProductionOrderSnapshot snap = new AoTDProductionOrderSnapshot();
        Map<String, AoTDProductionOrderData> lines = new LinkedHashMap<>();
        int units = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean wing = pass == 1;
            for (Map.Entry<String, Integer> e : (wing ? wings : weapons).entrySet()) {
                AoTDProductionSpec s = spec(wing, e.getKey());
                if (s == null || e.getValue() <= 0) continue;
                AoTDProductionOrderData d = lines.get(s.getId());
                if (d != null) d.setAmount(d.amountToProduce + e.getValue());
                else lines.put(s.getId(), new AoTDProductionOrderData(s.getId(), s, e.getValue()));
                units += e.getValue();
            }
        }
        if (units <= 0) return 0;
        snap.productionData.addAll(lines.values());
        snap.initSnapshotConsumption();
        return units;
    }
}
