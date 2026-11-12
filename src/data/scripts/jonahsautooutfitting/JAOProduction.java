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
                Global.getLogger(JAOProduction.class).warn("Auto Outfitting: AoTD production not usable; using the game's custom production", t);
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
            Global.getLogger(JAOProduction.class).warn("Auto Outfitting: couldn't read AoTD's production orders", t);
        }
    }

    /** One AoTD order (aotd() only). Returns the units ordered; throws if AoTD refused it. */
    static int orderAotd(Map<String, Integer> weapons, Map<String, Integer> wings) {
        return JAOAotdProduction.order(weapons, wings);
    }

    // ------------------------------------------------------------------ what AoTD's orders take from your colonies

    /** Auto Colonies: from 0.2.39.0 its Special project supply plans producers for what AoTD's production orders take. */
    static final String CM_ID = "jonah_tweaks";

    /** AoTD only: commodity id -> units these items take from your colonies; empty without AoTD or if it can't be read. */
    static Map<String, Integer> resources(Map<String, Integer> weapons, Map<String, Integer> wings) {
        if (!aotd()) return new java.util.TreeMap<>();
        try {
            return JAOAotdProduction.resources(weapons, wings);
        } catch (Throwable t) {
            Global.getLogger(JAOProduction.class).warn("Auto Outfitting: couldn't read what AoTD's production takes", t);
            return new java.util.TreeMap<>();
        }
    }

    /** AoTD only: what all its production orders still wait for. */
    static Map<String, Integer> outstanding() {
        if (!aotd()) return new java.util.TreeMap<>();
        try {
            return JAOAotdProduction.outstanding();
        } catch (Throwable t) {
            return new java.util.TreeMap<>();
        }
    }

    /** Your colonies whose industries make this commodity. */
    static java.util.List<String> makers(String commodityId) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (com.fs.starfarer.api.campaign.econ.MarketAPI m : com.fs.starfarer.api.util.Misc.getPlayerMarkets(false)) {
            for (com.fs.starfarer.api.campaign.econ.Industry ind : m.getIndustries()) {
                try {
                    com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity q = ind.getSupply(commodityId);
                    if (q != null && q.getQuantity().getModifiedInt() > 0) { out.add(m.getName()); break; }
                } catch (Throwable ignored) { }
            }
        }
        return out;
    }

    /** The goods in need that no colony of yours makes (AoTD's order waits for them). */
    static java.util.List<String> unmade(Map<String, Integer> need) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String c : need.keySet()) if (makers(c).isEmpty()) out.add(c);
        return out;
    }

    /** "24 Heavy Armaments (Hot Springs makes it), 8 Electronic Components (no colony makes it)". */
    static String describe(Map<String, Integer> need) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            java.util.List<String> who = makers(e.getKey());
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(commodityName(e.getKey())).append(who.isEmpty() ? " (no colony makes it)"
                    : who.size() == 1 ? " (" + who.get(0) + " makes it)" : " (" + who.size() + " colonies make it)");
        }
        return sb.toString();
    }

    static String commodityName(String id) {
        try { return Global.getSettings().getCommoditySpec(id).getName(); } catch (Throwable t) { return id; }
    }

    /**
     * Auto Colonies 0.2.39.0 or later is enabled: its Special project supply (Settings, Items and AI cores) reads
     * AoTD's open production orders and plans a producer for every good no colony of yours makes, and its surplus
     * investment keeps back what the orders still owe. Found by mod id and version, so neither mod needs the other.
     */
    static boolean colonyManagerSupplies() {
        try {
            if (!Global.getSettings().getModManager().isModEnabled(CM_ID)) return false;
            String v = Global.getSettings().getModManager().getModSpec(CM_ID).getVersion().replaceFirst("^[^0-9]+", "");
            String[] p = v.split("[^0-9]+");
            // Auto Colonies numbers with a leading 0 since 2026-11-11 (2.45.2 became 0.2.45.2, then 0.3.0.0): skip it
            int at = p.length >= 3 && "0".equals(p[0]) ? 1 : 0;
            int major = Integer.parseInt(p[at]), minor = p.length > at + 1 ? Integer.parseInt(p[at + 1]) : 0;
            return major > 2 || (major == 2 && minor >= 39);
        } catch (Throwable t) {
            return false;
        }
    }
}
