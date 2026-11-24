package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CoreUIAPI;
import com.fs.starfarer.api.ui.HintPanelAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableFighter;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableWeapon;

import java.util.ArrayList;
import java.util.List;

/**
 * Buying for Optimize fleet (0.5.57, user 11/16). The shops at the docked market are read the way the refit screen reads
 * them for its weapon list (WeaponPickerDialog.getCombinedAvailable, 0.98.5a):
 *  - every submarket the game lets you trade with now (plugin.isEnabled with the screen's trade mode); storage (free
 *    transfer) is not a shop;
 *  - each weapon and LPC stack the shop doesn't refuse you (isIllegalOnSubmarket for PLAYER_BUY: a military market's
 *    standing rules);
 *  - at the screen's price: base value x shipWeaponBuyPriceMult x (1 + tariff), rounded.
 * Black markets (isBlackMarket: the vanilla one, Underworld's Cabal Exchange and Scrapyard) only with their own tick.
 * A purchase is reported as the refit screen reports one: a PlayerMarketTransaction to the shop's plugin and the sector.
 */
final class JAOShops {
    static final class Shop {
        final SubmarketAPI sub;
        final boolean black;
        final String name;

        Shop(SubmarketAPI sub, boolean black, String name) { this.sub = sub; this.black = black; this.name = name; }
    }

    private JAOShops() { }

    /** The core UI the refit screen runs in (for the shops' trade rules), or a stand-in with this trade mode. */
    static CoreUIAPI ui(Object refit, CampaignUIAPI.CoreUITradeMode fallback) {
        Object c = refit == null ? null : JAORefl.call(refit, "getCoreUI");
        if (c instanceof CoreUIAPI) return (CoreUIAPI) c;
        try {
            InteractionDialogAPI d = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
            c = d == null ? null : JAORefl.call(d, "getCoreUI");
        } catch (Throwable ignored) { }
        if (c instanceof CoreUIAPI) return (CoreUIAPI) c;
        return new Stub(fallback);
    }

    private static final class Stub implements CoreUIAPI {
        final CampaignUIAPI.CoreUITradeMode mode;
        Stub(CampaignUIAPI.CoreUITradeMode mode) { this.mode = mode; }
        public CampaignUIAPI.CoreUITradeMode getTradeMode() { return mode; }
        public HintPanelAPI getHintPanel() { return null; }
    }

    /** Trade mode of the screen, OPEN when it can't be read. */
    static CampaignUIAPI.CoreUITradeMode mode(CoreUIAPI ui) {
        try {
            CampaignUIAPI.CoreUITradeMode m = ui.getTradeMode();
            return m == null ? CampaignUIAPI.CoreUITradeMode.OPEN : m;
        } catch (Throwable t) {
            return CampaignUIAPI.CoreUITradeMode.OPEN;
        }
    }

    /** Shops at this market you can buy from now, in the market's order; storage and shops closed to you are left out. */
    static List<Shop> at(MarketAPI market, CoreUIAPI ui) { return at(market, ui, true); }

    /** refresh false: only which shops there are (0.5.60: the settings view no longer restocks every shop on each redraw). */
    static List<Shop> at(MarketAPI market, CoreUIAPI ui, boolean refresh) {
        List<Shop> out = new ArrayList<>();
        if (market == null || market.isPlanetConditionMarketOnly()) return out;
        // the stock as the refit screen's weapon picker sees it: every submarket updated first (a shop not opened yet
        // has no cargo, and one not visited for a month has old stock)
        if (refresh) for (SubmarketAPI sub : market.getSubmarketsCopy()) {
            try {
                if (sub.getPlugin() == null) continue;
                sub.getPlugin().updateCargoPrePlayerInteraction();
                com.fs.starfarer.api.campaign.listeners.ListenerUtil.reportSubmarketCargoAndShipsUpdated(sub);
            } catch (Throwable t) {
                Global.getLogger(JAOShops.class).warn("Auto Outfitting: couldn't update the shop " + sub.getSpecId(), t);
            }
        }
        for (SubmarketAPI sub : market.getSubmarketsCopy()) {
            try {
                SubmarketPlugin plugin = sub.getPlugin();
                if (plugin == null || (plugin.isFreeTransfer() && !JAOCompat.freeShop(sub.getSpecId())) || !plugin.isEnabled(ui)) continue;
                String name = sub.getNameOneLine();
                out.add(new Shop(sub, plugin.isBlackMarket(), name == null || name.trim().isEmpty() ? sub.getSpecId() : name.trim()));
            } catch (Throwable t) {
                Global.getLogger(JAOShops.class).warn("Auto Outfitting: couldn't read the shop " + sub.getSpecId() + "; left out", t);
            }
        }
        return out;
    }

