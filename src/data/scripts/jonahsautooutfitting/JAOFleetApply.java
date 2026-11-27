package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.plog.PlaythroughLog;
import com.fs.starfarer.api.impl.campaign.plog.SModRecord;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableFighter;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableWeapon;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * "Optimize fleet" (user 11/05: "click the button and it just does every single ship with every single s-mod and item
 * and weapon it can possibly equip, one by one down the list"): the fleet plan (JAOFleetPlan) from what can be fitted
 * here, then every planned ship refitted to it in one go.
 *  - Stock: fleet cargo, this market's storage when you're docked where you can use it ("Use storage here"), and
 *    everything on the planned ships; with "Buy from legal shops" / "Buy from black markets" (0.5.57) also what the
 *    market's shops sell (JAOShops), inside the credit limit, bought on Apply.
 *  - Weapons and LPCs come off every planned ship into fleet cargo first; each ship then takes its plan from cargo, then
 *    storage. What came off and isn't used again goes to that storage (user: "let it all sit in storage"), otherwise it
 *    stays in cargo.
 *  - Hullmods change only where the refit rules allow here (spaceport hullmods only at a spaceport).
 *  - New s-mods (only with the panel's "Build in s-mods" ticked) cost a story point each through the game's own
 *    build-in (bonus XP, playthrough log); the plan never counts on more story points than you have above the reserve.
 *  - S-mod changes ("Change s-mods with credits", 0.5.57): at your colony with heavy industry, the game's Change s-mods
 *    price per s-mod removed or added, inside the credit limit.
 *  - The ship on the refit screen is changed through the screen's own copy, then the screen is synced and saved.
 * Civilian ships (with the logistics fit setting on) get that fit: campaign hullmods, no weapons, no vents or capacitors;
 * with s-mods asked for and the civilian s-mods setting on, cruisers, capitals and salvage rigs build in fleet-wide ones.
 * Away from a market every changed ship loses the CR the refit screen takes for an in-space refit (the game's own
 * FleetMemberAPI.setVariant with withRefit); the panel shows each ship's loss before Apply.
 */
final class JAOFleetApply {
    final JAOFleetPlan fleet;
    final CargoAPI cargo, storage;
    final MarketAPI market;
    /** The refit panel (for the ship it shows), or null. */
    final Object refit;
    final JAOFleetMix before;
    int civilian;
    /** "Build in s-mods" was ticked in the Optimize fleet panel when this plan started. */
    final boolean smods;
    /** The screen's core UI (the shops' trade rules) and its trade mode. */
    final com.fs.starfarer.api.campaign.CoreUIAPI ui;
    final CampaignUIAPI.CoreUITradeMode tradeMode;
    /** Shops at this market you can buy from (0.5.57), and which kinds are ticked. */
    final List<JAOShops.Shop> shops;
    final boolean buyLegal, buyBlack;
    /** "Use storage here" is unticked. */
    final boolean storageOff;
    /** "Change s-mods with credits" is ticked (changeOn) and the game's Change s-mods is open here (changeHere). */
    final boolean changeOn, changeHere;
    /** Credits the plan may spend on shop items and s-mod changes (0 when neither is on). */
    final float creditLimit;
    /** Planned purchases: items, their cost, s-mod changes and their cost. */
    int buyItems, smodChanges;
    double buyTotal, changeTotal;
    /** 0.5.75: d-mod repairs (JAODmods), after everything else in the plan, from what's left of the credit limit and the parts here. */
    final boolean repairOn;
    List<JAODmods.Repair> repairs = new ArrayList<>();
    int repairParts, repairCredits;
    /** Refitting here costs CR (no market to refit at: in space, or at a planet with no colony). */
    final boolean fieldRefit;
    /** Officer placement (0.5.39), when "Place officers" is ticked in the Optimize fleet panel; else null. */
    final JAOOfficers officers;
    /** ESR augment placement (0.5.52), when ESR is installed, "Place augments" is ticked and ESR's bay is here; else null. */
    final JAOAugments augments;
    /** ESR is installed and "Place augments" is ticked, but there is no Extra Systems bay here. */
    final boolean augmentsAway;
    /** Sector persistent data key of the "Place officers" box (per save; off by default). */
    static final String PLACE_OFFICERS = "jao_placeOfficers";

    static boolean placeOfficers() {
        try { return Boolean.TRUE.equals(Global.getSector().getPersistentData().get(PLACE_OFFICERS)); } catch (Throwable t) { return false; }
    }

    static void setPlaceOfficers(boolean on) {
        try { Global.getSector().getPersistentData().put(PLACE_OFFICERS, on); } catch (Throwable ignored) { }
    }

    /** Sector persistent data key of the "Strip stored ships" box (0.5.53; per save; off by default). */
    static final String STRIP_STORED = "jao_stripStoredShips";

    static boolean stripStored() {
        try { return Boolean.TRUE.equals(Global.getSector().getPersistentData().get(STRIP_STORED)); } catch (Throwable t) { return false; }
    }

    static void setStripStored(boolean on) {
        try { Global.getSector().getPersistentData().put(STRIP_STORED, on); } catch (Throwable ignored) { }
    }

    /**
     * 0.5.81: Reverse Engineered Private Arsenal (JAOReverse): the Reverse Engineering Storage here (null: none, or the box
     * is off) and the weapons and wings set aside for it, one each.
     */
    final com.fs.starfarer.api.campaign.econ.SubmarketAPI reverseHere;
    List<String> reverseW = new ArrayList<>(), reverseF = new ArrayList<>();

    /** Ships in this market's storage whose weapons and LPCs join the stock and come off on Apply ("Strip stored ships"). */
    final List<FleetMemberAPI> stored = new ArrayList<>();
    /** Weapons and LPCs on those ships. */
    int storedItems;
    /** "Strip stored ships" is ticked, but there is no storage of yours here. */
    boolean storedAway;

    /** What applying the plan changes on one ship. */
    static final class Change {
        final JAOFleetPlan.ShipPlan ship;
        int weapons, wings, flux;
        /** CR the refit takes away from a market (0-1). */
        float cr;
        final List<String> added = new ArrayList<>(), removed = new ArrayList<>(), smods = new ArrayList<>();
        /** 0.5.57: shop items this ship's plan buys; s-mods it builds in with credits and takes off. */
        int bought;
        final List<String> smodsBought = new ArrayList<>(), smodsRemoved = new ArrayList<>();

        Change(JAOFleetPlan.ShipPlan ship) { this.ship = ship; }
        boolean any() { return weapons + wings + flux + bought > 0 || !added.isEmpty() || !removed.isEmpty() || !smods.isEmpty()
                || !smodsBought.isEmpty() || !smodsRemoved.isEmpty(); }
    }

    final List<Change> changes = new ArrayList<>();
    int storyPoints, fromStorage, changedShips, crShips;
    /** Largest CR loss on one ship (0-1). */
    float crMax;
    boolean summarized, applied;

    JAOFleetApply(Object refit) {
        this.refit = refit;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        cargo = pf.getCargo();
        market = dockedMarket();
        storageOff = !JAOFleetOptions.useStorage();
        storage = market != null && !storageOff && Misc.playerHasStorageAccess(market) ? Misc.getStorageCargo(market) : null;
        int[] civ = new int[1];
        List<FleetMemberAPI> members = pf.getFleetData().getMembersListCopy();
        List<FleetMemberAPI> ships = JAOFleetPlan.combatShips(members, civ);
        // civilian ships get the logistics fit too (user 11/06), without s-mods; what they carry joins the stock
        List<FleetMemberAPI> planned = new ArrayList<>(ships);
        planned.addAll(JAOFleetPlan.civilianShips(members));
        civilian = planned.size() - ships.size();
        Map<String, Integer> w = new TreeMap<>(), f = new TreeMap<>();
        JAOFleetPlan.addCargo(cargo, w, f);
        if (storage != null) JAOFleetPlan.addCargo(storage, w, f);
        // ships in this market's storage (0.5.53, user 11/15: the weapons on ships parked at the home base get used): with
        // "Strip stored ships" ticked their weapons and LPCs join the stock and come off on Apply; what the fleet doesn't
        // use goes to storage as items. Locked ships keep theirs.
        if (stripStored() && !storageOff) {
            if (storage == null) storedAway = true;
            else {
                try {
                    com.fs.starfarer.api.campaign.FleetDataAPI sd = storage.getMothballedShips();
                    if (sd != null) for (FleetMemberAPI m : sd.getMembersListCopy()) {
                        if (m == null || m.isFighterWing() || m.getVariant() == null || JAOLocks.locked(m) || JAOOptimizer.noAutofit(m)) continue;
                        Map<String, Integer> sw = new TreeMap<>(), sf = new TreeMap<>();
                        try {
                            JAOFleetPlan.addMountedWithModules(m.getVariant(), sw, sf);
                        } catch (Throwable t) {
                            Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: couldn't read the stored ship " + m.getShipName() + "; left as it is", t);
                            continue;
                        }
                        int n = 0;
                        for (int x : sw.values()) n += x;
                        for (int x : sf.values()) n += x;
                        if (n == 0) continue;
                        stored.add(m);
                        storedItems += n;
                        for (Map.Entry<String, Integer> e : sw.entrySet()) w.merge(e.getKey(), e.getValue(), Integer::sum);
                        for (Map.Entry<String, Integer> e : sf.entrySet()) f.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                } catch (Throwable t) {
                    Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: couldn't read the ships in storage", t);
                }
            }
        }
        for (FleetMemberAPI m : planned) JAOFleetPlan.addMountedWithModules(live(m, false), w, f);
        // 0.5.81 (user 11/23): one of every weapon (and wing, from Hub II) the Reverse Engineering Hub here hasn't done is set
        // aside for its storage before the plan; locked ships and the refit screen's stock-variant ship keep theirs
        reverseHere = JAOReverse.present() && JAOFleetOptions.reverse() ? JAOReverse.storage(market) : null;
        if (reverseHere != null) {
            try {
                Map<String, Integer> kw = new TreeMap<>(), kf = new TreeMap<>();
                for (FleetMemberAPI m : planned) {
                    boolean keep = JAOLocks.locked(m) || JAOOptimizer.noAutofit(m);
                    if (!keep) try { keep = shown(m) && live(m, false).isStockVariant(); } catch (Throwable ignored) { }
                    if (keep) JAOFleetPlan.addMountedWithModules(live(m, false), kw, kf);
                }
                CargoAPI re = reverseHere.getCargo();
                java.util.Set<String> dw = JAOReverse.done(false), df = JAOReverse.done(true);
                reverseW = JAOReverse.pick(JAOReverse.movable(w, kw), dw, JAOReverse.held(re, false), JAOReverse.pending(false, dw), JAOReverse::weaponOk);
                if (JAOReverse.tier(market) >= 2)
                    reverseF = JAOReverse.pick(JAOReverse.movable(f, kf), df, JAOReverse.held(re, true), JAOReverse.pending(true, df), JAOReverse::wingOk);
                for (String id : reverseW) w.merge(id, -1, Integer::sum);
                for (String id : reverseF) f.merge(id, -1, Integer::sum);
                Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: reverse engineering at " + market.getName() + " (hub tier " + JAOReverse.tier(market)
                        + ", " + dw.size() + " weapons and " + df.size() + " wings done): sending " + reverseW + " " + reverseF);
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: couldn't pick items for reverse engineering", t);
                reverseW = new ArrayList<>();
                reverseF = new ArrayList<>();
            }
        }
        List<AvailableWeapon> weapons = new ArrayList<>();
        List<AvailableFighter> fighters = new ArrayList<>();
        JAOFleetPlan.owned(w, f, weapons, fighters);
        before = JAOFleetPlanner.currentMix(ships);
        CampaignUIAPI.CoreUITradeMode guess = market == null ? CampaignUIAPI.CoreUITradeMode.NONE
                : market.getFaction().isHostileTo(Factions.PLAYER) ? CampaignUIAPI.CoreUITradeMode.SNEAK : CampaignUIAPI.CoreUITradeMode.OPEN;
        ui = JAOShops.ui(refit, guess);
        CampaignUIAPI.CoreUITradeMode mode = market == null ? guess : JAOShops.mode(ui);
        tradeMode = mode;
        // the panel's own ticks (0.5.57); "Build in s-mods" follows the Autofit box until it's set on the panel
        smods = JAOFleetOptions.smods();
        shops = market == null ? new ArrayList<>() : JAOShops.at(market, ui, JAOFleetOptions.buyLegal() || JAOFleetOptions.buyBlack());
        buyLegal = JAOFleetOptions.buyLegal();
        buyBlack = JAOFleetOptions.buyBlack();
        int owned = weapons.size() + fighters.size();
        if (buyLegal || buyBlack) JAOShops.stock(shops, buyLegal, buyBlack, weapons, fighters);
        // 0.5.81: a free Private Arsenal here gives its stock away, so it's used without the legal box too
        if (!buyLegal) JAOShops.stockFree(shops, weapons, fighters);
        boolean buying = weapons.size() + fighters.size() > owned;
        changeOn = JAOFleetOptions.changeSmods();
        changeHere = JAOFleetOptions.changeSmodsHere(market, mode);
        float credits = 0f;
        try { credits = cargo.getCredits().get(); } catch (Throwable ignored) { }
        // 0.5.78: ticking ships for d-mod repairs turns repairs on too (the user picked a capital and nothing happened)
        boolean picked = false;
        java.util.Set<String> picks = JAOFleetOptions.repairPicks();
        for (FleetMemberAPI m : planned) if (JAODmods.picked(picks, m.getId(), live(m, false))) picked = true;
        repairOn = JAOFleetOptions.repair() || picked;
        creditLimit = buying || (changeOn && changeHere) || repairOn ? (float) Math.floor(credits * JAOFleetOptions.budget()) : 0f;
        fieldRefit = fieldRefit(refit, market);
        fleet = new JAOFleetPlan(planned, weapons, fighters, true, smods, market, mode);
        fleet.buying = buying;
        fleet.changeSmods = changeOn && changeHere;
        if (buying || fleet.changeSmods) fleet.creditsLeft = creditLimit;
        fleet.variantOf = m -> live(m, false);
        fleet.itemMarket(storage != null ? market : null);
        // 0.5.60: the refit screen's ship on a stock variant can't be changed in place (live), so it's kept as it is and
        // what it carries stays on it
        for (JAOFleetPlan.ShipPlan s : fleet.ships) {
            try {
                if (!s.locked && shown(s.member) && live(s.member, false).isStockVariant()) s.keep = true;
            } catch (Throwable ignored) { }
        }
        JAOOfficers o = null;
        if (placeOfficers()) {
            try { o = new JAOOfficers(fleet); } catch (Throwable t) { Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: officer placement couldn't start", t); }
        }
        officers = o;
        JAOAugments a = null;
        boolean away = false;
        if (JAOEsr.present() && JAOAugments.place()) {
            if (JAOAugments.bayHere(market)) {
                try { a = new JAOAugments(fleet, cargo, storage); } catch (Throwable t) { Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: augment placement couldn't start", t); }
            } else away = true;
        }
        augments = a;
        augmentsAway = away;
    }

    /** The progress line: officer placement first, then the plan. */
    String progress() {
        if (officers != null && !officers.done) return officers.progress();
        return augments != null && !augments.done ? augments.progress() : fleet.progress();
    }

    /** Officers the placement moves (0 without it). */
    int officerMoves() { return officers == null ? 0 : officers.moves.size(); }

    /** Ships whose augments the placement changes (0 without it). */
    int augmentMoves() { return augments == null ? 0 : augments.moves.size(); }

    /** Plans for about budgetMs; true once the plan and its summary are ready. */
    boolean step(long budgetMs) {
        // officers first: the plan fits every ship under its new captain
        if (officers != null && !officers.done) {
            try {
                if (!officers.step(budgetMs)) return false;
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: officer placement failed; ships keep their officers", t);
                officers.done = true;
                officers.captains.clear();
                officers.moves.clear();
                fleet.captains.clear();
            }
            return false;
        }
        // then augments: the plan fits every ship for the augments it ends up with
        if (augments != null && !augments.done) {
            try {
                if (!augments.step(budgetMs)) return false;
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: augment placement failed; ships keep their augments", t);
                augments.done = true;
                augments.target.clear();
                augments.moves.clear();
                fleet.augmentPlan.clear();
            }
            return false;
        }
        if (!fleet.done && !fleet.step(budgetMs)) return false;
        if (!summarized) summarize();
        return true;
    }

    /**
     * True when a refit here costs CR. The refit screen charges it when it has no other entity (FleetMember.setVariant
     * with the panel), which happens when it opened with trade mode NONE: in space, or at a planet with no colony.
     * Without the panel: no market, or a planet's condition market only.
     */
    static boolean fieldRefit(Object refit, MarketAPI market) {
        if (refit != null && JAORefl.method(refit.getClass(), "getOtherEntity") != JAORefl.NONE) return JAORefl.call(refit, "getOtherEntity") == null;
        return market == null || market.isPlanetConditionMarketOnly();
    }

    /** The ship the refit screen shows: it is changed through the screen, which charges its CR itself on save. */
    boolean shown(FleetMemberAPI m) { return refit != null && JAORefl.call(refit, "getMember") == m; }

    /**
     * The CR the game takes for refitting m from one variant to another away from a market (min with its CR, as the
     * refit screen shows it), 0 if it can't be worked out. See JAOModel.refitCrLoss.
     */
    static float crLoss(FleetMemberAPI m, ShipVariantAPI from, ShipVariantAPI to) {
        try {
            MutableCharacterStatsAPI cs = m.getFleetCommander() != null ? m.getFleetCommander().getStats() : null;
            float loss = JAOModel.refitCrLoss(refitDiff(cs, from, to, true), refitDiff(cs, from, to, false),
                    to.getHullSpec().getOrdnancePoints(cs), m.getRepairTracker().getBaseCR(),
                    Global.getSettings().getCRPlugin().getMalfunctionThreshold(null));
            return Math.max(0f, Math.min(loss, m.getRepairTracker().getCR()));
        } catch (Throwable t) {
            return 0f;
        }
    }

    /**
     * HullVariantSpec.computeVariantDiff through the API: the OP of weapons and wings that came off or changed (x
     * crLossMultForRefitWeapons), vents and capacitors removed, hullmods removed (x crLossMultForRefitHullmods); with
     * removedOnly false also what went on, and vents and capacitors either way. All x crLossMultForRefit.
     */
    static float refitDiff(MutableCharacterStatsAPI cs, ShipVariantAPI a, ShipVariantAPI b, boolean removedOnly) {
        com.fs.starfarer.api.SettingsAPI set = Global.getSettings();
        float all = set.getFloat("crLossMultForRefit"), wm = set.getFloat("crLossMultForRefitWeapons"), hm = set.getFloat("crLossMultForRefitHullmods");
        float d = 0f;
        for (WeaponSlotAPI slot : a.getHullSpec().getAllWeaponSlotsCopy()) {
            String x = a.getWeaponId(slot.getId()), y = b.getWeaponId(slot.getId());
            if (same(x, y)) continue;
            if (x != null) d += a.getWeaponSpec(slot.getId()).getOrdnancePointCost(cs, a.getStatsForOpCosts()) * wm;
            else if (!removedOnly) d += b.getWeaponSpec(slot.getId()).getOrdnancePointCost(cs) * wm;
        }
        for (int i = 0; i < 20; i++) {
            String x = wingAt(a, i), y = wingAt(b, i);
            if (same(x, y)) continue;
            if (x != null) d += set.getFighterWingSpec(x).getOpCost(a.getStatsForOpCosts()) * wm;
            else if (!removedOnly) d += set.getFighterWingSpec(y).getOpCost(b.getStatsForOpCosts()) * wm;
        }
        if (removedOnly) {
            d += Math.max(0, a.getNumFluxCapacitors() - b.getNumFluxCapacitors()) + Math.max(0, a.getNumFluxVents() - b.getNumFluxVents());
        } else {
            d += Math.abs(a.getNumFluxCapacitors() - b.getNumFluxCapacitors()) + Math.abs(a.getNumFluxVents() - b.getNumFluxVents());
        }
        for (String id : a.getHullMods()) if (!b.getHullMods().contains(id)) d += set.getHullModSpec(id).getCostFor(a.getHullSize()) * hm;
        if (!removedOnly) for (String id : b.getHullMods()) if (!a.getHullMods().contains(id)) d += set.getHullModSpec(id).getCostFor(a.getHullSize()) * hm;
        return d * all;
    }

    static MarketAPI dockedMarket() {
        InteractionDialogAPI d = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        return d == null || d.getInteractionTarget() == null ? null : d.getInteractionTarget().getMarket();
    }

    /** What applying changes, ship by ship. */
    private void summarize() {
        summarized = true;
        Map<String, Integer> shopUnits = new TreeMap<>();
        Map<String, Integer> need = new TreeMap<>(), have = new TreeMap<>();
        for (JAOFleetPlan.ShipPlan s : fleet.ships) {
            if (s.plan == null) continue;
            ShipVariantAPI now = live(s.member, false);
            Change c = new Change(s);
            c.weapons = diffWeapons(now, s.plan);
            for (String slot : now.getStationModules().keySet()) {
                ShipVariantAPI a = now.getModuleVariant(slot), b = s.plan.getModuleVariant(slot);
                if (a == null || b == null) continue;
                c.weapons += diffWeapons(a, b);
                // 0.5.102: a module's wings and ordinary hullmods count too (a plan that changed only those was skipped)
                int mb = Math.max(Global.getSettings().computeNumFighterBays(a), Global.getSettings().computeNumFighterBays(b));
                for (int i = 0; i < mb; i++) if (!same(wingAt(a, i), wingAt(b, i))) c.wings++;
                Set<String> ma = new LinkedHashSet<>(a.getNonBuiltInHullmods()), mbm = new LinkedHashSet<>(b.getNonBuiltInHullmods());
                for (String id : mbm) if (!ma.contains(id)) c.added.add(b.getHullSpec().getHullName() + ": " + JAOSMods.name(id));
                for (String id : ma) if (!mbm.contains(id) && !b.getSMods().contains(id)) c.removed.add(a.getHullSpec().getHullName() + ": " + JAOSMods.name(id));
            }
            int bays = Math.max(Global.getSettings().computeNumFighterBays(now), Global.getSettings().computeNumFighterBays(s.plan));
            for (int i = 0; i < bays; i++) if (!same(wingAt(now, i), wingAt(s.plan, i))) c.wings++;
            Set<String> a = new LinkedHashSet<>(now.getNonBuiltInHullmods()), b = new LinkedHashSet<>(s.plan.getNonBuiltInHullmods());
            // getNonBuiltInHullmods leaves s-mods out: ordinary hullmods on and off, then s-mods from the s-mod lists
            for (String id : b) if (!a.contains(id)) c.added.add(JAOSMods.name(id));
            for (String id : a) if (!b.contains(id) && !s.plan.getSMods().contains(id)) c.removed.add(JAOSMods.name(id));
            for (String id : s.plan.getSMods()) if (!now.getSMods().contains(id) && !s.smodsBought.contains(id)) c.smods.add(JAOSMods.name(id));
            // 0.5.85: s-mods planned on its modules
            for (String slot : s.plan.getStationModules().keySet()) {
                ShipVariantAPI mp = s.plan.getModuleVariant(slot), mn = now.getModuleVariant(slot);
                if (mp == null) continue;
                for (String id : mp.getSMods()) if (mn == null || !mn.getSMods().contains(id)) c.smods.add(mp.getHullSpec().getHullName() + ": " + JAOSMods.name(id));
            }
            for (String id : s.smodsBought) c.smodsBought.add(JAOSMods.name(id));
            for (String id : s.smodsRemoved) c.smodsRemoved.add(JAOSMods.name(id));
            // 0.5.94: Shrouded s-mods the plan takes off its modules
            for (String slot : s.plan.getStationModules().keySet()) {
                ShipVariantAPI mp = s.plan.getModuleVariant(slot), mn = now.getModuleVariant(slot);
                if (mp == null || mn == null) continue;
                for (String id : shroudedSmodsOff(mn, mp)) {
                    c.smodsRemoved.add(mn.getHullSpec().getHullName() + ": " + JAOSMods.name(id));
                    smodChanges++;
                }
            }
            c.bought = JAOShops.count(s.used);
            buyItems += c.bought;
            buyTotal += s.buyCost;
            smodChanges += s.smodsBought.size() + s.smodsRemoved.size();
            changeTotal += s.changeCost;
            if (now.getNumFluxVents() != s.plan.getNumFluxVents() || now.getNumFluxCapacitors() != s.plan.getNumFluxCapacitors()) c.flux = 1;
            for (String slot : s.plan.getStationModules().keySet()) {
                ShipVariantAPI mn = now.getModuleVariant(slot), mp = s.plan.getModuleVariant(slot);
                if (mn != null && mp != null && (mn.getNumFluxVents() != mp.getNumFluxVents() || mn.getNumFluxCapacitors() != mp.getNumFluxCapacitors())) c.flux = 1;
            }
            storyPoints += c.smods.size();
            if (c.any()) changedShips++;
            // in-space refit: the game compares the ship's saved variant with the new one (the shown ship's unsaved
            // screen changes included: saving charges them too)
            if (fieldRefit && c.any() && !s.locked) {
                c.cr = crLoss(s.member, s.member.getVariant(), s.plan);
                if (c.cr >= 0.005f) { crShips++; crMax = Math.max(crMax, c.cr); }
            }
            changes.add(c);
            countFitted(s.plan, need);
            countFitted(now, have);
            for (Object o : s.used) {
                if (JAOShops.shopOf(o) == null) continue;
                String key = (o instanceof AvailableFighter ? "f:" : "w:") + (o instanceof AvailableFighter ? ((AvailableFighter) o).getId() : ((AvailableWeapon) o).getId());
                shopUnits.merge(key, 1, Integer::sum);
            }
        }
        // 0.5.75: d-mod repairs come last, from what the plan left of the credit limit. 0.5.78: the ship on the refit screen is
        // repaired too, on the screen's own copy (as the screen's repair does), which the screen saves at the end of Apply
        if (repairOn) {
            try {
                double left = fleet.creditsLeft == Double.MAX_VALUE ? creditLimit : Math.min(creditLimit, fleet.creditsLeft);
                repairs = JAODmods.plan(fleet.ships, JAODmods.partsHave(cargo, storage != null ? market : null), left, JAODmods.restoreHere(market), null,
                        (s, v) -> fleet.scoreVariant(s.member, v));
                for (JAODmods.Repair r : repairs) {
                    if (r.how == JAODmods.WITH_PARTS) repairParts += r.parts; else repairCredits += r.credits;
                }
                List<String> lines = new ArrayList<>();
                for (JAODmods.Repair r : repairs) lines.add(r.ship.name + ": " + r.describe());
                Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: d-mod repairs planned (" + JAODmods.partsHave(cargo, storage != null ? market : null) + " parts here, "
                        + Math.round(left) + " credits left, restoration " + (JAODmods.restoreHere(market) ? "open" : "closed") + ", picked ships " + picks().size() + "): "
                        + (lines.isEmpty() ? "none" : String.join("; ", lines)));
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: couldn't plan the d-mod repairs", t);
                repairs = new ArrayList<>();
            }
        }
        // what has to come out of storage: the plan's items beyond what the ships and cargo already hold
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            String id = e.getKey();
            int inCargo = id.startsWith("f:") ? cargo.getNumFighters(id.substring(2)) : cargo.getNumWeapons(id.substring(2));
            fromStorage += Math.max(0, e.getValue() - have.getOrDefault(id, 0) - inCargo - shopUnits.getOrDefault(id, 0));
        }
    }

    private static int diffWeapons(ShipVariantAPI a, ShipVariantAPI b) {
        int n = 0;
        for (WeaponSlotAPI slot : b.getHullSpec().getAllWeaponSlotsCopy()) {
            if (!JAOOptimizer.mountable(slot)) continue;
            if (!same(a.getWeaponId(slot.getId()), b.getWeaponId(slot.getId()))) n++;
        }
        return n;
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }

    /** Weapons ("w:id") and LPCs ("f:id") a variant and its modules carry in choosable slots and bays. */
    private static void countFitted(ShipVariantAPI v, Map<String, Integer> out) {
        Map<String, Integer> w = new TreeMap<>(), f = new TreeMap<>();
        JAOFleetPlan.addMountedWithModules(v, w, f);
        for (Map.Entry<String, Integer> e : w.entrySet()) out.merge("w:" + e.getKey(), e.getValue(), Integer::sum);
        for (Map.Entry<String, Integer> e : f.entrySet()) out.merge("f:" + e.getKey(), e.getValue(), Integer::sum);
    }

    /**
     * The variant to change for this ship: the refit screen's own copy when it's the ship shown there, otherwise the
     * ship's; a shared stock variant is copied first (make), so no other ship's goal or stock fit changes with it.
     */
    /** Ships picked for d-mod repairs (member ids). */
    private static java.util.Set<String> picks() { return JAOFleetOptions.repairPicks(); }

    ShipVariantAPI live(FleetMemberAPI m, boolean make) {
        if (refit != null && JAORefl.call(refit, "getMember") == m) {
            Object v = JAORefl.call(JAORefl.call(refit, "getShipDisplay"), "getCurrentVariant");
            if (v instanceof ShipVariantAPI) {
                // the screen's copy can't be swapped for a fresh one; a shared stock variant there is left alone
                if (make && ((ShipVariantAPI) v).isStockVariant()) throw new IllegalStateException("the ship on the refit screen still uses a stock variant; re-fit it once by hand");
                return (ShipVariantAPI) v;
            }
        }
        ShipVariantAPI v = m.getVariant();
        if (make && (v.isStockVariant() || v.getSource() != VariantSource.REFIT)) {
            v = v.clone();
            v.setSource(VariantSource.REFIT);
            m.setVariant(v, false, true);
        }
        return v;
    }

    // ------------------------------------------------------------------ apply

    /** Refits every planned ship to its plan. Returns what happened. */
    String apply() {
        if (applied) return "Already applied";
        applied = true;
        Map<String, Integer> off = new TreeMap<>(), used = new TreeMap<>();
        int missing = 0, built = 0, ships = 0;
        List<JAOFleetPlan.ShipPlan> todo = new ArrayList<>();
        for (Change c : changes) if (c.any() && !c.ship.locked) todo.add(c.ship);
        // away from a market: each ship's variant as it was, so the game can charge the refit's CR once it's done (the
        // ship on the refit screen pays through the screen's own save)
        Map<FleetMemberAPI, ShipVariantAPI> was = new IdentityHashMap<>();
        if (fieldRefit) for (JAOFleetPlan.ShipPlan s : todo) if (!shown(s.member)) was.put(s.member, s.member.getVariant().clone());
        int crCharged = 0;
        float crTaken = 0f;
        // 0. officers move first (the plan is for the ships under their new captains)
        int moved = 0;
        if (officers != null) {
            try { moved = officers.apply(); } catch (Throwable t) { Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: Optimize fleet couldn't move the officers", t); }
        }
        // 1. everything off the planned ships into cargo (each item as it comes off, so nothing is lost if one fails)
        for (JAOFleetPlan.ShipPlan s : todo) {
            try {
                ShipVariantAPI v = live(s.member, true);
                strip(v, off);
                for (String slot : new ArrayList<>(v.getStationModules().keySet())) {
                    // 0.5.102: no_autofit modules keep what they carry (armaa Spriggan Weapons Pack: 0 OP, its missile is the hull's own)
                    if (JAOOptimizer.noAutofit(v.getModuleVariant(slot))) continue;
                    ShipVariantAPI mv = module(v, slot);
                    if (mv != null) strip(mv, off);
                }
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: Optimize fleet couldn't strip " + s.name, t);
            }
        }
        // 1b. the stored ships' weapons and LPCs into cargo too ("Strip stored ships")
        int strippedStored = 0;
        Set<String> stillStored = new java.util.HashSet<>();
        try {
            if (storage != null && storage.getMothballedShips() != null)
                for (FleetMemberAPI m : storage.getMothballedShips().getMembersListCopy()) stillStored.add(m.getId());
        } catch (Throwable ignored) { }
        for (FleetMemberAPI m : stored) {
            if (!stillStored.contains(m.getId())) continue;
            try {
                ShipVariantAPI v = live(m, true);
                strip(v, off);
                for (String slot : new ArrayList<>(v.getStationModules().keySet())) {
                    // 0.5.102: no_autofit modules keep what they carry (armaa Spriggan Weapons Pack: 0 OP, its missile is the hull's own)
                    if (JAOOptimizer.noAutofit(v.getModuleVariant(slot))) continue;
                    ShipVariantAPI mv = module(v, slot);
                    if (mv != null) strip(mv, off);
                }
                m.updateStats();
                strippedStored++;
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: Optimize fleet couldn't strip the stored ship " + m.getShipName(), t);
            }
        }
        // 1c. what the plans buy (0.5.57): biggest ships first, at the planned shop's price, into fleet cargo
        int boughtNow = 0;
        float spentNow = 0f;
        for (JAOFleetPlan.ShipPlan s : todo) {
            Map<Object, Integer> wants = new java.util.LinkedHashMap<>();
            for (Object o : s.used) if (JAOShops.shopOf(o) != null) wants.merge(o, 1, Integer::sum);
            for (Map.Entry<Object, Integer> e : wants.entrySet()) {
                try {
                    float c0 = cargo.getCredits().get();
                    int k = JAOShops.buy(e.getKey(), e.getValue(), cargo, ui);
                    boughtNow += k;
                    spentNow += c0 - cargo.getCredits().get();
                    if (k < e.getValue()) Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: Optimize fleet bought " + k + " of "
                            + e.getValue() + " planned for " + s.name + " (the shop has fewer now, or the credits ran out)");
                } catch (Throwable t) {
                    Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: Optimize fleet couldn't buy for " + s.name, t);
                }
            }
        }
        // 2. each ship takes its plan
        MutableCharacterStatsAPI ps = Global.getSector().getPlayerStats();
        List<FleetMemberAPI> done = new ArrayList<>();
        for (JAOFleetPlan.ShipPlan s : todo) {
            try {
                // 0.5.101: listed first, so the hullmod item bookkeeping below also runs when a later step throws
                done.add(s.member);
                ShipVariantAPI v = live(s.member, true);
                missing += fit(v, s.plan, used, s.scripted);
                for (String slot : new ArrayList<>(v.getStationModules().keySet())) {
                    if (JAOOptimizer.noAutofit(v.getModuleVariant(slot))) continue;
                    ShipVariantAPI mv = module(v, slot), mp = s.plan.getModuleVariant(slot);
                    if (mv == null || mp == null) continue;
                    missing += fit(mv, mp, used, java.util.Collections.emptySet());
                    // 0.5.94: Shrouded hullmods off: the module's Shrouded s-mods the plan took off (Change s-mods), then the
                    // ordinary ones
                    if (!JAOSettings.shroudedHullmods) {
                        moduleShroudedSmodsOff(mv, mp, s, ps);
                        for (String id : new ArrayList<>(mv.getNonBuiltInHullmods())) {
                            if (JAOOptimizer.shrouded(id) && !mv.getPermaMods().contains(id) && !mp.hasHullMod(id)) mv.removeMod(id);
                        }
                    }
                    // 0.5.85: the module's planned s-mods, one story point each
                    built += moduleSmods(mv, mp, s, ps);
                    // 0.5.60: the module plan spends its flux OP on weapons too (vents and capacitors left as they were put it over its OP)
                    mv.setNumFluxVents(mp.getNumFluxVents());
                    mv.setNumFluxCapacitors(mp.getNumFluxCapacitors());
                }
                int builtHere = hullmods(v, s, ps);
                built += builtHere;
                v.setNumFluxVents(s.plan.getNumFluxVents());
                v.setNumFluxCapacitors(s.plan.getNumFluxCapacitors());
                settle(v, s.member);
                v.setVariantDisplayName(s.plan.getDisplayName());
                v.setSource(VariantSource.REFIT);
                if (s.smods != null && s.smods.waiting) JAOSMods.waitedDays(s.member.getId(), true);
                // 0.5.60: s-mods built, so the wait is over (an old timer read as expired the next time a slot opened)
                else if (builtHere > 0) JAOSMods.stopWaiting(s.member.getId());
                List<String> before = new ArrayList<>(v.getHullMods());
                s.member.updateStats();
                // the refresh ran the ship's hullmod scripts: bays they fill (Nimbus drones, 10 OP each on a Maquech) or
                // costs they change can put it over its OP, and Trinity Worlds then adds Equipment Malfunction (-100% CR);
                // flux comes off until it fits and the scripts look again
                if (settle(v, s.member)) s.member.updateStats();
                List<String> added = new ArrayList<>();
                for (String id : v.getHullMods()) if (!before.contains(id)) added.add(id);
                if (!added.isEmpty()) Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: Optimize fleet: " + s.name
                        + "'s hullmod scripts added " + added);
                // the refit screen's CR cost for an in-space refit, through the same call: the old variant goes back for a
                // moment so setVariant(new, withRefit) sees the difference
                ShipVariantAPI old = was.get(s.member);
                if (old != null) {
                    float cr0 = s.member.getRepairTracker().getCR();
                    ShipVariantAPI now = s.member.getVariant();
                    s.member.setVariant(old, false, false);
                    try {
                        s.member.setVariant(now, true, true);
                    } catch (Throwable t) {
                        // 0.5.101: never leave the ship on its old variant (its old gear is already in cargo)
                        s.member.setVariant(now, false, true);
                        throw t;
                    }
                    float lost = cr0 - s.member.getRepairTracker().getCR();
                    if (lost > 0.0005f) { crCharged++; crTaken = Math.max(crTaken, lost); }
                    Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: Optimize fleet: " + s.name + " lost "
                            + Math.round(lost * 100f) + "% CR to the in-space refit");
                }
                ships++;
                // 0.5.60: its hullmod items are taken now, so the next ship's check sees what's left (the refit screen's
                // ship is reported after the screen saves it, below)
                if (!shown(s.member)) {
                    try { com.fs.starfarer.api.impl.campaign.HullModItemManager.getInstance().reportFleetMemberVariantSaved(s.member, storage != null ? market : null); }
                    catch (Throwable t) { Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: hullmod item bookkeeping failed for " + s.name, t); }
                }
                Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: Optimize fleet: " + s.name + " (" + v.getHullSpec().getHullId() + "): "
                        + emptyMounts(v) + " empty mounts, " + emptyBays(v) + " empty bays, wings " + fittedWings(v) + ", hullmods "
                        + v.getNonBuiltInHullmods() + ", s-mods " + v.getSMods() + ", vents " + v.getNumFluxVents() + ", caps "
                        + v.getNumFluxCapacitors() + ", OP " + v.computeOPCost(Global.getSector().getPlayerStats()) + "/"
                        + v.getHullSpec().getOrdnancePoints(Global.getSector().getPlayerStats()));
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: Optimize fleet couldn't finish " + s.name, t);
            }
        }
        // 2b. ESR augments move to their ships (after the refit, so nothing the refit does touches ESR's hullmod)
        int augmentsPlaced = 0;
        if (augments != null) {
            try { augmentsPlaced = augments.apply(); } catch (Throwable t) { Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: Optimize fleet couldn't move the augments", t); }
        }
        // 2c. d-mod repairs (0.5.75): parts first from cargo then storage here, or credits for a restoration
        int repaired = 0, partsUsed = 0, repairSpent = 0;
        for (JAODmods.Repair r : repairs) {
            try {
                FleetMemberAPI m = r.ship.member;
                boolean onScreen = shown(m);
                // 0.5.101: a ship off the screen gets its own copy first (it can still hold the shared stock variant)
                ShipVariantAPI v = live(m, !onScreen);
                if (v == null) continue;
                List<String> d = new ArrayList<>(r.dmods);
                d.retainAll(JAOOptimizer.removableDmods(v));
                if (d.isEmpty()) continue;
                if (r.how == JAODmods.WITH_PARTS) {
                    int need = d.size() * JAODmods.partsPerDmod(v.getHullSpec());
                    // 0.5.101: storage only when it is a source (Use storage here on, access)
                    if (JAODmods.partsHave(cargo, storage != null ? market : null) < need) continue;
                    JAODmods.takeParts(cargo, storage != null ? market : null, need);
                    JAODmods.repair(v, d);
                    if (!onScreen) m.setVariant(v, false, true);
                    com.fs.starfarer.api.campaign.listeners.ListenerUtil.reportDModsRepaired(m, v, need);
                    partsUsed += need;
                } else {
                    int cost = JAODmods.restoreCost(v.getHullSpec(), d.size());
                    if (cargo.getCredits().get() < cost) continue;
                    cargo.getCredits().subtract(cost);
                    JAODmods.repair(v, d);
                    if (!onScreen) m.setVariant(v, false, true);
                    repairSpent += cost;
                }
                repaired++;
                Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: Optimize fleet: removed " + d + " from " + r.ship.name
                        + (r.how == JAODmods.WITH_PARTS ? " with Domain-grade Parts" : " with a restoration for credits"));
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: Optimize fleet couldn't remove the d-mods on " + r.ship.name, t);
            }
        }
        // 3. what came off and wasn't used again goes to storage
        int stored = 0;
        if (storage != null) {
            for (Map.Entry<String, Integer> e : off.entrySet()) {
                String key = e.getKey(), id = key.substring(2);
                boolean wing = key.startsWith("f:");
                if (id.trim().isEmpty()) continue;
                int spare = Math.min(e.getValue() - used.getOrDefault(key, 0), wing ? cargo.getNumFighters(id) : cargo.getNumWeapons(id));
                if (spare <= 0) continue;
                if (wing) { cargo.removeFighters(id, spare); storage.addFighters(id, spare); }
                else { cargo.removeWeapons(id, spare); storage.addWeapons(id, spare); }
                stored += spare;
            }
        }
        // 4. reverse engineering (0.5.81): one of each set-aside item into the Reverse Engineering Storage, from storage here
        // then cargo; items in it the hub has already done (never picked again) come back
        int sentRe = 0, tookBack = 0;
        if (reverseHere != null) {
            try {
                CargoAPI re = reverseHere.getCargo();
                List<String> missed = new ArrayList<>();
                for (String id : reverseW) { if (JAOReverse.send(id, false, storage, cargo, re)) sentRe++; else missed.add(id); }
                for (String id : reverseF) { if (JAOReverse.send(id, true, storage, cargo, re)) sentRe++; else missed.add(id); }
                tookBack = JAOReverse.takeBack(re, storage != null ? storage : cargo);
                Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: Optimize fleet: " + sentRe + " items to the Reverse Engineering Storage"
                        + (missed.isEmpty() ? "" : ", not found in cargo or storage: " + missed) + (tookBack > 0 ? ", " + tookBack + " already done taken back" : ""));
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: Optimize fleet couldn't fill the Reverse Engineering Storage", t);
            }
        }
        try { Global.getSector().getPlayerFleet().getFleetData().setSyncNeeded(); } catch (Throwable ignored) { }
        try { Global.getSector().getPlayerFleet().getFleetData().syncIfNeeded(); } catch (Throwable ignored) { }
        if (refit != null) {
            JAORefl.call(refit, "syncWithCurrentVariant");
            JAORefl.call(refit, "saveCurrentVariant");
        }
        // the screen's save can write over ESR's hullmod on the shown ship: ESR syncs the changed ships again
        if (augments != null) {
            try { augments.resync(); } catch (Throwable ignored) { }
        }
        purgeBlankLpcs();
        JAOCompat.purgeLpcs();
        // hullmods that need an item (the refit screen's own bookkeeping, as when it saves a ship): take what new ones
        // need, give back what removed ones held; it works from the difference, so a second report changes nothing
        for (FleetMemberAPI m : done) {
            try {
                com.fs.starfarer.api.impl.campaign.HullModItemManager.getInstance().reportFleetMemberVariantSaved(m, storage != null ? market : null);
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: hullmod item bookkeeping failed for " + m.getShipName(), t);
            }
        }
        String msg = "Refitted " + ships + " ships" + (built > 0 ? ", built in " + built + " s-mod" + (built > 1 ? "s" : "") : "")
                + (boughtNow > 0 ? ", bought " + boughtNow + " weapons and LPCs for " + Misc.getDGSCredits(spentNow) : "")
                + (changedSmods > 0 ? ", changed " + changedSmods + " s-mod" + (changedSmods > 1 ? "s" : "") + " for " + Misc.getDGSCredits(changeSpent) : "")
                + (moved > 0 ? ", moved " + moved + " officer" + (moved > 1 ? "s" : "") : "")
                + (augmentsPlaced > 0 ? ", installed " + augmentsPlaced + " augment" + (augmentsPlaced > 1 ? "s" : "") : "")
                + (repaired > 0 ? ", removed the d-mods on " + repaired + " ship" + (repaired > 1 ? "s" : "") + " (" + (partsUsed > 0 ? partsUsed + " Domain-grade Parts" : "")
                        + (partsUsed > 0 && repairSpent > 0 ? ", " : "") + (repairSpent > 0 ? Misc.getDGSCredits(repairSpent) : "") + ")" : "")
                + (strippedStored > 0 ? ", stripped " + strippedStored + " stored ship" + (strippedStored > 1 ? "s" : "") : "")
                + (sentRe > 0 ? ", put " + sentRe + " item" + (sentRe > 1 ? "s" : "") + " in the Reverse Engineering Storage" : "")
                + (tookBack > 0 ? ", took " + tookBack + " already reverse-engineered item" + (tookBack > 1 ? "s" : "") + " out of it" : "")
                + (crCharged > 0 ? "; " + crCharged + " lost CR to the in-space refit (up to " + Math.round(crTaken * 100f) + "%)" : "")
                + (stored > 0 ? "; " + stored + " weapons and LPCs that came off went to storage at " + market.getName() : "")
                + (missing > 0 ? "; " + missing + " items weren't there any more, so those mounts are empty" : "");
        Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: Optimize fleet: " + msg + "; fleet " + before.describe() + " -> " + fleet.mix().describe());
        return msg;
    }

    static int emptyMounts(ShipVariantAPI v) {
        int n = 0;
        Set<String> skip = JAORatings.skipSlots(v.getHullSpec().getHullId(), v.getHullSpec().getBaseHullId());
        for (WeaponSlotAPI s : v.getHullSpec().getAllWeaponSlotsCopy()) if (JAOOptimizer.mountable(s) && !skip.contains(s.getId()) && v.getWeaponId(s.getId()) == null) n++;
        return n;
    }

    static int emptyBays(ShipVariantAPI v) {
        int n = 0, builtIn = v.getHullSpec().getBuiltInWings() == null ? 0 : v.getHullSpec().getBuiltInWings().size();
        for (int i = builtIn; i < Global.getSettings().computeNumFighterBays(v); i++) if (wingAt(v, i) == null) n++;
        return n;
    }

    static List<String> fittedWings(ShipVariantAPI v) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Global.getSettings().computeNumFighterBays(v); i++) { String id = wingAt(v, i); if (id != null) out.add(id); }
        return out;
    }

    /** A module variant safe to change: a shared stock one is copied into the ship first. */
    private static ShipVariantAPI module(ShipVariantAPI v, String slot) {
        ShipVariantAPI mv = v.getModuleVariant(slot);
        if (mv == null) return null;
        // 0.5.79: also the game's shared stock variant when another mod marked it as a refit
        boolean shared = JAOOptimizer.sharedStock(mv);
        if (shared) JAOOptimizer.restoreStock(mv);
        if (mv.isStockVariant() || mv.getSource() != VariantSource.REFIT || shared) {
            mv = mv.clone();
            mv.setSource(VariantSource.REFIT);
            mv.setHullVariantId(mv.getHullVariantId() + "_" + slot.replace(' ', '_'));
            v.setModuleVariant(slot, mv);
        }
        return mv;
    }

    /**
     * Takes the choosable weapons and non-built-in wings off into fleet cargo (counted into off: "w:id" / "f:id"). Wings
     * only scripts place stay in their bays (JAOCompat.boundWing): their mods delete them from cargo, and the plan keeps
     * them where they are.
     */
    private void strip(ShipVariantAPI v, Map<String, Integer> off) {
        Set<String> skip = JAORatings.skipSlots(v.getHullSpec().getHullId(), v.getHullSpec().getBaseHullId());
        for (String slot : new ArrayList<>(v.getFittedWeaponSlots())) {
            WeaponSlotAPI s = v.getSlot(slot);
            String id = v.getWeaponId(slot);
            if (s == null || id == null || id.isEmpty() || !JAOOptimizer.mountable(s) || skip.contains(slot)) continue;
            v.clearSlot(slot);
            cargo.addWeapons(id, 1);
            off.merge("w:" + id, 1, Integer::sum);
        }
        int builtIn = v.getHullSpec().getBuiltInWings() == null ? 0 : v.getHullSpec().getBuiltInWings().size();
        int bays = Global.getSettings().computeNumFighterBays(v);
        for (int i = builtIn; i < bays; i++) {
            String id = wingAt(v, i);
            if (id == null || JAOCompat.boundWing(id)) continue;
            v.setWingId(i, null);
            cargo.addFighters(id, 1);
            off.merge("f:" + id, 1, Integer::sum);
        }
    }

    /**
     * The wing in bay i, or null. The game marks an empty bay with "" in getWings() (setWingId(i, null) stores ""), so
     * the list itself can't be read as id-or-null: 0.5.0 took every empty bay for a full one and skipped all planned
     * wings, and stripped "" into cargo as a blank LPC.
     */
    static String wingAt(ShipVariantAPI v, int i) {
        String id;
        try { id = v.getWingId(i); } catch (Throwable t) { return null; }
        return id == null || id.isEmpty() ? null : id;
    }

    /**
     * Removes LPC stacks with a blank id from fleet cargo and every storage: 0.5.0's Optimize fleet put one there for
     * every empty fighter bay it stripped (see wingAt). Run on load and after each Optimize fleet. Returns how many.
     */
    static int purgeBlankLpcs() {
        int n = 0;
        try {
            n += purge(Global.getSector().getPlayerFleet().getCargo());
            for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
                CargoAPI c = Misc.getStorageCargo(m);
                if (c != null) n += purge(c);
            }
        } catch (Throwable t) {
            Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: couldn't check for blank LPCs", t);
        }
        if (n > 0) Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: removed " + n + " blank LPCs (left by 0.5.0's Optimize fleet)");
        return n;
    }

    private static int purge(CargoAPI c) {
        int n = 0;
        for (CargoAPI.CargoItemQuantity<String> q : c.getFighters()) {
            if (q.getItem() != null && !q.getItem().trim().isEmpty()) continue;
            n += q.getCount();
            c.removeFighters(q.getItem(), q.getCount());
        }
        return n;
    }

    /**
     * Puts the plan's weapons and wings on v from cargo, then storage. Bays the ship's scripts fill (scripted) are left
     * to them. Returns how many weren't found.
     */
    private int fit(ShipVariantAPI v, ShipVariantAPI plan, Map<String, Integer> used, Set<Integer> scripted) {
        int missing = 0;
        Set<String> skip = JAORatings.skipSlots(v.getHullSpec().getHullId(), v.getHullSpec().getBaseHullId());
        for (WeaponSlotAPI slot : v.getHullSpec().getAllWeaponSlotsCopy()) {
            if (!JAOOptimizer.mountable(slot) || skip.contains(slot.getId())) continue;
            String id = plan.getWeaponId(slot.getId());
            if (id == null || v.getWeaponId(slot.getId()) != null) continue;
            if (cargo.getNumWeapons(id) > 0) cargo.removeWeapons(id, 1);
            else if (storage != null && storage.getNumWeapons(id) > 0) storage.removeWeapons(id, 1);
            else { missing++; continue; }
            v.addWeapon(slot.getId(), id);
            used.merge("w:" + id, 1, Integer::sum);
        }
        int builtIn = v.getHullSpec().getBuiltInWings() == null ? 0 : v.getHullSpec().getBuiltInWings().size();
        int bays = Global.getSettings().computeNumFighterBays(plan);
        for (int i = builtIn; i < bays; i++) {
            String id = wingAt(plan, i);
            if (id == null || wingAt(v, i) != null || scripted.contains(i)) continue;
            if (cargo.getNumFighters(id) > 0) cargo.removeFighters(id, 1);
            else if (storage != null && storage.getNumFighters(id) > 0) storage.removeFighters(id, 1);
            else { missing++; continue; }
            v.setWingId(i, id);
            used.merge("f:" + id, 1, Integer::sum);
        }
        v.getWeaponGroups().clear();
        v.setMayAutoAssignWeapons(true);
        v.autoGenerateWeaponGroups();
        JAOGroups.arrange(v);
        return missing;
    }

    /**
     * The plan's hullmods: ordinary ones off and on (s-mods and other permanent ones stay), then the new s-mods built
     * in, one story point each, the way the refit screen's build-in does it. Returns how many were built in.
     */
    private int hullmods(ShipVariantAPI v, JAOFleetPlan.ShipPlan s, MutableCharacterStatsAPI ps) {
        ShipVariantAPI plan = s.plan;
        // 0.5.57: the game's Change s-mods first; if it can't be paid nothing changes, and the planned credit s-mods
        // (never in getNonBuiltInHullmods) aren't added
        boolean changeFailed = false;
        if (!s.smodsRemoved.isEmpty() || !s.smodsBought.isEmpty()) changeFailed = !changeSmods(v, s, ps);
        for (String id : new ArrayList<>(v.getNonBuiltInHullmods())) {
            if (v.getPermaMods().contains(id) || plan.getNonBuiltInHullmods().contains(id)) continue;
            // hidden hullmods and never_remove rules stay, as in the single fit's strip (AI Tweaks' AI hullmods)
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (spec == null || spec.isHidden() || spec.isHiddenEverywhere() || JAOCompat.neverRemove(id, v.getHullSpec().getHullSize())) continue;
            v.removeMod(id);
        }
        List<String> newS = new ArrayList<>();
        for (String id : plan.getSMods()) if (!v.getSMods().contains(id) && !s.smodsBought.contains(id)) newS.add(id);
        for (String id : plan.getNonBuiltInHullmods()) {
            if (newS.contains(id) || v.hasHullMod(id)) continue;
            // 0.5.60: a hullmod whose item isn't there any more stays off (the game would install it without taking one)
            if (!itemThere(id, s.member, v)) {
                Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: Optimize fleet: no item left for " + id + " on " + s.name + "; left off");
                continue;
            }
            v.addMod(id);
        }
        newS.removeIf(id -> !itemThere(id, s.member, v));
        if (changeFailed) {
            // the s-mods meant to come off are still there: new story point s-mods only up to the free room
            int room = Math.min(Misc.getMaxPermanentMods(s.member, ps), s.civilian ? 99 : JAOOptimizer.sizeCap(v.getHullSize())) - v.getSMods().size();
            while (newS.size() > Math.max(0, room)) newS.remove(newS.size() - 1);
        }
        if (newS.isEmpty()) return 0;
        int had = ps.getStoryPoints();
        if (had - JAOSettings.smodReserve < newS.size()) {
            for (String id : newS) if (!v.hasHullMod(id)) v.addMod(id);
            Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: not enough story points for " + s.name + "'s s-mods; added as ordinary hullmods");
            return 0;
        }
        ShipHullSpecAPI hull = v.getHullSpec();
        float xp = 0f;
        List<String> names = new ArrayList<>();
        for (String id : newS) {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            xp += Misc.getBuildInBonusXP(spec, hull.getHullSize());
            names.add(spec.getDisplayName());
        }
        ps.spendStoryPoints(newS.size(), true, null, true, xp / newS.size(), "Built " + Misc.getAndJoined(names) + " into " + s.name + " (" + hull.getNameWithDesignationWithDashClass() + ")");
        if (ps.getStoryPoints() > had - newS.size()) {
            for (String id : newS) if (!v.hasHullMod(id)) v.addMod(id);
            Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: story points weren't spent for " + s.name + "; " + names + " added as ordinary hullmods");
            return 0;
        }
        for (String id : newS) {
            v.addPermaMod(id, true);
            SModRecord rec = new SModRecord(s.member);
            rec.getSMods().add(id);
            rec.setBonusXPFractionGained(Misc.getBuildInBonusXP(Global.getSettings().getHullModSpec(id), hull.getHullSize()));
            rec.setSPSpent(1);
            PlaythroughLog.getInstance().getSModsInstalled().add(rec);
        }
        return newS.size();
    }

    /**
     * 0.5.85: a module's planned s-mods, built in the way the refit screen builds them into a module: one story point each,
     * within the ship's s-mod limit and above the story point reserve. Returns how many were built in.
     */
    private int moduleSmods(ShipVariantAPI mv, ShipVariantAPI mp, JAOFleetPlan.ShipPlan s, MutableCharacterStatsAPI ps) {
        List<String> newS = new ArrayList<>();
        for (String id : mp.getSMods()) if (!mv.getSMods().contains(id) && !mv.getHullSpec().getBuiltInMods().contains(id)) newS.add(id);
        if (newS.isEmpty()) return 0;
        int room = Misc.getMaxPermanentMods(s.member, ps) - mv.getSMods().size();
        while (newS.size() > Math.max(0, room)) newS.remove(newS.size() - 1);
        int had = ps.getStoryPoints();
        if (newS.isEmpty() || had - JAOSettings.smodReserve < newS.size()) {
            if (!newS.isEmpty()) Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: not enough story points for the s-mods on " + s.name + "'s "
                    + mv.getHullSpec().getHullName() + "; left off");
            return 0;
        }
        ShipHullSpecAPI hull = mv.getHullSpec();
        float xp = 0f;
        List<String> names = new ArrayList<>();
        for (String id : newS) {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            xp += Misc.getBuildInBonusXP(spec, hull.getHullSize());
            names.add(spec.getDisplayName());
        }
        ps.spendStoryPoints(newS.size(), true, null, true, xp / newS.size(), "Built " + Misc.getAndJoined(names) + " into " + s.name + "'s "
                + hull.getHullName() + " (" + hull.getNameWithDesignationWithDashClass() + ")");
        if (ps.getStoryPoints() > had - newS.size()) {
            Global.getLogger(JAOFleetApply.class).error("Auto Outfitting: story points weren't spent for " + s.name + "'s " + hull.getHullName() + "; " + names + " left off");
            return 0;
        }
        for (String id : newS) {
            mv.addPermaMod(id, true);
            SModRecord rec = new SModRecord(s.member);
            rec.getSMods().add(id);
            rec.setBonusXPFractionGained(Misc.getBuildInBonusXP(Global.getSettings().getHullModSpec(id), hull.getHullSize()));
            rec.setSPSpent(1);
            PlaythroughLog.getInstance().getSModsInstalled().add(rec);
        }
        Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: Optimize fleet: built " + newS + " into " + s.name + "'s " + hull.getHullName());
        return newS.size();
    }

    /** The item a hullmod needs is in cargo (or storage here, when used) beyond what this ship's other changes take. */
    private boolean itemThere(String id, FleetMemberAPI m, ShipVariantAPI v) {
        try {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (spec == null || spec.getEffect() == null || spec.getEffect().getRequiredItem() == null) return true;
            return com.fs.starfarer.api.impl.campaign.HullModItemManager.getInstance().isRequiredItemAvailable(id, m, v, storage != null ? market : null);
        } catch (Throwable t) {
            return true;
        }
    }

    /** S-mods changed with credits by this Apply, and what they cost. */
    private int changedSmods;
    private float changeSpent;

    /** 0.5.94: Shrouded s-mods on the module now that its plan doesn't have (the plan took them off). */
    static List<String> shroudedSmodsOff(ShipVariantAPI now, ShipVariantAPI plan) {
        List<String> out = new ArrayList<>();
        for (String id : now.getSMods()) {
            if (!plan.getSMods().contains(id) && !now.getHullSpec().getBuiltInMods().contains(id) && JAOOptimizer.shrouded(id)) out.add(id);
        }
        return out;
    }

    /**
     * 0.5.94: the game's Change s-mods on a module, for the Shrouded s-mods its plan took off: changeSmodsCost for the
     * module's hull size per s-mod, in credits, and the bonus XP their story points didn't earn, as for a ship.
     */
    private void moduleShroudedSmodsOff(ShipVariantAPI mv, ShipVariantAPI mp, JAOFleetPlan.ShipPlan s, MutableCharacterStatsAPI ps) {
        List<String> off = shroudedSmodsOff(mv, mp);
        if (off.isEmpty()) return;
        float cost = off.size() * JAOFleetOptions.changePrice(mv.getHullSpec().getHullSize());
        CargoAPI pc = Global.getSector().getPlayerFleet().getCargo();
        if (!changeHere || !JAOFleetOptions.canChangeSmods(s.member) || cost <= 0f || pc.getCredits().get() < cost) {
            Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: Optimize fleet didn't take " + off + " off " + s.name + "'s "
                    + mv.getHullSpec().getHullName() + " (" + (cost > 0f && pc.getCredits().get() < cost ? "not enough credits" : "Change s-mods isn't open here") + ")");
            return;
        }
        float[] xp = unearnedXp(s.member, off, 0);
        for (String x : off) { mv.removePermaMod(x); mv.removeMod(x); }
        pc.getCredits().subtract(cost);
        changedSmods += off.size();
        changeSpent += cost;
        giveBonusXp(ps, xp, s.name);
        Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: Optimize fleet: " + s.name + "'s " + mv.getHullSpec().getHullName()
                + " s-mods off " + off + " for " + Math.round(cost) + " credits");
    }

    /** Story points spent on these s-mods and the bonus XP fraction they earned, from the game's s-mod records (removed beyond the added count). */
    private static float[] unearnedXp(FleetMemberAPI member, List<String> removed, int added) {
        float sp = 0f, gained = 0f;
        try {
            for (SModRecord rec : PlaythroughLog.getInstance().getSModsInstalled()) {
                if (rec.getMember() == null || rec.getMember() != member) continue;
                for (String x : removed) {
                    if (!rec.getSMods().contains(x)) continue;
                    if (added > 0) added--;
                    else { sp += rec.getSPSpent(); gained += rec.getBonusXPFractionGained() * rec.getSPSpent(); }
                    break;
                }
            }
        } catch (Throwable t) {
            sp = 0f;
        }
        return new float[]{sp, gained};
    }

    /** The bonus XP the removed s-mods' story points didn't earn, as the game gives it back. */
    private static void giveBonusXp(MutableCharacterStatsAPI ps, float[] xp, String name) {
        float sp = xp[0], gained = xp[1];
        if (sp <= 0f || gained >= sp) return;
        try {
            ps.setOnlyAddBonusXPDoNotSpendStoryPoints(true);
            ps.spendStoryPoints(Math.round(sp), true, null, true, 1f - gained / sp, null);
        } catch (Throwable t) {
            Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: couldn't give the bonus XP for " + name + "'s removed s-mods", t);
        } finally {
            ps.setOnlyAddBonusXPDoNotSpendStoryPoints(false);
        }
    }

    /**
     * The game's Change s-mods for one ship (0.5.57, ModPickerDialogV3 in 0.98.5a): the planned s-mods come off (the
     * hullmod stays only if the plan keeps it as an ordinary one) and the planned ones go in as s-mods, no story points;
     * changeSmodsCost per s-mod removed or added is paid in credits. As in the game, s-mods removed beyond those added
     * give back the bonus XP their story points didn't earn when they were built in. False (nothing changed) when the
     * change isn't open here any more or the credits are short.
     */
    private boolean changeSmods(ShipVariantAPI v, JAOFleetPlan.ShipPlan s, MutableCharacterStatsAPI ps) {
        int n = s.smodsRemoved.size() + s.smodsBought.size();
        float cost = n * JAOFleetOptions.changePrice(v.getHullSpec().getHullSize());
        CargoAPI pc = Global.getSector().getPlayerFleet().getCargo();
        if (!changeHere || !JAOFleetOptions.canChangeSmods(s.member) || cost <= 0f || pc.getCredits().get() < cost) {
            Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: Optimize fleet didn't change " + s.name + "'s s-mods ("
                    + (cost > 0f && pc.getCredits().get() < cost ? "not enough credits" : "Change s-mods isn't open here") + ")");
            return false;
        }
        // bonus XP for removed s-mods, as the game works it out before the change
        int added = s.smodsBought.size();
        float sp = 0f, gained = 0f;
        try {
            for (SModRecord rec : PlaythroughLog.getInstance().getSModsInstalled()) {
                if (rec.getMember() == null || rec.getMember() != s.member) continue;
                for (String x : s.smodsRemoved) {
                    if (!rec.getSMods().contains(x)) continue;
                    if (added > 0) added--;
                    else { sp += rec.getSPSpent(); gained += rec.getBonusXPFractionGained() * rec.getSPSpent(); }
                    break;
                }
            }
        } catch (Throwable t) {
            sp = 0f;
        }
        for (String x : s.smodsRemoved) v.removePermaMod(x);
        for (String y : s.smodsBought) v.addPermaMod(y, true);
        pc.getCredits().subtract(cost);
        changedSmods += n;
        changeSpent += cost;
        if (sp > 0f && gained < sp) {
            try {
                ps.setOnlyAddBonusXPDoNotSpendStoryPoints(true);
                ps.spendStoryPoints(Math.round(sp), true, null, true, 1f - gained / sp, null);
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: couldn't give the bonus XP for " + s.name + "'s removed s-mods", t);
            } finally {
                ps.setOnlyAddBonusXPDoNotSpendStoryPoints(false);
            }
        }
        Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: Optimize fleet: " + s.name + " s-mods off " + s.smodsRemoved + ", on "
                + s.smodsBought + " for " + Math.round(cost) + " credits");
        return true;
    }

    /**
     * Over the hull's OP (a weapon missing, a cost that changed, a bay a script filled): capacitors, then vents, come off
     * until it fits. Returns true if anything changed.
     */
    private static boolean settle(ShipVariantAPI v, FleetMemberAPI m) {
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        int op = v.getHullSpec().getOrdnancePoints(stats);
        int over = v.computeOPCost(stats) - op;
        if (over <= 0) return false;
        int c = Math.min(over, v.getNumFluxCapacitors());
        v.setNumFluxCapacitors(v.getNumFluxCapacitors() - c);
        over -= c;
        int ventsCut = Math.min(over, v.getNumFluxVents());
        v.setNumFluxVents(v.getNumFluxVents() - ventsCut);
        // 0.5.102: what flux can't cover is logged (the ship stays over its OP; nothing else is taken off)
        if (over > ventsCut) Global.getLogger(JAOFleetApply.class).warn("Auto Outfitting: Optimize fleet: " + (m == null ? v.getHullVariantId() : m.getShipName())
                + " is still " + (over - ventsCut) + " OP over after removing all vents and capacitors");
        Global.getLogger(JAOFleetApply.class).info("Auto Outfitting: Optimize fleet: " + (m == null ? v.getHullVariantId() : m.getShipName())
                + " was over its OP after refitting (scripts or changed costs); now vents " + v.getNumFluxVents() + ", caps " + v.getNumFluxCapacitors());
        return true;
    }
}
