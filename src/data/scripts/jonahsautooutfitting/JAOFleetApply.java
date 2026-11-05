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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * "Optimize fleet" (user 11/05: "click the button and it just does every single ship with every single s-mod and item
 * and weapon it can possibly equip, one by one down the list"): the fleet plan (JAOFleetPlan) from what can be fitted
 * here, then every planned ship refitted to it in one go.
 *  - Stock: fleet cargo, this market's storage when you're docked where you can use it, and everything on the planned
 *    ships. Nothing is bought.
 *  - Weapons and LPCs come off every planned ship into fleet cargo first; each ship then takes its plan from cargo, then
 *    storage. What came off and isn't used again goes to that storage (user: "let it all sit in storage"), otherwise it
 *    stays in cargo.
 *  - Hullmods change only where the refit rules allow here (spaceport hullmods only at a spaceport).
 *  - New s-mods (setting "Optimize fleet builds s-mods") cost a story point each through the game's own build-in (bonus
 *    XP, playthrough log); the plan never counts on more story points than you have above the reserve.
 *  - The ship on the refit screen is changed through the screen's own copy, then the screen is synced and saved.
 * Civilian ships with the logistics fit are left as they are.
 */
final class JAOFleetApply {
    final JAOFleetPlan fleet;
    final CargoAPI cargo, storage;
    final MarketAPI market;
    /** The refit panel (for the ship it shows), or null. */
    final Object refit;
    final JAOFleetMix before;
    int civilian;

    /** What applying the plan changes on one ship. */
    static final class Change {
        final JAOFleetPlan.ShipPlan ship;
        int weapons, wings, flux;
        final List<String> added = new ArrayList<>(), removed = new ArrayList<>(), smods = new ArrayList<>();

        Change(JAOFleetPlan.ShipPlan ship) { this.ship = ship; }
        boolean any() { return weapons + wings + flux > 0 || !added.isEmpty() || !removed.isEmpty() || !smods.isEmpty(); }
    }

    final List<Change> changes = new ArrayList<>();
    int storyPoints, fromStorage, changedShips;
    boolean summarized, applied;

    JAOFleetApply(Object refit) {
        this.refit = refit;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        cargo = pf.getCargo();
        market = dockedMarket();
        storage = market != null && Misc.playerHasStorageAccess(market) ? Misc.getStorageCargo(market) : null;
        int[] civ = new int[1];
        List<FleetMemberAPI> ships = JAOFleetPlan.combatShips(pf.getFleetData().getMembersListCopy(), civ);
        civilian = civ[0];
        Map<String, Integer> w = new TreeMap<>(), f = new TreeMap<>();
        JAOFleetPlan.addCargo(cargo, w, f);
        if (storage != null) JAOFleetPlan.addCargo(storage, w, f);
        for (FleetMemberAPI m : ships) JAOFleetPlan.addMountedWithModules(live(m, false), w, f);
        List<AvailableWeapon> weapons = new ArrayList<>();
        List<AvailableFighter> fighters = new ArrayList<>();
        JAOFleetPlan.owned(w, f, weapons, fighters);
        before = JAOFleetPlanner.currentMix(ships);
        CampaignUIAPI.CoreUITradeMode mode = market == null ? CampaignUIAPI.CoreUITradeMode.NONE
                : market.getFaction().isHostileTo(Factions.PLAYER) ? CampaignUIAPI.CoreUITradeMode.SNEAK : CampaignUIAPI.CoreUITradeMode.OPEN;
        fleet = new JAOFleetPlan(ships, weapons, fighters, true, JAOSettings.fleetSmods, market, mode);
        fleet.variantOf = m -> live(m, false);
    }

    /** Plans for about budgetMs; true once the plan and its summary are ready. */
    boolean step(long budgetMs) {
        if (!fleet.done && !fleet.step(budgetMs)) return false;
        if (!summarized) summarize();
        return true;
    }

    static MarketAPI dockedMarket() {
        InteractionDialogAPI d = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        return d == null || d.getInteractionTarget() == null ? null : d.getInteractionTarget().getMarket();
    }

