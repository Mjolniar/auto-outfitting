package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FactionProductionAPI;
import com.fs.starfarer.api.campaign.FactionProductionAPI.ItemInProductionAPI;
import com.fs.starfarer.api.campaign.FactionProductionAPI.ProductionItemType;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableFighter;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableWeapon;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Order fleet weapons" (docs/design.md 11b, 7k): the whole fleet's plan (JAOFleetPlan) with every weapon and fighter
 * blueprint the player knows in unlimited supply, and what that plan takes from blueprints is the order. Owned stock
 * (fleet cargo, every storage, what's on the fleet, what's already being built) costs nothing and is used first.
 *
 * User 11/05: pressing it after re-fitting a few ships ordered different weapons again. The plan no longer depends on
 * what's fitted now (every ship planned from bare, hullmods chosen by the plan, the fleet balance taken from the plan
 * itself), and the budget no longer reshapes the plan: it only decides how much of the order is queued now, biggest
 * ships first; pressing it later orders the rest, since what's on order counts as owned.
 */
final class JAOFleetPlanner {
    static final class Line {
        final boolean wing;
        final String id, name;
        final int unitCost;
        int count;
        final List<String> ships = new ArrayList<>();

        Line(boolean wing, String id, String name, int unitCost) { this.wing = wing; this.id = id; this.name = name; this.unitCost = unitCost; }
        int total() { return unitCost * count; }
    }

    static final class Plan {
        final List<Line> lines = new ArrayList<>();
        /** AoTD only: weapons and LPCs waiting in the game's own production list, which AoTD's screen doesn't show; Queue all moves them into AoTD's order. */
        final List<Line> moved = new ArrayList<>();
        int totalCost, budget, credits, planned, skippedCivilian, failed, monthlyCapacity, movedCost;
        /** Over the budget: ordered next time. */
        int laterShips, laterItems, laterCost;
        /** Ordered through Ashes of the Domain's production rather than the game's own. */
        boolean aotd;
        String gatheringPoint, problem;
        long ms;
        JAOFleetMix before, after;
        /** AoTD: commodity id -> units the order takes from your colonies' exports (JAOProduction.resources). */
        Map<String, Integer> resources = new TreeMap<>();
        /** "Ship: note" for ships whose plan the player should know about (bay rules, penalty hullmods). */
        final List<String> notes = new ArrayList<>();

        boolean anything() { return !lines.isEmpty() || !moved.isEmpty(); }
    }

    /** Builds the order a frame at a time (the plan behind it can take a few seconds on a big fleet). */
    static final class Job {
        final Plan plan = new Plan();
        final JAOFleetPlan fleet;

        Job() {
            CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
            FactionAPI faction = Global.getSector().getPlayerFaction();
            FactionProductionAPI prod = faction.getProduction();
            plan.credits = (int) pf.getCargo().getCredits().get();
            plan.budget = (int) (plan.credits * JAOSettings.productionBudget);
            plan.monthlyCapacity = prod.getMonthlyProductionCapacity();
            plan.gatheringPoint = prod.getGatheringPoint() == null ? null : prod.getGatheringPoint().getName();
            plan.aotd = JAOProduction.aotd();
            plan.problem = JAOProduction.problem;

            // owned stock: cargo, storages, what's on the fleet, what's already being built
            Map<String, Integer> ownW = new TreeMap<>(), ownF = new TreeMap<>();
            JAOFleetPlan.addCargo(pf.getCargo(), ownW, ownF);
            for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
                CargoAPI c = Misc.getStorageCargo(m);
                if (c != null) JAOFleetPlan.addCargo(c, ownW, ownF);
            }
            List<FleetMemberAPI> members = pf.getFleetData().getMembersListCopy();
            for (FleetMemberAPI m : members) if (m.getVariant() != null) JAOFleetPlan.addMountedWithModules(m.getVariant(), ownW, ownF);
            // the game's own production list is still built every month even with AoTD (its CoreScript keeps that), so it
            // counts as incoming either way; with AoTD its weapons and LPCs are also offered for moving into AoTD's order
            for (ItemInProductionAPI item : prod.getCurrent()) {
                boolean wing = item.getType() == ProductionItemType.FIGHTER;
                if (!wing && item.getType() != ProductionItemType.WEAPON) continue;
                (wing ? ownF : ownW).merge(item.getSpecId(), item.getQuantity(), Integer::sum);
                if (!plan.aotd || item.getQuantity() <= 0) continue;
                int cost = JAOProduction.unitCost(prod, wing, item.getSpecId());
                if (cost <= 0) continue;   // AoTD can't build it: it stays where it is
                FighterWingSpecAPI ws = wing ? JAOFleetPlan.wingSpec(item.getSpecId()) : null;
                Line l = new Line(wing, item.getSpecId(), wing ? (ws == null ? item.getSpecId() : wingName(ws)) : weaponName(item.getSpecId()), cost);
                l.count = item.getQuantity();
                l.ships.add("earlier order");
                plan.moved.add(l);
                plan.movedCost += l.total();
            }
            JAOProduction.addIncoming(ownW, ownF);

