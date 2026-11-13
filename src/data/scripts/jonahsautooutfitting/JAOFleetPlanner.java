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
        int totalCost, budget, credits, planned, skippedCivilian, locked, failed, monthlyCapacity, movedCost;
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
        /** RAT: the Frontiers settlement that takes weapon orders (null: none, or the setting is off). */
        JAOProduction.Settlement settlement;
        /** RAT: the items ordered at the settlement, at its prices, and what they cost in all (paid when ordered). */
        final List<Line> settlementLines = new ArrayList<>();
        int settlementCost;

        boolean anything() { return !lines.isEmpty() || !moved.isEmpty() || !settlementLines.isEmpty(); }

        /** Custom production (AoTD's, or the game's with Heavy Industry or Orbital Works capacity) can take an order. */
        boolean custom() { return aotd || monthlyCapacity > 0; }
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
            plan.settlement = JAOProduction.settlement();

            // owned stock: cargo, storages, what's on the fleet, what's already being built
            Map<String, Integer> ownW = new TreeMap<>(), ownF = new TreeMap<>();
            JAOFleetPlan.addCargo(pf.getCargo(), ownW, ownF);
            java.util.Set<MarketAPI> counted = new java.util.HashSet<>();
            for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
                counted.add(m);
                CargoAPI c = Misc.getStorageCargo(m);
                if (c != null) JAOFleetPlan.addCargo(c, ownW, ownF);
            }
            // RAT: the settlement's storage (kept in its planet's storage, a market outside the economy) and its open orders
            JAOProduction.addSettlementStock(ownW, ownF, counted);
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
                if (!JAOSettings.cheatItems && JAOCompat.cheatOnly(spec)) continue;   // console allblueprints gear
                int cost = JAOProduction.unitCost(prod, false, id);   // 0: the production in use can't build it
                if (cost > 0) weapons.add(new JAOFleetPlan.PlanWeapon(spec, cost, 9999, true));
            }
            for (String id : new java.util.TreeSet<>(faction.getKnownFighters())) {
                FighterWingSpecAPI spec = JAOFleetPlan.wingSpec(id);
                if (spec == null || (!JAOSettings.cheatItems && JAOCompat.cheatOnly(spec))) continue;
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

        /**
         * The blueprint items of each ship's plan, biggest ships first while the budget lasts; the rest is for next time.
         * RAT: a ship's items go to the settlement while its budget lasts (at its prices, paid when ordered), the rest to
         * custom production. Without custom production what the settlement can't take waits for next time; with neither,
         * the list is custom production's, for the panel to show.
         */
        private void finish() {
            Map<String, Line> lines = new LinkedHashMap<>(), atSettlement = new LinkedHashMap<>();
            JAOProduction.Settlement st = plan.settlement;
            List<JAOFleetPlan.ShipPlan> ships = new ArrayList<>();
            List<int[]> sums = new ArrayList<>();   // per ship: cost at the plan's prices, units, cost at the settlement's (-1: can't)
            for (JAOFleetPlan.ShipPlan s : fleet.ships) {
                if (s.plan == null) continue;
                plan.planned++;
                int cost = 0, units = 0, stCost = 0;
                for (Object o : s.used) {
                    if (!blueprint(o)) continue;
                    cost += price(o);
                    units++;
                    int c = st == null ? 0 : st.unitCost(o instanceof JAOFleetPlan.PlanFighter, itemId(o));
                    stCost = c <= 0 || stCost < 0 ? -1 : stCost + c;
                }
                if (units == 0) continue;
                ships.add(s);
                sums.add(new int[]{cost, units, stCost});
            }
            int[] cost = new int[ships.size()], stCost = new int[ships.size()];
            for (int i = 0; i < ships.size(); i++) { cost[i] = sums.get(i)[0]; stCost[i] = sums.get(i)[2]; }
            int[] where = split(cost, stCost, plan.budget, st == null ? -1 : st.budget, plan.custom() || st == null);
            for (int i = 0; i < ships.size(); i++) {
                if (where[i] == SETTLEMENT) {
                    plan.settlementCost += stCost[i];
                    addItems(ships.get(i), atSettlement, st);
                } else if (where[i] == CUSTOM) {
                    plan.totalCost += cost[i];
                    addItems(ships.get(i), lines, null);
                } else {
                    plan.laterShips++;
                    plan.laterItems += sums.get(i)[1];
                    plan.laterCost += cost[i];
                }
            }
            plan.failed = fleet.failed;
            plan.locked = fleet.locked;
            plan.after = fleet.mix();
            plan.lines.addAll(lines.values());
            plan.lines.sort(Comparator.comparingInt((Line l) -> -l.total()).thenComparing(l -> l.name));
            plan.settlementLines.addAll(atSettlement.values());
            plan.settlementLines.sort(Comparator.comparingInt((Line l) -> -l.total()).thenComparing(l -> l.name));
            plan.ms = Math.max(1L, fleet.nanos / 1_000_000L);
            for (JAOFleetPlan.ShipPlan s : fleet.ships) for (String n : s.notes) plan.notes.add(s.name + ": " + n);
            if (plan.aotd) {
                Map<String, Integer> weapons = new LinkedHashMap<>(), wings = new LinkedHashMap<>();
                for (Line l : plan.lines) (l.wing ? wings : weapons).merge(l.id, l.count, Integer::sum);
                for (Line l : plan.moved) (l.wing ? wings : weapons).merge(l.id, l.count, Integer::sum);
                plan.resources = JAOProduction.resources(weapons, wings);
            }
            if (!plan.notes.isEmpty()) Global.getLogger(JAOFleetPlanner.class).info("Auto Outfitting: fleet order notes: " + plan.notes);
            Global.getLogger(JAOFleetPlanner.class).info("Auto Outfitting: fleet order planned in " + plan.ms + " ms ("
                    + (plan.aotd ? "AoTD production" : "custom production") + "): " + plan.lines.size() + " items, " + plan.totalCost
                    + " credits (budget " + plan.budget + ")"
                    + (st == null ? "" : "; settlement " + st.name + ": " + plan.settlementLines.size() + " kinds, " + plan.settlementCost
                    + " credits (its budget " + st.budget + " of " + st.maxBudget + ")") + (plan.laterItems > 0 ? ", " + plan.laterItems + " more items for " + plan.laterShips
                    + " ships next time (" + plan.laterCost + " credits)" : "") + ", " + plan.planned + " ships, " + plan.skippedCivilian + " civilian skipped"
                    + (plan.failed > 0 ? ", " + plan.failed + " failed" : "") + "; fleet " + plan.before.describe() + " -> " + plan.after.describe()
                    + (plan.moved.isEmpty() ? "" : "; " + plan.moved.size() + " kinds waiting in the game's own list to move into AoTD's"));
        }
    }

    static final int LATER = 0, CUSTOM = 1, SETTLEMENT = 2;

    /**
     * Where each ship's items go, in fleet order (biggest first): the settlement while its budget lasts (stCost[i] at its
     * prices; -1 can't be priced there), then custom production when custom is true, else next time (LATER). Both draw on
     * one credit budget. stBudget < 0: no settlement. Pure, for JAOOrderSplitCheck.
     */
    static int[] split(int[] cost, int[] stCost, int budget, int stBudget, boolean custom) {
        int[] out = new int[cost.length];
        int spent = 0, stLeft = Math.max(0, stBudget);
        for (int i = 0; i < cost.length; i++) {
            if (stBudget >= 0 && stCost[i] > 0 && stCost[i] <= stLeft && spent + stCost[i] <= budget) {
                out[i] = SETTLEMENT;
                stLeft -= stCost[i];
                spent += stCost[i];
            } else if (custom && spent + cost[i] <= budget) {
                out[i] = CUSTOM;
                spent += cost[i];
            } else {
                out[i] = LATER;
            }
        }
        return out;
    }

    static boolean blueprint(Object o) {
        return o instanceof JAOFleetPlan.PlanWeapon ? ((JAOFleetPlan.PlanWeapon) o).blueprint
                : o instanceof JAOFleetPlan.PlanFighter && ((JAOFleetPlan.PlanFighter) o).blueprint;
    }

    static int price(Object o) {
        return (int) (o instanceof JAOFleetPlan.PlanFighter ? ((JAOFleetPlan.PlanFighter) o).price : ((JAOFleetPlan.PlanWeapon) o).price);
    }

    static String itemId(Object o) {
        return o instanceof JAOFleetPlan.PlanFighter ? ((JAOFleetPlan.PlanFighter) o).getId() : ((JAOFleetPlan.PlanWeapon) o).getId();
    }

    /** Adds a ship's blueprint items to these lines, priced by the settlement (st) or by the plan (null). */
    static void addItems(JAOFleetPlan.ShipPlan s, Map<String, Line> lines, JAOProduction.Settlement st) {
        for (Object o : s.used) {
            if (!blueprint(o)) continue;
            boolean wing = o instanceof JAOFleetPlan.PlanFighter;
            String id = itemId(o);
            Line l = lines.computeIfAbsent((wing ? "f:" : "w:") + id, k -> new Line(wing, id,
                    wing ? wingName(((JAOFleetPlan.PlanFighter) o).spec) : ((JAOFleetPlan.PlanWeapon) o).spec.getWeaponName(),
                    st == null ? price(o) : st.unitCost(wing, id)));
            l.count++;
            if (!l.ships.contains(s.name)) l.ships.add(s.name);
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
        String settled = null;
        if (!plan.settlementLines.isEmpty()) {
            Map<String, Integer> weapons = new LinkedHashMap<>(), wings = new LinkedHashMap<>();
            for (Line l : plan.settlementLines) (l.wing ? wings : weapons).merge(l.id, l.count, Integer::sum);
            try {
                int[] r = JAOProduction.orderSettlement(weapons, wings);
                settled = r[0] <= 0 ? null : "Settlement: " + r[0] + " items for " + Misc.getDGSCredits(r[1]) + ", paid now, in about "
                        + plan.settlement.days(r[1]) + " days to its storage";
            } catch (Throwable t) {
                Global.getLogger(JAOFleetPlanner.class).error("Auto Outfitting: RAT's settlement refused the fleet order", t);
                settled = "Settlement: refused (see starsector.log), nothing was ordered there";
            }
            if (settled != null) Global.getLogger(JAOFleetPlanner.class).info("Auto Outfitting: " + settled);
        }
        if (plan.lines.isEmpty() && plan.moved.isEmpty()) return settled == null ? "Nothing was ordered" : settled;
        String custom = queueCustom(plan);
        return settled == null ? custom : settled + ". " + custom;
    }

    /** The custom production part of the order (AoTD's or the game's). */
    private static String queueCustom(Plan plan) {
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
                Global.getLogger(JAOFleetPlanner.class).error("Auto Outfitting: AoTD production refused the fleet order", t);
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
                    + ", about " + Misc.getDGSCredits(plan.totalCost + plan.movedCost) + ", each paid when it's finished; " + where + ". See Colonies, Custom production";
            // what AoTD now waits to have delivered for all its orders, and the goods no colony makes (the order stalls on them)
            Map<String, Integer> waits = JAOProduction.outstanding();
            if (!waits.isEmpty()) {
                List<String> none = JAOProduction.unmade(waits);
                Global.getLogger(JAOFleetPlanner.class).info("Auto Outfitting: AoTD production waits for " + JAOProduction.describe(waits)
                        + (none.isEmpty() ? "" : "; no colony makes " + none + (JAOProduction.colonyManagerSupplies()
                        ? " (Auto Colonies' Special project supply plans producers for them)" : "")));
            }
        } else {
            int items = 0;
            for (Line l : plan.lines) {
                if (prod.addItem(l.wing ? ProductionItemType.FIGHTER : ProductionItemType.WEAPON, l.id, l.count)) items += l.count;
            }
            msg = "Queued " + items + " items for about " + Misc.getDGSCredits(plan.totalCost) + ", paid each month as they're built; " + where
                    + ". See Colonies, Custom production";
        }
        Global.getLogger(JAOFleetPlanner.class).info("Auto Outfitting: " + msg);
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
