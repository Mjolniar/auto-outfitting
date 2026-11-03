package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoAPI.CargoItemQuantity;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FactionProductionAPI;
import com.fs.starfarer.api.campaign.FactionProductionAPI.ItemInProductionAPI;
import com.fs.starfarer.api.campaign.FactionProductionAPI.ProductionItemType;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.plugins.AutofitPlugin.AutofitPluginDelegate;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableFighter;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableWeapon;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Order best weapons for fleet" (docs/design.md 11b): plans the Optimal fit of every ship in the player fleet as if
 * every weapon and fighter blueprint the player knows were in stock, and turns what those fits would build into a
 * custom production order.
 *
 * Stock is shared across the fleet, biggest ships first: what the player owns (fleet cargo, every storage, the
 * weapons and LPCs already on fleet ships, items already in the production queue) costs nothing; known blueprints
 * are unlimited at the production unit cost. Each ship keeps its hullmods; civilian ships with the logistics fit are
 * skipped. A ship whose blueprint items would push the order past the budget (a share of the player's credits) is
 * planned again with owned stock only, so the order stays affordable and goes to the biggest ships first.
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
        int totalCost, budget, credits, planned, ownedOnly, skippedCivilian, failed, monthlyCapacity, movedCost;
        /** Ordered through Ashes of the Domain's production rather than the game's own. */
        boolean aotd;
        String gatheringPoint, problem;
        long ms;

        boolean anything() { return !lines.isEmpty() || !moved.isEmpty(); }
    }

    // ------------------------------------------------------------------ stock entries and the planning delegate

    static final class PlanWeapon implements AvailableWeapon {
        final WeaponSpecAPI spec;
        final float price;
        final boolean blueprint;
        int qty;

        PlanWeapon(WeaponSpecAPI spec, float price, int qty, boolean blueprint) { this.spec = spec; this.price = price; this.qty = qty; this.blueprint = blueprint; }
        public String getId() { return spec.getWeaponId(); }
        public WeaponSpecAPI getSpec() { return spec; }
        public float getPrice() { return price; }
        public CargoAPI getSource() { return null; }
        public SubmarketAPI getSubmarket() { return null; }
        public int getQuantity() { return qty; }
        public void setQuantity(int quantity) { qty = quantity; }
        public float getOPCost(MutableCharacterStatsAPI stats, MutableShipStatsAPI shipStats) { return spec.getOrdnancePointCost(stats, shipStats); }
    }

    static final class PlanFighter implements AvailableFighter {
        final FighterWingSpecAPI spec;
        final float price;
        final boolean blueprint;
        int qty;

        PlanFighter(FighterWingSpecAPI spec, float price, int qty, boolean blueprint) { this.spec = spec; this.price = price; this.qty = qty; this.blueprint = blueprint; }
        public String getId() { return spec.getId(); }
        public FighterWingSpecAPI getWingSpec() { return spec; }
        public float getPrice() { return price; }
        public CargoAPI getSource() { return null; }
        public SubmarketAPI getSubmarket() { return null; }
        public int getQuantity() { return qty; }
        public void setQuantity(int quantity) { qty = quantity; }
    }

    /** Writes fits into a copy of the variant and remembers which stock entries they used. */
    static final class PlanDelegate implements AutofitPluginDelegate {
        final List<AvailableWeapon> weapons;
        final List<AvailableFighter> fighters;
        final FleetMemberAPI member;
        final List<String> hullmods;
        final List<Object> used = new ArrayList<>();

        PlanDelegate(List<AvailableWeapon> weapons, List<AvailableFighter> fighters, FleetMemberAPI member, List<String> hullmods) {
            this.weapons = weapons; this.fighters = fighters; this.member = member; this.hullmods = hullmods;
        }

        public void fitFighterInSlot(int index, AvailableFighter fighter, ShipVariantAPI variant) {
            fighter.setQuantity(fighter.getQuantity() - 1);
            variant.setWingId(index, fighter.getId());
            used.add(fighter);
        }
        public void clearFighterSlot(int index, ShipVariantAPI variant) { variant.setWingId(index, null); }
        public void fitWeaponInSlot(WeaponSlotAPI slot, AvailableWeapon weapon, ShipVariantAPI variant) {
            weapon.setQuantity(weapon.getQuantity() - 1);
            variant.addWeapon(slot.getId(), weapon.getId());
            used.add(weapon);
        }
        public void clearWeaponSlot(WeaponSlotAPI slot, ShipVariantAPI variant) { variant.clearSlot(slot.getId()); }
        public List<AvailableWeapon> getAvailableWeapons() { return new ArrayList<>(weapons); }
        public List<AvailableFighter> getAvailableFighters() { return new ArrayList<>(fighters); }
        public boolean isPriority(WeaponSpecAPI weapon) { return false; }
        public boolean isPriority(FighterWingSpecAPI wing) { return false; }
        public List<String> getAvailableHullmods() { return new ArrayList<>(hullmods); }
        public void syncUIWithVariant(ShipVariantAPI variant) { }
        public ShipAPI getShip() { return null; }
        public FactionAPI getFaction() { return Global.getSector().getPlayerFaction(); }
        public boolean isAllowSlightRandomization() { return false; }
        public boolean isPlayerCampaignRefit() { return false; }
        public boolean canAddRemoveHullmodInPlayerCampaignRefit(String modId) { return true; }
        public MarketAPI getMarket() { return null; }
        public FleetMemberAPI getFleetMember() { return member; }
    }

    // ------------------------------------------------------------------ planning

    static Plan plan() {
        long t0 = System.nanoTime();
        Plan plan = new Plan();
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        FactionAPI pf = Global.getSector().getPlayerFaction();
        FactionProductionAPI prod = pf.getProduction();
        plan.credits = (int) fleet.getCargo().getCredits().get();
        plan.budget = (int) (plan.credits * JAOSettings.productionBudget);
        plan.monthlyCapacity = prod.getMonthlyProductionCapacity();
        plan.gatheringPoint = prod.getGatheringPoint() == null ? null : prod.getGatheringPoint().getName();
        plan.aotd = JAOProduction.aotd();
        plan.problem = JAOProduction.problem;

        // owned stock: cargo, storages, what's on the fleet, what's already being built
        Map<String, Integer> ownW = new TreeMap<>(), ownF = new TreeMap<>();
        addCargo(fleet.getCargo(), ownW, ownF);
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            CargoAPI c = Misc.getStorageCargo(m);
            if (c != null) addCargo(c, ownW, ownF);
        }
        List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
        for (FleetMemberAPI m : members) {
            ShipVariantAPI v = m.getVariant();
            if (v == null) continue;
            addMounted(v, ownW, ownF);
            for (String slot : v.getStationModules().keySet()) {
                ShipVariantAPI mv = v.getModuleVariant(slot);
                if (mv != null) addMounted(mv, ownW, ownF);
            }
        }
        // the game's own production list is still built every month even with AoTD (its CoreScript keeps that), so it
        // counts as incoming either way; with AoTD its weapons and LPCs are also offered for moving into AoTD's order
        for (ItemInProductionAPI item : prod.getCurrent()) {
            boolean wing = item.getType() == ProductionItemType.FIGHTER;
            if (!wing && item.getType() != ProductionItemType.WEAPON) continue;
            (wing ? ownF : ownW).merge(item.getSpecId(), item.getQuantity(), Integer::sum);
            if (!plan.aotd || item.getQuantity() <= 0) continue;
            int cost = JAOProduction.unitCost(prod, wing, item.getSpecId());
            if (cost <= 0) continue;   // AoTD can't build it: it stays where it is
            FighterWingSpecAPI ws = wing ? wingSpec(item.getSpecId()) : null;
            Line l = new Line(wing, item.getSpecId(), wing ? (ws == null ? item.getSpecId() : wingName(ws)) : weaponName(item.getSpecId()), cost);
            l.count = item.getQuantity();
            l.ships.add("earlier order");
            plan.moved.add(l);
            plan.movedCost += l.total();
        }
        JAOProduction.addIncoming(ownW, ownF);

        List<AvailableWeapon> owned = new ArrayList<>(), blueprints = new ArrayList<>();
        List<AvailableFighter> ownedF = new ArrayList<>(), blueprintsF = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ownW.entrySet()) {
            WeaponSpecAPI spec = weaponSpec(e.getKey());
            if (spec != null && e.getValue() > 0) owned.add(new PlanWeapon(spec, 0f, e.getValue(), false));
        }
        for (Map.Entry<String, Integer> e : ownF.entrySet()) {
            FighterWingSpecAPI spec = wingSpec(e.getKey());
            if (spec != null && e.getValue() > 0) ownedF.add(new PlanFighter(spec, 0f, e.getValue(), false));
        }
        for (String id : new java.util.TreeSet<>(pf.getKnownWeapons())) {
            WeaponSpecAPI spec = weaponSpec(id);
            if (spec == null || spec.getAIHints().contains(AIHints.SYSTEM) || spec.getType() == WeaponType.DECORATIVE
                    || spec.getType() == WeaponType.BUILT_IN || spec.getType() == WeaponType.SYSTEM) continue;
            int cost = JAOProduction.unitCost(prod, false, id);   // 0: the production in use can't build it
            if (cost > 0) blueprints.add(new PlanWeapon(spec, cost, 9999, true));
        }
        for (String id : new java.util.TreeSet<>(pf.getKnownFighters())) {
            FighterWingSpecAPI spec = wingSpec(id);
            if (spec == null) continue;
            int cost = JAOProduction.unitCost(prod, true, id);
            if (cost > 0) blueprintsF.add(new PlanFighter(spec, cost, 9999, true));
        }

        // biggest ships first: they get the scarce owned items and the budget
        List<FleetMemberAPI> order = new ArrayList<>();
        for (FleetMemberAPI m : members) {
            if (m.isFighterWing() || m.getVariant() == null) continue;
            if (JAOSettings.civilianLogistics && JAOOptimizer.civilian(m.getHullSpec())) { plan.skippedCivilian++; continue; }
            order.add(m);
        }
        order.sort(Comparator.comparingInt((FleetMemberAPI m) -> -m.getHullSpec().getHullSize().ordinal())
                .thenComparing(m -> -m.getDeploymentPointsCost()).thenComparing(m -> m.getShipName() == null ? "" : m.getShipName()));

        Map<String, Line> lines = new LinkedHashMap<>();
        for (FleetMemberAPI m : order) {
            try {
                Map<Object, Integer> before = quantities(owned, ownedF, blueprints, blueprintsF);
                List<AvailableWeapon> allW = new ArrayList<>(owned);
                allW.addAll(blueprints);
                List<AvailableFighter> allF = new ArrayList<>(ownedF);
                allF.addAll(blueprintsF);
                PlanDelegate d = fit(m, allW, allF);
                int cost = 0;
                for (Object o : d.used) cost += (int) (o instanceof PlanWeapon ? ((PlanWeapon) o).price : ((PlanFighter) o).price);
                if (plan.totalCost + cost > plan.budget) {
                    restore(before);
                    fit(m, new ArrayList<>(owned), new ArrayList<>(ownedF));
                    plan.ownedOnly++;
                    continue;
                }
                plan.totalCost += cost;
                plan.planned++;
                String ship = m.getShipName() == null ? m.getHullSpec().getHullName() : m.getShipName();
                for (Object o : d.used) {
                    boolean wing = o instanceof PlanFighter;
                    if (wing ? !((PlanFighter) o).blueprint : !((PlanWeapon) o).blueprint) continue;
                    String id = wing ? ((PlanFighter) o).getId() : ((PlanWeapon) o).getId();
                    Line l = lines.computeIfAbsent((wing ? "f:" : "w:") + id, k -> new Line(wing, id,
                            wing ? wingName(((PlanFighter) o).spec) : ((PlanWeapon) o).spec.getWeaponName(),
                            (int) (wing ? ((PlanFighter) o).price : ((PlanWeapon) o).price)));
                    l.count++;
                    if (!l.ships.contains(ship)) l.ships.add(ship);
                }
            } catch (Throwable t) {
                plan.failed++;
                Global.getLogger(JAOFleetPlanner.class).warn("Jonah's Auto Outfitting: couldn't plan " + m.getShipName(), t);
            }
        }
        plan.lines.addAll(lines.values());
        plan.lines.sort(Comparator.comparingInt((Line l) -> -l.total()).thenComparing(l -> l.name));
        plan.ms = (System.nanoTime() - t0) / 1_000_000L;
        Global.getLogger(JAOFleetPlanner.class).info("Jonah's Auto Outfitting: fleet order planned in " + plan.ms + " ms ("
                + (plan.aotd ? "AoTD production" : "custom production") + "): " + plan.lines.size() + " items, " + plan.totalCost
                + " credits (budget " + plan.budget + "), " + plan.planned + " ships with blueprints, " + plan.ownedOnly
                + " with owned stock only, " + plan.skippedCivilian + " civilian skipped"
                + (plan.moved.isEmpty() ? "" : ", " + plan.moved.size() + " kinds waiting in the game's own list to move into AoTD's"));
        return plan;
    }

    /** Runs the Optimal fitter in planning mode on a copy of the ship. */
    private static PlanDelegate fit(FleetMemberAPI m, List<AvailableWeapon> weapons, List<AvailableFighter> fighters) {
        ShipVariantAPI v = m.getVariant().clone();
        PlanDelegate d = new PlanDelegate(weapons, fighters, m, new ArrayList<>(v.getNonBuiltInHullmods()));
        JAOAutofitPlugin plugin = new JAOAutofitPlugin(Global.getSector().getPlayerPerson());
        new JAOOptimizer(plugin, v, d, JAOSettings.style, false, true).run();
        return d;
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

    /** The weapons and wings a ship (or module) carries that a refit could take off: not built-in or mod-owned ones. */
    private static void addMounted(ShipVariantAPI v, Map<String, Integer> w, Map<String, Integer> f) {
        java.util.Set<String> skip = JAORatings.skipSlots(v.getHullSpec().getHullId(), v.getHullSpec().getBaseHullId());
        for (String slot : v.getFittedWeaponSlots()) {
            WeaponSlotAPI s = v.getSlot(slot);
            if (s == null || !JAOOptimizer.mountable(s) || skip.contains(slot) || v.getWeaponId(slot) == null) continue;
            w.merge(v.getWeaponId(slot), 1, Integer::sum);
        }
        int builtIn = v.getHullSpec().getBuiltInWings() == null ? 0 : v.getHullSpec().getBuiltInWings().size();
        List<String> wings = v.getWings();
        for (int i = builtIn; wings != null && i < wings.size(); i++) if (wings.get(i) != null) f.merge(wings.get(i), 1, Integer::sum);
    }

    private static void addCargo(CargoAPI c, Map<String, Integer> w, Map<String, Integer> f) {
        for (CargoItemQuantity<String> q : c.getWeapons()) w.merge(q.getItem(), q.getCount(), Integer::sum);
        for (CargoItemQuantity<String> q : c.getFighters()) f.merge(q.getItem(), q.getCount(), Integer::sum);
    }

    private static Map<Object, Integer> quantities(List<AvailableWeapon> a, List<AvailableFighter> b, List<AvailableWeapon> c, List<AvailableFighter> d) {
        Map<Object, Integer> m = new HashMap<>();
        for (AvailableWeapon w : a) m.put(w, w.getQuantity());
        for (AvailableWeapon w : c) m.put(w, w.getQuantity());
        for (AvailableFighter f : b) m.put(f, f.getQuantity());
        for (AvailableFighter f : d) m.put(f, f.getQuantity());
        return m;
    }

    private static void restore(Map<Object, Integer> q) {
        for (Map.Entry<Object, Integer> e : q.entrySet()) {
            if (e.getKey() instanceof AvailableWeapon) ((AvailableWeapon) e.getKey()).setQuantity(e.getValue());
            else ((AvailableFighter) e.getKey()).setQuantity(e.getValue());
        }
    }

    private static String weaponName(String id) {
        WeaponSpecAPI spec = weaponSpec(id);
        return spec == null ? id : spec.getWeaponName();
    }

    private static WeaponSpecAPI weaponSpec(String id) {
        try { return Global.getSettings().getWeaponSpec(id); } catch (Throwable t) { return null; }
    }

    private static FighterWingSpecAPI wingSpec(String id) {
        try { return Global.getSettings().getFighterWingSpec(id); } catch (Throwable t) { return null; }
    }

    private static String wingName(FighterWingSpecAPI spec) {
        try { return spec.getVariant().getHullSpec().getHullName() + " wing"; } catch (Throwable t) { return spec.getId(); }
    }
}