            List<AvailableWeapon> weapons = new ArrayList<>();
            List<AvailableFighter> fighters = new ArrayList<>();
            JAOFleetPlan.owned(ownW, ownF, weapons, fighters);
            for (String id : new java.util.TreeSet<>(faction.getKnownWeapons())) {
                WeaponSpecAPI spec = JAOFleetPlan.weaponSpec(id);
                if (spec == null || spec.getAIHints().contains(AIHints.SYSTEM) || spec.getType() == WeaponType.DECORATIVE
                        || spec.getType() == WeaponType.BUILT_IN || spec.getType() == WeaponType.SYSTEM) continue;
                int cost = JAOProduction.unitCost(prod, false, id);   // 0: the production in use can't build it
                if (cost > 0) weapons.add(new JAOFleetPlan.PlanWeapon(spec, cost, 9999, true));
            }
            for (String id : new java.util.TreeSet<>(faction.getKnownFighters())) {
                FighterWingSpecAPI spec = JAOFleetPlan.wingSpec(id);
                if (spec == null) continue;
                int cost = JAOProduction.unitCost(prod, true, id);
                if (cost > 0) fighters.add(new JAOFleetPlan.PlanFighter(spec, cost, 9999, true));
            }
            int[] civilian = new int[1];
            List<FleetMemberAPI> ships = JAOFleetPlan.combatShips(members, civilian);
            plan.skippedCivilian = civilian[0];
            plan.before = currentMix(ships);
            fleet = new JAOFleetPlan(ships, weapons, fighters, false, false, null, CampaignUIAPI.CoreUITradeMode.NONE);
        }

        /** Plans for about budgetMs; true once the order is ready. */
        boolean step(long budgetMs) {
            if (!fleet.done && !fleet.step(budgetMs)) return false;
            if (plan.ms == 0L) finish();
            return true;
        }

        String progress() { return fleet.progress(); }