    /** What applying changes, ship by ship. */
    private void summarize() {
        summarized = true;
        Map<String, Integer> need = new TreeMap<>(), have = new TreeMap<>();
        for (JAOFleetPlan.ShipPlan s : fleet.ships) {
            if (s.plan == null) continue;
            ShipVariantAPI now = live(s.member, false);
            Change c = new Change(s);
            c.weapons = diffWeapons(now, s.plan);
            for (String slot : now.getStationModules().keySet()) {
                ShipVariantAPI a = now.getModuleVariant(slot), b = s.plan.getModuleVariant(slot);
                if (a != null && b != null) c.weapons += diffWeapons(a, b);
            }
            int bays = Math.max(now.getWings() == null ? 0 : now.getWings().size(), s.plan.getWings() == null ? 0 : s.plan.getWings().size());
            for (int i = 0; i < bays; i++) if (!same(wingAt(now, i), wingAt(s.plan, i))) c.wings++;
            Set<String> a = new LinkedHashSet<>(now.getNonBuiltInHullmods()), b = new LinkedHashSet<>(s.plan.getNonBuiltInHullmods());
            for (String id : b) if (!a.contains(id)) (s.plan.getSMods().contains(id) ? c.smods : c.added).add(JAOSMods.name(id));
            for (String id : s.plan.getSMods()) if (!now.getSMods().contains(id) && a.contains(id)) c.smods.add(JAOSMods.name(id));
            for (String id : a) if (!b.contains(id)) c.removed.add(JAOSMods.name(id));
            if (now.getNumFluxVents() != s.plan.getNumFluxVents() || now.getNumFluxCapacitors() != s.plan.getNumFluxCapacitors()) c.flux = 1;
            storyPoints += c.smods.size();
            if (c.any()) changedShips++;
            changes.add(c);
            countFitted(s.plan, need);
            countFitted(now, have);
        }
        // what has to come out of storage: the plan's items beyond what the ships and cargo already hold
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            String id = e.getKey();
            int inCargo = id.startsWith("f:") ? cargo.getNumFighters(id.substring(2)) : cargo.getNumWeapons(id.substring(2));
            fromStorage += Math.max(0, e.getValue() - have.getOrDefault(id, 0) - inCargo);
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
        for (Change c : changes) if (c.any()) todo.add(c.ship);
        // 1. everything off the planned ships into cargo (each item as it comes off, so nothing is lost if one fails)
        for (JAOFleetPlan.ShipPlan s : todo) {
            try {
                ShipVariantAPI v = live(s.member, true);
                strip(v, off);
                for (String slot : new ArrayList<>(v.getStationModules().keySet())) {
                    ShipVariantAPI mv = module(v, slot);
                    if (mv != null) strip(mv, off);
                }
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Jonah's Auto Outfitting: Optimize fleet couldn't strip " + s.name, t);
            }
        }
        // 2. each ship takes its plan
        MutableCharacterStatsAPI ps = Global.getSector().getPlayerStats();
        List<FleetMemberAPI> done = new ArrayList<>();
        for (JAOFleetPlan.ShipPlan s : todo) {
            try {
                ShipVariantAPI v = live(s.member, true);
                missing += fit(v, s.plan, used);
                for (String slot : new ArrayList<>(v.getStationModules().keySet())) {
                    ShipVariantAPI mv = module(v, slot), mp = s.plan.getModuleVariant(slot);
                    if (mv != null && mp != null) missing += fit(mv, mp, used);
                }
                built += hullmods(v, s, ps);
                v.setNumFluxVents(s.plan.getNumFluxVents());
                v.setNumFluxCapacitors(s.plan.getNumFluxCapacitors());
                settle(v, s.member);
                v.setVariantDisplayName(s.plan.getDisplayName());
                v.setSource(VariantSource.REFIT);
                if (s.smods != null && s.smods.waiting) JAOSMods.waitedDays(s.member.getId(), true);
                s.member.updateStats();
                done.add(s.member);
                ships++;
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).error("Jonah's Auto Outfitting: Optimize fleet couldn't finish " + s.name, t);
            }
        }
        // 3. what came off and wasn't used again goes to storage
        int stored = 0;
        if (storage != null) {
            for (Map.Entry<String, Integer> e : off.entrySet()) {
                String key = e.getKey(), id = key.substring(2);
                boolean wing = key.startsWith("f:");
                int spare = Math.min(e.getValue() - used.getOrDefault(key, 0), wing ? cargo.getNumFighters(id) : cargo.getNumWeapons(id));
                if (spare <= 0) continue;
                if (wing) { cargo.removeFighters(id, spare); storage.addFighters(id, spare); }
                else { cargo.removeWeapons(id, spare); storage.addWeapons(id, spare); }
                stored += spare;
            }
        }
        try { Global.getSector().getPlayerFleet().getFleetData().setSyncNeeded(); } catch (Throwable ignored) { }
        try { Global.getSector().getPlayerFleet().getFleetData().syncIfNeeded(); } catch (Throwable ignored) { }
        if (refit != null) {
            JAORefl.call(refit, "syncWithCurrentVariant");
            JAORefl.call(refit, "saveCurrentVariant");
        }
        // hullmods that need an item (the refit screen's own bookkeeping, as when it saves a ship): take what new ones
        // need, give back what removed ones held; it works from the difference, so a second report changes nothing
        for (FleetMemberAPI m : done) {
            try {
                com.fs.starfarer.api.impl.campaign.HullModItemManager.getInstance().reportFleetMemberVariantSaved(m, market);
            } catch (Throwable t) {
                Global.getLogger(JAOFleetApply.class).warn("Jonah's Auto Outfitting: hullmod item bookkeeping failed for " + m.getShipName(), t);
            }
        }
        String msg = "Refitted " + ships + " ships" + (built > 0 ? ", built in " + built + " s-mod" + (built > 1 ? "s" : "") : "")
                + (stored > 0 ? "; " + stored + " weapons and LPCs that came off went to storage at " + market.getName() : "")
                + (missing > 0 ? "; " + missing + " items weren't there any more, so those mounts are empty" : "");
        Global.getLogger(JAOFleetApply.class).info("Jonah's Auto Outfitting: Optimize fleet: " + msg + "; fleet " + before.describe() + " -> " + fleet.mix().describe());
        return msg;
    }

    /** A module variant safe to change: a shared stock one is copied into the ship first. */
    private static ShipVariantAPI module(ShipVariantAPI v, String slot) {
        ShipVariantAPI mv = v.getModuleVariant(slot);
        if (mv == null) return null;
        if (mv.isStockVariant() || mv.getSource() != VariantSource.REFIT) {
            mv = mv.clone();
            mv.setSource(VariantSource.REFIT);
            mv.setHullVariantId(mv.getHullVariantId() + "_" + slot.replace(' ', '_'));
            v.setModuleVariant(slot, mv);
        }
        return mv;
    }

    /** Takes the choosable weapons and non-built-in wings off into fleet cargo (counted into off: "w:id" / "f:id"). */
    private void strip(ShipVariantAPI v, Map<String, Integer> off) {
        Set<String> skip = JAORatings.skipSlots(v.getHullSpec().getHullId(), v.getHullSpec().getBaseHullId());
        for (String slot : new ArrayList<>(v.getFittedWeaponSlots())) {
            WeaponSlotAPI s = v.getSlot(slot);
            String id = v.getWeaponId(slot);
            if (s == null || id == null || !JAOOptimizer.mountable(s) || skip.contains(slot)) continue;
            v.clearSlot(slot);
            cargo.addWeapons(id, 1);
            off.merge("w:" + id, 1, Integer::sum);
        }
        int builtIn = v.getHullSpec().getBuiltInWings() == null ? 0 : v.getHullSpec().getBuiltInWings().size();
        List<String> wings = new ArrayList<>(v.getWings() == null ? new ArrayList<>() : v.getWings());
        for (int i = builtIn; i < wings.size(); i++) {
            String id = wings.get(i);
            if (id == null) continue;
            v.setWingId(i, null);
            cargo.addFighters(id, 1);
            off.merge("f:" + id, 1, Integer::sum);
        }
    }

    /** The wing in bay i, or null (past the end of the list too). */
    private static String wingAt(ShipVariantAPI v, int i) {
        List<String> w = v.getWings();
        return w == null || i < 0 || i >= w.size() ? null : w.get(i);
    }

    /** Puts the plan's weapons and wings on v from cargo, then storage. Returns how many weren't found. */
    private int fit(ShipVariantAPI v, ShipVariantAPI plan, Map<String, Integer> used) {
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
            if (id == null || wingAt(v, i) != null) continue;
            if (cargo.getNumFighters(id) > 0) cargo.removeFighters(id, 1);
            else if (storage != null && storage.getNumFighters(id) > 0) storage.removeFighters(id, 1);
            else { missing++; continue; }
            v.setWingId(i, id);
            used.merge("f:" + id, 1, Integer::sum);
        }
        v.getWeaponGroups().clear();
        v.setMayAutoAssignWeapons(true);
        v.autoGenerateWeaponGroups();
        return missing;
    }

    /**
     * The plan's hullmods: ordinary ones off and on (s-mods and other permanent ones stay), then the new s-mods built
     * in, one story point each, the way the refit screen's build-in does it. Returns how many were built in.
     */
    private int hullmods(ShipVariantAPI v, JAOFleetPlan.ShipPlan s, MutableCharacterStatsAPI ps) {
        ShipVariantAPI plan = s.plan;
        for (String id : new ArrayList<>(v.getNonBuiltInHullmods())) {
            if (v.getPermaMods().contains(id) || plan.getNonBuiltInHullmods().contains(id)) continue;
            v.removeMod(id);
        }
        List<String> newS = new ArrayList<>();
        for (String id : plan.getSMods()) if (!v.getSMods().contains(id)) newS.add(id);
        for (String id : plan.getNonBuiltInHullmods()) if (!newS.contains(id) && !v.hasHullMod(id)) v.addMod(id);
        if (newS.isEmpty()) return 0;
        int had = ps.getStoryPoints();
        if (had - JAOSettings.smodReserve < newS.size()) {
            for (String id : newS) if (!v.hasHullMod(id)) v.addMod(id);
            Global.getLogger(JAOFleetApply.class).warn("Jonah's Auto Outfitting: not enough story points for " + s.name + "'s s-mods; added as ordinary hullmods");
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
            Global.getLogger(JAOFleetApply.class).error("Jonah's Auto Outfitting: story points weren't spent for " + s.name + "; " + names + " added as ordinary hullmods");
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

    /** Over the hull's OP (a weapon missing, a cost that changed): capacitors, then vents, come off until it fits. */
    private static void settle(ShipVariantAPI v, FleetMemberAPI m) {
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        int op = v.getHullSpec().getOrdnancePoints(stats);
        int over = v.computeOPCost(stats) - op;
        if (over <= 0) return;
        int c = Math.min(over, v.getNumFluxCapacitors());
        v.setNumFluxCapacitors(v.getNumFluxCapacitors() - c);
        over -= c;
        v.setNumFluxVents(Math.max(0, v.getNumFluxVents() - over));
    }
}