    /** Names of the legal (black false) or black-market shops, joined with commas; "" when there are none. */
    static String names(List<Shop> shops, boolean black) {
        List<String> n = new ArrayList<>();
        for (Shop s : shops) if (s.black == black) n.add(s.name);
        return String.join(", ", n);
    }

    /** The ticked shops' weapons and LPCs as priced plan stock (PlanWeapon / PlanFighter with their shop). */
    static void stock(List<Shop> shops, boolean legal, boolean black, List<AvailableWeapon> weapons, List<AvailableFighter> fighters) {
        float mult = buyMult();
        for (Shop s : shops) {
            if (s.black ? !black : !legal) continue;
            try {
                SubmarketPlugin plugin = s.sub.getPlugin();
                CargoAPI c = plugin.getCargo();
                if (c == null) continue;
                float tariff = plugin.getTariff();
                for (CargoStackAPI st : c.getStacksCopy()) {
                    int n = (int) st.getSize();
                    if (n <= 0) continue;
                    if (st.isWeaponStack()) {
                        WeaponSpecAPI spec = st.getWeaponSpecIfWeapon();
                        if (spec == null || plugin.isIllegalOnSubmarket(st, SubmarketPlugin.TransferAction.PLAYER_BUY)) continue;
                        JAOFleetPlan.PlanWeapon w = new JAOFleetPlan.PlanWeapon(spec, price(st.getBaseValuePerUnit(), mult, tariff), n, false);
                        w.sub = s.sub;
                        weapons.add(w);
                    } else if (st.isFighterWingStack()) {
                        FighterWingSpecAPI spec = st.getFighterWingSpecIfWing();
                        if (spec == null || plugin.isIllegalOnSubmarket(st, SubmarketPlugin.TransferAction.PLAYER_BUY)) continue;
                        JAOFleetPlan.PlanFighter f = new JAOFleetPlan.PlanFighter(spec, price(st.getBaseValuePerUnit(), mult, tariff), n, false);
                        f.sub = s.sub;
                        fighters.add(f);
                    }
                }
            } catch (Throwable t) {
                Global.getLogger(JAOShops.class).warn("Auto Outfitting: couldn't read what " + s.name + " sells; left out", t);
            }
        }
    }

    /**
     * 0.5.81: a free Private Arsenal here (Omega core: tariff -1, price 0) is stock like storage, with or without Buy from
     * legal shops. Its stock is refreshed first (the shop list is read without a refresh when no buy box is ticked).
     */
    static void stockFree(List<Shop> shops, List<AvailableWeapon> weapons, List<AvailableFighter> fighters) {
        List<Shop> free = new ArrayList<>();
        for (Shop s : shops) {
            try {
                if (!JAOCompat.REPA_ARSENAL.equals(s.sub.getSpecId()) || s.sub.getPlugin() == null || s.sub.getPlugin().getTariff() > -1f) continue;
                s.sub.getPlugin().updateCargoPrePlayerInteraction();
                free.add(s);
            } catch (Throwable t) {
                Global.getLogger(JAOShops.class).warn("Auto Outfitting: couldn't read the Private Arsenal", t);
            }
        }
        if (!free.isEmpty()) stock(free, true, false, weapons, fighters);
    }