        /** The blueprint items of each ship's plan, biggest ships first while the budget lasts; the rest is for next time. */
        private void finish() {
            Map<String, Line> lines = new LinkedHashMap<>();
            for (JAOFleetPlan.ShipPlan s : fleet.ships) {
                if (s.plan == null) continue;
                int cost = 0, units = 0;
                for (Object o : s.used) {
                    if (o instanceof JAOFleetPlan.PlanWeapon && ((JAOFleetPlan.PlanWeapon) o).blueprint) { cost += (int) ((JAOFleetPlan.PlanWeapon) o).price; units++; }
                    if (o instanceof JAOFleetPlan.PlanFighter && ((JAOFleetPlan.PlanFighter) o).blueprint) { cost += (int) ((JAOFleetPlan.PlanFighter) o).price; units++; }
                }
                plan.planned++;
                if (units == 0) continue;
                if (plan.totalCost + cost > plan.budget) {
                    plan.laterShips++;
                    plan.laterItems += units;
                    plan.laterCost += cost;
                    continue;
                }
                plan.totalCost += cost;
                for (Object o : s.used) {
                    boolean wing = o instanceof JAOFleetPlan.PlanFighter;
                    if (wing ? !((JAOFleetPlan.PlanFighter) o).blueprint : !((JAOFleetPlan.PlanWeapon) o).blueprint) continue;
                    String id = wing ? ((JAOFleetPlan.PlanFighter) o).getId() : ((JAOFleetPlan.PlanWeapon) o).getId();
                    Line l = lines.computeIfAbsent((wing ? "f:" : "w:") + id, k -> new Line(wing, id,
                            wing ? wingName(((JAOFleetPlan.PlanFighter) o).spec) : ((JAOFleetPlan.PlanWeapon) o).spec.getWeaponName(),
                            (int) (wing ? ((JAOFleetPlan.PlanFighter) o).price : ((JAOFleetPlan.PlanWeapon) o).price)));
                    l.count++;
                    if (!l.ships.contains(s.name)) l.ships.add(s.name);
                }
            }
            plan.failed = fleet.failed;
            plan.after = fleet.mix();
            plan.lines.addAll(lines.values());
            plan.lines.sort(Comparator.comparingInt((Line l) -> -l.total()).thenComparing(l -> l.name));
            plan.ms = Math.max(1L, fleet.nanos / 1_000_000L);
            for (JAOFleetPlan.ShipPlan s : fleet.ships) for (String n : s.notes) plan.notes.add(s.name + ": " + n);
            if (plan.aotd) {
                Map<String, Integer> weapons = new LinkedHashMap<>(), wings = new LinkedHashMap<>();
                for (Line l : plan.lines) (l.wing ? wings : weapons).merge(l.id, l.count, Integer::sum);
                for (Line l : plan.moved) (l.wing ? wings : weapons).merge(l.id, l.count, Integer::sum);
                plan.resources = JAOProduction.resources(weapons, wings);
            }
            if (!plan.notes.isEmpty()) Global.getLogger(JAOFleetPlanner.class).info("Jonah's Auto Outfitting: fleet order notes: " + plan.notes);
            Global.getLogger(JAOFleetPlanner.class).info("Jonah's Auto Outfitting: fleet order planned in " + plan.ms + " ms ("
                    + (plan.aotd ? "AoTD production" : "custom production") + "): " + plan.lines.size() + " items, " + plan.totalCost
                    + " credits (budget " + plan.budget + ")" + (plan.laterItems > 0 ? ", " + plan.laterItems + " more items for " + plan.laterShips
                    + " ships next time (" + plan.laterCost + " credits)" : "") + ", " + plan.planned + " ships, " + plan.skippedCivilian + " civilian skipped"
                    + (plan.failed > 0 ? ", " + plan.failed + " failed" : "") + "; fleet " + plan.before.describe() + " -> " + plan.after.describe()
                    + (plan.moved.isEmpty() ? "" : "; " + plan.moved.size() + " kinds waiting in the game's own list to move into AoTD's"));
        }
    }

    /** The fleet's mix as fitted now (for the before/after line). */
    static JAOFleetMix currentMix(List<FleetMemberAPI> ships) {
        JAOFleetMix mix = new JAOFleetMix();
        for (FleetMemberAPI m : ships) mix.addShip(JAOFleetContext.weaponsOf(m.getVariant()));
        return mix;
    }

    /**
     * Places the order with the production in use, setting a gathering point (both use the game's) if there is none.
     * AoTD: one AoTD order like its own Confirm button places, including the weapons and LPCs moved from the game's own
     * list, which are then taken off that list so nothing is built twice. Otherwise the game's custom production list.
     * Returns what happened.
     */
    static String queue(Plan plan) {
        FactionProductionAPI prod = Global.getSector().getPlayerFaction().getProduction();
        if (prod.getGatheringPoint() == null) {
            MarketAPI best = null;
            float cap = 0f;
            for (MarketAPI m : Misc.getPlayerMarkets(true)) {
                float c = prod.getProductionCapacityForMarket(m);
                if (c > cap) { cap = c; best = m; }
            }
            if (best != null) prod.setGatheringPoint(best);
        }
        String where = prod.getGatheringPoint() == null ? "no gathering point set" : "delivered to " + prod.getGatheringPoint().getName();
        String msg;
        if (plan.aotd) {
            Map<String, Integer> weapons = new LinkedHashMap<>(), wings = new LinkedHashMap<>();
            for (Line l : plan.lines) (l.wing ? wings : weapons).merge(l.id, l.count, Integer::sum);
            for (Line l : plan.moved) (l.wing ? wings : weapons).merge(l.id, l.count, Integer::sum);
            int units;
            try {
                units = JAOProduction.orderAotd(weapons, wings);
            } catch (Throwable t) {
                Global.getLogger(JAOFleetPlanner.class).error("Jonah's Auto Outfitting: AoTD production refused the fleet order", t);
                return "Ashes of the Domain's production refused the order (see starsector.log); nothing was ordered";
            }
            int moved = 0;
            if (units > 0) {
                for (Line l : plan.moved) {
                    prod.removeItem(l.wing ? ProductionItemType.FIGHTER : ProductionItemType.WEAPON, l.id, l.count);
                    moved += l.count;
                }
            }
            msg = "Ordered " + units + " items through AoTD production" + (moved > 0 ? " (" + moved + " moved from the game's own list)" : "")
                    + ", about " + Misc.getDGSCredits(plan.totalCost + plan.movedCost) + ", each paid when it's finished; " + where;
            // what AoTD now waits to have delivered for all its orders, and the goods no colony makes (the order stalls on them)
            Map<String, Integer> waits = JAOProduction.outstanding();
            if (!waits.isEmpty()) {
                List<String> none = JAOProduction.unmade(waits);
                Global.getLogger(JAOFleetPlanner.class).info("Jonah's Auto Outfitting: AoTD production waits for " + JAOProduction.describe(waits)
                        + (none.isEmpty() ? "" : "; no colony makes " + none + (JAOProduction.colonyManagerSupplies()
                        ? " (Colony Manager's Special project supply plans producers for them)" : "")));
            }
        } else {
            int items = 0;
            for (Line l : plan.lines) {
                if (prod.addItem(l.wing ? ProductionItemType.FIGHTER : ProductionItemType.WEAPON, l.id, l.count)) items += l.count;
            }
            msg = "Queued " + items + " items for about " + Misc.getDGSCredits(plan.totalCost) + ", paid each month as they're built; " + where;
        }
        Global.getLogger(JAOFleetPlanner.class).info("Jonah's Auto Outfitting: " + msg);
        return msg;
    }

    // ------------------------------------------------------------------ helpers

    private static String weaponName(String id) {
        WeaponSpecAPI spec = JAOFleetPlan.weaponSpec(id);
        return spec == null ? id : spec.getWeaponName();
    }

    static String wingName(FighterWingSpecAPI spec) {
        try { return spec.getVariant().getHullSpec().getHullName() + " wing"; } catch (Throwable t) { return spec.getId(); }
    }
}
