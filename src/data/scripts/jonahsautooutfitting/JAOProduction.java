package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionProductionAPI;
import com.fs.starfarer.api.campaign.FactionProductionAPI.ProductionItemType;

import java.util.Map;

/**
 * Which production the fleet order goes to: Ashes of the Domain's (JAOAotdProduction) when Vaults of Knowledge is
 * enabled, since it replaces the game's custom production screen; otherwise the game's own custom production. Decided
 * at run time, so the mod works with or without AoTD. If AoTD is enabled but its production can't be used (a changed
 * version), the game's own is used and the order panel says why (problem).
 */
final class JAOProduction {
    static final String AOTD_ID = "aotd_vok";
    private static Boolean aotd;
    static String problem;

    private JAOProduction() { }

    static boolean aotd() {
        if (aotd == null) {
            boolean on = false;
            try {
                if (Global.getSettings().getModManager().isModEnabled(AOTD_ID)) {
                    JAOAotdProduction.probe();
                    on = true;
                }
            } catch (Throwable t) {
                problem = "Ashes of the Domain's production couldn't be used (see starsector.log), so this goes to the game's own "
                        + "custom production, which AoTD's screen doesn't show";
                Global.getLogger(JAOProduction.class).warn("Jonah's Auto Outfitting: AoTD production not usable; using the game's custom production", t);
            }
            aotd = on;
        }
        return aotd;
    }

    /** What one unit costs through the production in use; 0 when it can't be ordered there. */
    static int unitCost(FactionProductionAPI prod, boolean wing, String id) {
        try {
            if (aotd()) return JAOAotdProduction.unitCost(wing, id);
            return prod.getUnitCost(wing ? ProductionItemType.FIGHTER : ProductionItemType.WEAPON, id);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Adds the units still to come from AoTD orders (the game's own queue is counted by the planner either way). */
    static void addIncoming(Map<String, Integer> weapons, Map<String, Integer> wings) {
        if (!aotd()) return;
        try {
            JAOAotdProduction.addIncoming(weapons, wings);
        } catch (Throwable t) {
            Global.getLogger(JAOProduction.class).warn("Jonah's Auto Outfitting: couldn't read AoTD's production orders", t);
        }
    }

    /** One AoTD order (aotd() only). Returns the units ordered; throws if AoTD refused it. */
    static int orderAotd(Map<String, Integer> weapons, Map<String, Integer> wings) {
        return JAOAotdProduction.order(weapons, wings);
    }
}
