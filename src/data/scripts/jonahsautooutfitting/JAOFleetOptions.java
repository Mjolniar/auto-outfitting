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