    static float buyMult() {
        try { return Global.getSettings().getFloat("shipWeaponBuyPriceMult"); } catch (Throwable t) { return 1f; }
    }

    /**
     * The refit screen's price for one: base value x shipWeaponBuyPriceMult x (1 + tariff), rounded; at least 1. A tariff
     * of -1 or less is a shop that gives its stock away (the Private Arsenal with an Omega core): 0.
     */
    static float price(float baseValue, float mult, float tariff) {
        if (tariff <= -1f) return 0f;
        return Math.max(1f, Math.round(baseValue * mult * (1f + tariff)));
    }

    /**
     * Buys n of one shop entry into the fleet's cargo at the planned price: out of the shop's cargo, credits paid, the
     * purchase reported. Buys fewer if the shop has fewer now or the credits run out. Returns how many were bought.
     */
    static int buy(Object entry, int n, CargoAPI cargo, CoreUIAPI ui) {
        SubmarketAPI sub;
        String id;
        float price;
        boolean wing;
        if (entry instanceof JAOFleetPlan.PlanWeapon) {
            JAOFleetPlan.PlanWeapon w = (JAOFleetPlan.PlanWeapon) entry;
            sub = w.sub; id = w.getId(); price = w.price; wing = false;
        } else if (entry instanceof JAOFleetPlan.PlanFighter) {
            JAOFleetPlan.PlanFighter f = (JAOFleetPlan.PlanFighter) entry;
            sub = f.sub; id = f.getId(); price = f.price; wing = true;
        } else {
            return 0;
        }
        if (sub == null || n <= 0) return 0;
        CargoAPI shop = sub.getPlugin().getCargo();
        int have = wing ? shop.getNumFighters(id) : shop.getNumWeapons(id);
        float credits = cargo.getCredits().get();
        int k = Math.min(n, have);
        if (price > 0f) k = Math.min(k, (int) Math.floor(credits / price));
        if (k <= 0) return 0;
        if (wing) { shop.removeFighters(id, k); cargo.addFighters(id, k); }
        else { shop.removeWeapons(id, k); cargo.addWeapons(id, k); }
        cargo.getCredits().subtract(k * price);
        try {
            PlayerMarketTransaction tx = new PlayerMarketTransaction(sub.getMarket(), sub, mode(ui));
            tx.setCreditValue(-k * price);
            CargoAPI bought = Global.getFactory().createCargo(true);
            if (wing) bought.addFighters(id, k); else bought.addWeapons(id, k);
            tx.setBought(bought);
            sub.getPlugin().reportPlayerMarketTransaction(tx);
            Global.getSector().reportPlayerMarketTransaction(tx);
        } catch (Throwable t) {
            Global.getLogger(JAOShops.class).warn("Auto Outfitting: bought " + k + " " + id + " at " + sub.getNameOneLine() + " but couldn't report the sale", t);
        }
        return k;
    }

    /** The shop an entry comes from, or null for owned stock. */
    static SubmarketAPI shopOf(Object entry) {
        if (entry instanceof JAOFleetPlan.PlanWeapon) return ((JAOFleetPlan.PlanWeapon) entry).sub;
        if (entry instanceof JAOFleetPlan.PlanFighter) return ((JAOFleetPlan.PlanFighter) entry).sub;
        return null;
    }

    /** Credits the shop entries in a ship's used list cost (one per unit). */
    static double cost(List<Object> used) {
        double c = 0.0;
        for (Object o : used) {
            if (o instanceof JAOFleetPlan.PlanWeapon && ((JAOFleetPlan.PlanWeapon) o).sub != null) c += ((JAOFleetPlan.PlanWeapon) o).price;
            else if (o instanceof JAOFleetPlan.PlanFighter && ((JAOFleetPlan.PlanFighter) o).sub != null) c += ((JAOFleetPlan.PlanFighter) o).price;
        }
        return c;
    }

    /** How many entries in a used list come from shops. */
    static int count(List<Object> used) {
        int n = 0;
        for (Object o : used) if (shopOf(o) != null) n++;
        return n;
    }
}
