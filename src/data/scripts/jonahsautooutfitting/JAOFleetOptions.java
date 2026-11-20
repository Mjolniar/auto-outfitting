package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;

/**
 * The Optimize fleet panel's settings (0.5.57, user 11/16: every setting Optimize fleet uses belongs on its own panel,
 * not in the Autofit panel). Per save, in the sector's persistent data (plain JDK types). The Autofit panel's own boxes
 * only change the single-ship Optimal fit.
 */
final class JAOFleetOptions {
    static final String USE_STORAGE = "jao_ofUseStorage", BUY_LEGAL = "jao_ofBuyLegal", BUY_BLACK = "jao_ofBuyBlack",
            BUDGET = "jao_ofBudget", SMODS = "jao_ofSmods", CHANGE_SMODS = "jao_ofChangeSmods";
    /** S-mods by ship type (0.5.73, user 11/19: "Add a toggle in here", "For each type"): per save, Optimal and Optimize fleet. */
    static final String SMODS_FRIGATES = "jao_ofSmodsFrigates", SMODS_DESTROYERS = "jao_ofSmodsDestroyers", SMODS_CRUISERS = "jao_ofSmodsCruisers",
            SMODS_CAPITALS = "jao_ofSmodsCapitals", SMODS_CIVILIAN = "jao_ofSmodsCivilian";
    /** Credit limit steps: share of the player's credits one Optimize fleet may spend on shops and s-mod changes. */
    static final float[] BUDGETS = {0.1f, 0.25f, 0.5f, 0.75f, 1f};
    static final float DEFAULT_BUDGET = 0.25f;

    private JAOFleetOptions() { }

    private static Object get(String key) {
        try { return Global.getSector().getPersistentData().get(key); } catch (Throwable t) { return null; }
    }

    static void set(String key, Object value) {
        try { Global.getSector().getPersistentData().put(key, value); } catch (Throwable ignored) { }
    }

    private static boolean flag(String key, boolean def) {
        Object o = get(key);
        return o instanceof Boolean ? (Boolean) o : def;
    }

    /** "Use storage here": storage at the docked market is a source, and what comes off goes there. On by default. */
    static boolean useStorage() { return flag(USE_STORAGE, true); }
    static boolean buyLegal() { return flag(BUY_LEGAL, false); }
    static boolean buyBlack() { return flag(BUY_BLACK, false); }
    static boolean changeSmods() { return flag(CHANGE_SMODS, false); }

    /** "Build in s-mods": until the player sets it here, it follows the Autofit panel's box (what Optimize fleet used before 0.5.57). */
    static boolean smods() {
        Object o = get(SMODS);
        return o instanceof Boolean ? (Boolean) o : JAOGoals.optionChecked(JAOAutofitPlugin.SMODS);
    }

    /** The per-save switch for warships of this size (all on until the player turns one off). */
    static String smodsKey(HullSize size) {
        if (size == null) return null;
        switch (size) {
            case FRIGATE: return SMODS_FRIGATES;
            case DESTROYER: return SMODS_DESTROYERS;
            case CRUISER: return SMODS_CRUISERS;
            case CAPITAL_SHIP: return SMODS_CAPITALS;
            default: return null;
        }
    }

    /** Whether s-mods get built into warships of this size (Optimal, Optimize fleet and its s-mod changes). */
    static boolean smodsOn(HullSize size) {
        String k = smodsKey(size);
        return k == null || flag(k, true);
    }

    /** D-mod repairs (0.5.75): on/off (off by default) and one box per ship type (all on), per save. */
    static final String REPAIR = "jao_ofRepair", REPAIR_FRIGATES = "jao_ofRepairFrigates", REPAIR_DESTROYERS = "jao_ofRepairDestroyers",
            REPAIR_CRUISERS = "jao_ofRepairCruisers", REPAIR_CAPITALS = "jao_ofRepairCapitals", REPAIR_CIVILIAN = "jao_ofRepairCivilian";

    static boolean repair() { return flag(REPAIR, false); }

    /** 0.5.77 (user 11/20: "I only want this ship and that ship"): fleet member ids picked for d-mod repairs, per save. */
    static final String REPAIR_PICKS = "jao_ofRepairPicks";

    static java.util.Set<String> repairPicks() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        Object o = get(REPAIR_PICKS);
        if (o instanceof java.util.Collection) for (Object x : (java.util.Collection<?>) o) if (x instanceof String) out.add((String) x);
        return out;
    }

    static void setRepairPick(String memberId, boolean on) {
        java.util.Set<String> s = repairPicks();
        if (on) s.add(memberId); else s.remove(memberId);
        set(REPAIR_PICKS, new java.util.ArrayList<>(s));
    }

    /** Whether ships of this type get their d-mods removed (civilian ships by the Civilian box). */
    static boolean repairType(HullSize size, boolean civilian) {
        if (civilian) return flag(REPAIR_CIVILIAN, true);
        if (size == null) return true;
        switch (size) {
            case FRIGATE: return flag(REPAIR_FRIGATES, true);
            case DESTROYER: return flag(REPAIR_DESTROYERS, true);
            case CRUISER: return flag(REPAIR_CRUISERS, true);
            case CAPITAL_SHIP: return flag(REPAIR_CAPITALS, true);
            default: return true;
        }
    }

    /** Whether civilian ships build in fleet-wide hullmods: until set in this save, the LunaLib setting (jao_civilianSmods). */
    static boolean smodsCivilian() { return flag(SMODS_CIVILIAN, JAOSettings.civilianSmods); }

    static float budget() {
        Object o = get(BUDGET);
        if (o instanceof Number) {
            float f = ((Number) o).floatValue();
            for (float b : BUDGETS) if (Math.abs(b - f) < 0.001f) return b;
        }
        return DEFAULT_BUDGET;
    }

    /** One step up (dir 1) or down (-1) the credit limit steps; returns the new limit. */
    static float stepBudget(int dir) {
        float b = step(budget(), dir);
        set(BUDGET, b);
        return b;
    }

    static float step(float now, int dir) {
        int i = 0;
        for (int k = 0; k < BUDGETS.length; k++) if (Math.abs(BUDGETS[k] - now) < 0.001f) i = k;
        i = Math.max(0, Math.min(BUDGETS.length - 1, i + dir));
        return BUDGETS[i];
    }

    /**
     * The game's Change s-mods is open here (refit panel R.canChangeSModsAtCurrentMarket, 0.98.5a): docked at the
     * player's own market with heavy industry, trading openly.
     */
    static boolean changeSmodsHere(MarketAPI market, CampaignUIAPI.CoreUITradeMode mode) {
        try {
            return market != null && mode == CampaignUIAPI.CoreUITradeMode.OPEN && market.isPlayerOwned() && Misc.hasHeavyIndustry(market);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The ship's s-mods can be changed: the game's s-mod button is off for hulls or variants tagged unrestorable or no_changing_smods. */
    static boolean canChangeSmods(FleetMemberAPI m) {
        try {
            for (String tag : new String[]{"unrestorable", Tags.NO_CHANGING_SMODS}) {
                if (m.getHullSpec().hasTag(tag) || m.getVariant().hasTag(tag)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The game's price of one s-mod change (one added or one removed) for this hull size (settings changeSmodsCost_*), 0 if unknown. */
    static float changePrice(HullSize size) {
        try { return Global.getSettings().getFloat("changeSmodsCost_" + size.name()); } catch (Throwable t) { return 0f; }
    }
}
