package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.impl.campaign.plog.PlaythroughLog;
import com.fs.starfarer.api.impl.campaign.plog.SModRecord;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.plugins.AutofitPlugin.AutofitPluginDelegate;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableFighter;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableWeapon;
import com.fs.starfarer.api.plugins.impl.CoreAutofitPlugin;
import com.fs.starfarer.api.util.Misc;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * One Optimal fit: builds the strongest loadout for the ship from what the delegate offers (docs/design.md 9).
 *  1. modules first (each module's own slots, no hullmods, like the game's autofit)
 *  2. strip through the delegate, so the ship's own weapons and LPCs join the pool (unless "Strip" is unticked:
 *     then what's fitted stays), then sync the UI once so its OP check matches ours
 *  3. candidates per slot from the panel-filtered pool (slot fit, missile arcs, mod rules), wings for bays,
 *     hullmods the player knows that can go on
 *  4. JAOSearch picks weapons, wings, hullmods, vents and capacitors on the build score
 *  5. apply through the delegate, record purchases (credit cost), weapon groups, name, sync
 */
class JAOOptimizer {
    final JAOAutofitPlugin plugin;
    final ShipVariantAPI current;
    final AutofitPluginDelegate delegate;
    final JAOStyle style;
    final boolean module;
    /**
     * Planning on a copy (JAOFleetPlan): nothing spent, no timers started, nothing logged. Weapons and wings only, the
     * ship's hullmods kept, unless fullPlan: then hullmods too, and s-mods with planSmods (added to the copy, paid for
     * when the plan is applied).
     */
    final boolean planning;
    boolean fullPlan, planSmods;
    /** Fleet plan: give this (civilian) ship the logistics fit, from bare and without s-mods (user 11/06). */
    boolean logistics;
    /** The rest of the fleet when the fleet plan fits this ship (null: read the fleet as it stands). */
    JAOFleetMix fleetMix;
    /** After run(): the s-mod plan (null without one). */
    JAOSMods.Plan smodPlan;
    /** Optimize fleet buys from shops (0.5.57): prices count (jao_buyGain) and owned-only weapons aren't bought. */
    boolean buying;
    /**
     * Optimize fleet's "Change s-mods with credits" (0.5.57): the plan may replace or remove s-mods through the game's
     * Change s-mods. changeCredits: credits left for it, changePrice: the game's price of one change for this hull.
     */
    boolean changeSmods;
    double changeCredits;
    float changePrice;
    /** After run(): s-mods the changes took off and built in with credits, what they cost, and what was done. */
    final List<String> smodsRemoved = new ArrayList<>(), smodsBought = new ArrayList<>();
    double changeCost;
    final List<String> changeNotes = new ArrayList<>();

    JAOOptimizer(JAOAutofitPlugin plugin, ShipVariantAPI current, int maxSMods, AutofitPluginDelegate delegate, JAOStyle style) {
        this(plugin, current, delegate, style, false);
    }

    private JAOOptimizer(JAOAutofitPlugin plugin, ShipVariantAPI current, AutofitPluginDelegate delegate, JAOStyle style, boolean module) {
        this(plugin, current, delegate, style, module, false);
    }

    JAOOptimizer(JAOAutofitPlugin plugin, ShipVariantAPI current, AutofitPluginDelegate delegate, JAOStyle style, boolean module, boolean planning) {
        this.plugin = plugin; this.current = current; this.delegate = delegate; this.style = style; this.module = module;
        this.planning = planning;
    }

    void run() {
        long t0 = System.nanoTime();
        ShipHullSpecAPI hull = current.getHullSpec();
        current.setMayAutoAssignWeapons(false);
        // 0.5.90: Station Augments on this station, from Auto Colonies' tags (a module also gets its station's)
        if (stationFit()) augments.addAll(augmentsOf(current));
        if (!module && (logistics || (!planning && JAOSettings.civilianLogistics && civilian(hull)))) {
            civilianFit = true;
            runLogistics(hull, t0);
            return;
        }
        if (!module) fitModules();

        // planning always fits from scratch: the weapons on the ship are already counted in the planner's owned stock
        boolean strip = planning || plugin.option(CoreAutofitPlugin.STRIP);
        Set<String> skip = JAORatings.skipSlots(hull.getHullId(), hull.getBaseHullId());
        int bays = Global.getSettings().computeNumFighterBays(current);
        int builtInWings = hull.getBuiltInWings() == null ? 0 : hull.getBuiltInWings().size();

        // 2. strip
        if (strip) {
            for (String id : new ArrayList<>(current.getFittedWeaponSlots())) {
                WeaponSlotAPI slot = current.getSlot(id);
                if (slot == null || !mountable(slot) || skip.contains(id)) continue;
                plugin.clearWeaponSlot(slot, delegate, current);
            }
            // a wing only scripts place (Nimbus drones, kites) stays where it is: it can't go back into cargo (JAOCompat)
            for (int i = builtInWings; i < bays; i++) {
                String w = current.getWingId(i);
                if (w != null && !JAOCompat.boundWing(w)) plugin.clearFighterSlot(i, delegate, current);
            }
            if ((!module || stationFit()) && (!planning || fullPlan)) {
                for (String id : new ArrayList<>(current.getNonBuiltInHullmods())) {
                    HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
                    if (spec == null || spec.isHidden() || spec.isHiddenEverywhere() || JAOCompat.neverRemove(id, hull.getHullSize())) continue;
                    if (delegate.isPlayerCampaignRefit() && !delegate.canAddRemoveHullmodInPlayerCampaignRefit(id)) continue;
                    current.removeMod(id);
                }
            } else if (module && !JAOSettings.shroudedHullmods) {
                // 0.5.94: a ship's module keeps its hullmods, except Shrouded ones while they're off
                for (String id : new ArrayList<>(current.getNonBuiltInHullmods())) {
                    if (!shrouded(id)) continue;
                    if (delegate.isPlayerCampaignRefit() && !delegate.canAddRemoveHullmodInPlayerCampaignRefit(id)) continue;
                    current.removeMod(id);
                }
            }
            // 0.5.60: the bays as they are now (a stripped Converted Hangar takes its bay with it; the old count left a
            // wing slot on a hull with no bay)
            bays = Global.getSettings().computeNumFighterBays(current);
        }
        current.setNumFluxVents(0);
        current.setNumFluxCapacitors(0);
        // Confirm or preview? The preview delegate's syncUIWithVariant does nothing; the real one rebuilds the refit
        // screen's ship around this variant. Story points are only ever spent on a real (Confirm) run.
        ShipAPI before = delegate.getShip();
        if (!module) delegate.syncUIWithVariant(current);
        ShipAPI after = delegate.getShip();
        boolean real = !module && (after != before || (after != null && after.getVariant() == current));

        // 3. pool and candidates
        // planning takes the planner's whole pool, not filtered by the refit panel's cargo/storage/market boxes
        List<AvailableWeapon> weapons = planning ? delegate.getAvailableWeapons() : plugin.weapons(delegate);
        List<AvailableFighter> fighters = planning ? delegate.getAvailableFighters() : plugin.fighters(delegate);
        Map<String, Integer> stock = new LinkedHashMap<>();
        for (int i = 0; i < weapons.size(); i++) stock.put("w" + i, weapons.get(i).getQuantity());
        for (int i = 0; i < fighters.size(); i++) stock.put("f" + i, fighters.get(i).getQuantity());
        final int baysNow = bays;
        Supplier<List<JAOSearch.Slot>> slotsOf = () -> buildSlots(hull, skip, baysNow, builtInWings, weapons, fighters);
        List<JAOSearch.Slot> slots = slotsOf.get();
        // Optimize fleet's s-mod changes for credits (0.5.57) come first: everything else is chosen around the s-mods
        if (!module && planning && fullPlan && changeSmods) {
            try {
                if (dropShrouded()) dropShroudedSmods(current, null);
                planSmodChanges(hull, slotsOf, stock);
            } catch (Throwable t) {
                if (t instanceof JAOCoop.Cancelled) throw (JAOCoop.Cancelled) t;
                // changes already made to the plan stay with their records; no more are tried
                Global.getLogger(JAOOptimizer.class).warn("Auto Outfitting: s-mod change planning failed for " + shipName(), t);
            }
        }
        Set<String> keepMods = new LinkedHashSet<>(current.getNonBuiltInHullmods());
        // 0.5.90: a station's modules get hullmods (their OP is effectively unlimited); a ship's modules don't
        List<String> modCands = (module && !stationFit()) || (planning && !fullPlan) ? new ArrayList<>() : hullmodCandidates(keepMods);

        // 4. search (s-mods first: they change how much OP everything else gets)
        int hullOp = hull.getOrdnancePoints(plugin.stats());
        int budget = hullOp - current.computeOPCost(plugin.stats());
        JAORole role = roleFor(hull);
        JAOStyle st = role != null ? style : resolve(style, hull);
        JAOModel.Params p = paramsFor(hull, role, st);
        // bought weapons must earn their price (survival mode's x10 market prices); plans use owned stock or blueprints
        p.creditCost = planning && !buying ? 0f : JAOSettings.buyGain / 10000f;
        JAOShip world = stationWorld(new JAOShip(current, delegate, plugin.stats(), budget, plugin.getMaxVents(hull.getHullSize()), plugin.getMaxCaps(hull.getHullSize())));
        // 0.5.64: AI Tweaks' Custom AI on capitals (0.5.80: not cruisers); it costs no OP, so it's kept like a hullmod the ship has
        if (!module && JAOCompat.customAiWanted(hull.getHullSize(), delegate.getAvailableHullmods())
                && !current.hasHullMod(JAOCompat.CUSTOM_AI) && world.canAdd(JAOCompat.CUSTOM_AI, keepMods)) {
            current.addMod(JAOCompat.CUSTOM_AI);
            keepMods.add(JAOCompat.CUSTOM_AI);
        }
        if (!module && dropCustomAi(hull)) keepMods.remove(JAOCompat.CUSTOM_AI);
        // 0.5.95: the same fit as one made a moment ago (the Autofit dialog's preview, then Confirm) takes that fit's result
        String memoKey = memoKey(hull, weapons, fighters, keepMods, modCands, hullOp, budget, p, st, world);
        Memo hit = memoKey == null ? null : memoGet(memoKey, hull.getHullId());
        if (hit != null) {
            if (hit.swarm) current.addMod(SWARM);
            List<JAOSearch.Slot> hitSlots = hit.swarm ? slotsOf.get() : slots;
            if (hit.apply(hitSlots, stock)) {
                slots = hitSlots;
            } else {
                if (hit.swarm) current.removeMod(SWARM);
                hit = null;
            }
        }
        Set<String> smods = new LinkedHashSet<>();
        String smodNote = null;
        boolean smodStop = false, smodStart = false, smodStopIfReal = false;
        if (hit != null) {
            smods.addAll(hit.smods);
            smodNote = hit.smodNote;
            smodPlan = hit.smodPlan;
            FleetMemberAPI member = delegate.getFleetMember();
            if (member != null && member.getId() != null) {
                if (hit.smodStop) JAOSMods.stopWaiting(member.getId());
                if (hit.smodStart) JAOSMods.waitedDays(member.getId(), true);
                if (hit.smodStopIfReal && real) JAOSMods.stopWaiting(member.getId());
            }
        } else if (!module && (planning ? planSmods : plugin.option(JAOAutofitPlugin.SMODS))) {
            List<String> available = smodCandidates(modCands, keepMods, hull);
            // s-mods the credit changes took off this run aren't built back in with story points
            available.removeAll(smodsRemoved);
            int[] why = new int[4];
            int limit = smodLimit(why);
            FleetMemberAPI member = delegate.getFleetMember();
            String memberId = member == null ? null : member.getId();
            if (limit <= 0) {
                smodNote = !JAOFleetOptions.smodsOn(hull.getHullSize()) ? "s-mods on " + hull.getHullSize().name().toLowerCase(java.util.Locale.ROOT).replace("capital_ship", "capital") + "s are off (Optimize fleet, S-mods)"
                        : why[0] <= 0 && sizeCapped ? hull.getHullSize().name().toLowerCase(java.util.Locale.ROOT) + "s take at most " + sizeCap(hull.getHullSize()) + " s-mods (setting)"
                        : why[0] <= 0 ? "already has " + why[3] + " of " + (why[3] + Math.max(0, why[0])) + " s-mods"
                        : why[1] <= 0 && fleetCapUsed() ? "Story points per Optimize fleet (" + JAOSettings.smodFleetMax + ") used up by bigger ships"
                        : why[1] <= 0 ? "no story points" + (JAOSettings.smodReserve > 0 ? " above the " + JAOSettings.smodReserve + " kept in reserve" : "")
                        : "the s-mods per ship setting allows none";
                if (why[0] <= 0 && memberId != null && !planning) { JAOSMods.stopWaiting(memberId); smodStop = true; }
            } else {
                // waiting for missing hullmods is limited in time: after jao_smodWaitDays the ship builds the best it has
                // (a plan starts no timer: applying it does)
                float waited = memberId == null ? 0f : JAOSMods.waitedDays(memberId, false);
                boolean mayWait = JAOSettings.smodWait && waited < JAOSettings.smodWaitDays;
                // s-mods are permanent and d-mods can be removed (restoration, Hull Restoration), so s-mods are judged on
                // the hull without its removable d-mods (0.5.32, user: building in hullmods to counter d-mods "is pretty
                // stupid"); the ordinary fit below still counts them
                List<String> dmods = removableDmods(current);
                JAOShip smodWorld = dmods.isEmpty() ? world : restored(dmods, budget, hull);
                JAOSMods sm = new JAOSMods(smodWorld, p, slotsOf, stock, modCands, keepMods, limit);
                // 0.5.57 (user 11/16: no three s-mods on little frigates while capitals could use the story points): the
                // gain an s-mod must add grows as the ship gets smaller
                sm.minGain = JAOSettings.smodMinGain * sizeWeight(member, hull);
                List<String> every = available;
                if (mayWait) {
                    every = new ArrayList<>(JAOSMods.everything());
                    every.removeAll(smodsRemoved);
                    // 0.5.101: only hullmods this hull may take (rules, settings, built-ins): no waiting for one it never gets
                    final List<String> availNow = available;
                    final Set<String> keepNow = new LinkedHashSet<>(keepMods);
                    every.removeIf(id -> !availNow.contains(id) && !allowedHere(id, keepNow));
                }
                JAOSMods.Plan plan = sm.plan(available, every);
                smodPlan = plan;
                smods.addAll(plan.mods);
                // 0.5.66: an s-mod the hull's script would delete with the s-mods it ends up with (jao_rules smod_lost)
                Set<String> smodsAfter = new LinkedHashSet<>(current.getSMods());
                smodsAfter.addAll(smods);
                smods.removeIf(id -> JAOCompat.smodLost(id, hull.getBuiltInMods(), smodsAfter));
                smodNote = plan.note + (dmods.isEmpty() ? "" : " (judged without the removable d-mods " + dmods + ")");
                if (plan.waiting && memberId != null && !planning) {
                    waited = JAOSMods.waitedDays(memberId, true);
                    smodStart = true;
                    smodNote += "; waited " + Math.round(waited) + " of " + JAOSettings.smodWaitDays + " days";
                } else if (JAOSettings.smodWait && !mayWait) {
                    smodNote = "stopped waiting after " + JAOSettings.smodWaitDays + " days; " + smodNote;
                }
                // 0.5.60: s-mods go in on Confirm, so the wait is over
                smodStopIfReal = !plan.waiting && !plan.mods.isEmpty();
                if (smodStopIfReal && memberId != null && !planning && real) JAOSMods.stopWaiting(memberId);
            }
        }
        // 0.5.85 (user 11/23: "Sub-parts of ships don't get S-mods"): a module's s-mods are planned after its ship's own,
        // from the story points the ship leaves (moduleSmods); the module's fit here is made without them
        if (module && parentSize != null && !stationFit() && (planning ? planSmods : plugin.option(JAOAutofitPlugin.SMODS))) {
            final List<String> mc = hullmodCandidates(keepMods);
            final Set<String> km = new LinkedHashSet<>(keepMods);
            final JAOModel.Params pp = p;
            final JAOShip ww = world;
            final int bb = budget;
            final Map<String, Integer> ss = stock;
            deferredSmods = (isReal) -> planModuleSmods(hull, mc, km, ww, bb, pp, slotsOf, ss, isReal);
        }
        world.setSMods(smods);
        Set<String> keepAll = new LinkedHashSet<>(keepMods);
        keepAll.addAll(smods);
        List<String> cands = new ArrayList<>(modCands);
        cands.removeAll(smods);
        JAOSearch search = null;
        List<String> addMods = new ArrayList<>();
        int[] vc;
        Map<String, String> drawbacks;
        if (hit != null) {
            if (hit.swarm) keepAll.add(SWARM);
            addMods.addAll(hit.addMods);
            vc = hit.flux.clone();
            drawbacks = hit.drawbacks;
        } else {
            // 0.5.89: the stock as it was, for the Fragment Swarm trial (the search takes from the map it's given)
            Map<String, Integer> stock0 = module ? null : new LinkedHashMap<>(stock);
            search = new JAOSearch(world, p, slots, stock, cands, keepAll);
            if (!module && (!planning || fullPlan)) search.fleetMods.addAll(fleetHullmods(keepAll));
            search.run();
            oneFeedbackWeapon(search);
            boolean swarm = false;
            if (!module) {
                SwarmTrial t = swarmTrial(hull, hullOp, p, smods, keepAll, cands, slotsOf, stock0, weapons, world, search, real);
                if (t != null) {
                    search = t.search; slots = t.slots; world = t.world; keepAll = t.keepAll;
                    stock.clear();
                    stock.putAll(t.stock);
                    swarm = true;
                }
            }
            for (String m : search.mods) if (!keepAll.contains(m)) addMods.add(m);
            vc = search.fluxBest();
            drawbacks = world.drawbacks;
            if (memoKey != null) memoPut(Memo.of(memoKey, hull.getHullId(), slots, addMods, vc, drawbacks, swarm, smods, smodNote, smodPlan,
                    smodStop, smodStart, smodStopIfReal, search.evaluations));
        }

        // 5. apply: hullmods, weapons and wings (OP-checked by the real delegate), then s-mods (no OP), then flux
        for (String m : addMods) current.addMod(m);
        for (JAOSearch.Slot s : slots) {
            if (s.fixed != null || s.pick == null || s.pick.entry == null) continue;
            if (s.bay) {
                int index = Integer.parseInt(s.id.substring(3));
                AvailableFighter f = (AvailableFighter) s.pick.entry;
                delegate.fitFighterInSlot(index, f, current);
                plugin.recordFighter(current.getHullVariantId() + "_" + s.id, f);
            } else {
                AvailableWeapon w = (AvailableWeapon) s.pick.entry;
                int had = w.getSource() == null ? -1 : w.getSource().getNumWeapons(w.getId());
                delegate.fitWeaponInSlot(current.getSlot(s.id), w, current);
                // the real delegate takes the weapon out of its cargo; the preview one doesn't
                if (had > 0 && w.getSource().getNumWeapons(w.getId()) < had) real = !module;
                plugin.recordWeapon(current.getHullVariantId() + "_" + s.id, w);
                // the real delegate silently skips a weapon that fails its own checks: its OP then goes to flux below
                if (!planning && !w.getId().equals(current.getWeaponId(s.id))) {
                    Global.getLogger(JAOOptimizer.class).warn("Auto Outfitting: " + w.getId() + " didn't go into "
                            + s.id + " on " + shipName() + " (the game refused it); its OP goes to vents/capacitors");
                }
            }
        }
        int built = smods.isEmpty() ? 0 : buildIn(smods, real, hull);
        // 0.5.85: then the modules' s-mods, from the story points left
        if (!module) moduleSmods(real, smods.size());
        int left = hullOp - current.computeOPCost(plugin.stats());
        if (left < 0) {
            // s-mods that couldn't be built in were added as ordinary hullmods: drop them if they don't fit
            for (String m : new ArrayList<>(smods)) {
                if (left >= 0) break;
                if (current.getNonBuiltInHullmods().contains(m)) { current.removeMod(m); left = hullOp - current.computeOPCost(plugin.stats()); }
            }
        }
        // vents and capacitors from the OP really left now (not the plan's figure), the plan's split first and any
        // difference into vents, then capacitors, up to their maximums: no point is left unspent if one can be used
        int maxV = plugin.getMaxVents(hull.getHullSize()), maxC = plugin.getMaxCaps(hull.getHullSize());
        int vents = Math.min(Math.min(vc[0], maxV), Math.max(0, left));
        int caps = Math.min(Math.min(vc[1], maxC), Math.max(0, left - vents));
        int extra = Math.max(0, left - vents - caps);
        int addV = Math.min(extra, maxV - vents);
        vents += addV;
        caps += Math.min(extra - addV, maxC - caps);
        current.setNumFluxVents(vents);
        current.setNumFluxCapacitors(caps);
        int unused = hullOp - current.computeOPCost(plugin.stats());
        if (!planning && (real || JAOSettings.debug)) {
            // 0.5.95: a module's spare OP goes into its ship's or station's line, written once after the last module
            List<String> letIn = new ArrayList<>();
            if (unused > 0 && !JAOSettings.allowDrawbacks) {
                for (Map.Entry<String, String> e : drawbacks.entrySet()) {
                    if (world.modOp(e.getKey()) <= unused) letIn.add(JAOSMods.name(e.getKey()) + " (" + e.getValue() + ")");
                }
            }
            if (parentFit != null) {
                if (unused > 0) {
                    parentFit.moduleUnused.merge(unused + " on " + hull.getHullId(), 1, Integer::sum);
                    parentFit.moduleLetIn.addAll(letIn);
                    if (!drawbacks.isEmpty()) parentFit.moduleHadDrawbacks = true;
                }
            } else if (unused > 0 || !moduleUnused.isEmpty()) {
                Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + unusedLine(unused, letIn, !drawbacks.isEmpty()));
            }
        }

        current.getWeaponGroups().clear();
        current.setMayAutoAssignWeapons(true);
        current.autoGenerateWeaponGroups();
        JAOGroups.arrange(current);
        current.setSource(VariantSource.REFIT);
        if (!module) {
            String what = role != null ? role.label : "";
            if (st != JAOStyle.AUTO && st != JAOStyle.BALANCED) what = what.isEmpty() ? st.label : what + ", " + st.label;
            if (p.flagship) what = what.isEmpty() ? "Flagship" : what + ", flagship";
            current.setVariantDisplayName(JAOGoals.PREFIX + (what.isEmpty() ? "" : ": " + what) + (built > 0 ? " +" + built + " s-mod" + (built > 1 ? "s" : "") : "")
                    + (unused > 0 ? " (" + unused + " OP spare" + (!JAOSettings.allowDrawbacks && !drawbacks.isEmpty() ? ": drawbacks off)" : ")") : ""));
            delegate.syncUIWithVariant(current);
            if (real) settleOp(hullOp, maxV, maxC, role == JAORole.CARRIER || role == JAORole.PHASE || role == JAORole.MISSILE_BOAT || role == JAORole.SUPPORT);
        }
        if (smodNote != null && (real || JAOSettings.debug)) {
            Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + shipName() + " s-mods" + (real ? "" : " (preview)") + ": " + smodNote);
        }
        if (JAOSettings.debug) {
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (search != null) log(search, world, p, st, ms);
            else Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + hull.getHullId() + (module ? " (module)" : "") + " "
                    + (p.role != null ? p.role.label + "/" : "") + st.label + " in " + ms + " ms: same fit as " + hit.evaluations
                    + " evaluations " + (System.currentTimeMillis() - hit.at) / 1000L + " s ago (Autofit preview), reused");
            // 0.5.95: a module's list goes to its ship or station, written once with its own
            if (parentFit != null) {
                for (Map.Entry<String, String> e : drawbacks.entrySet()) parentFit.moduleDrawbacks.putIfAbsent(e.getKey(), e.getValue());
            } else {
                Map<String, String> all = new LinkedHashMap<>(drawbacks);
                for (Map.Entry<String, String> e : moduleDrawbacks.entrySet()) all.putIfAbsent(e.getKey(), e.getValue());
                if (!all.isEmpty()) Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + shipName() + " hullmods skipped for drawbacks: " + all);
            }
        }
    }

    /** 0.5.95: set on a module's fit; its OP-unused and drawback notes go to this fit (its ship or station). */
    JAOOptimizer parentFit;
    /** 0.5.95: "674 on module_hightech_hangar" -> how many modules had it. */
    final Map<String, Integer> moduleUnused = new LinkedHashMap<>();
    /** 0.5.95: drawback hullmods that would fit a module's spare OP. */
    final Set<String> moduleLetIn = new LinkedHashSet<>();
    boolean moduleHadDrawbacks;
    /** 0.5.95: the modules' hullmods skipped for drawbacks (debug log). */
    final Map<String, String> moduleDrawbacks = new java.util.TreeMap<>();

    /** 0.5.95: one line for the ship's or station's spare OP and its modules'. */
    String unusedLine(int unused, List<String> letIn, boolean hadDrawbacks) {
        StringBuilder sb = new StringBuilder(shipName());
        List<String> mods = new ArrayList<>();
        for (Map.Entry<String, Integer> e : moduleUnused.entrySet()) mods.add(e.getKey() + (e.getValue() > 1 ? " (x" + e.getValue() + ")" : ""));
        if (unused > 0) sb.append(" has ").append(unused).append(" OP unused");
        if (!mods.isEmpty()) sb.append(unused > 0 ? ", modules " : " modules have OP unused: ").append(String.join(", ", mods));
        sb.append(": vents and capacitors at maximum, no hullmod or weapon upgrade fits");
        Set<String> all = new LinkedHashSet<>(letIn);
        all.addAll(moduleLetIn);
        if (!JAOSettings.allowDrawbacks && (hadDrawbacks || moduleHadDrawbacks)) {
            sb.append("; hullmods with drawbacks that \"Allow hullmods with drawbacks\" would let in: ")
                    .append(all.isEmpty() ? "none that fits" : String.join(", ", all));
        }
        return sb.toString();
    }

    /**
     * 0.5.95: one fit's search result, for a fit with exactly the same inputs. The refit screen's Autofit dialog fits the
     * ship for its preview and again on Confirm (log 11/25: a Voidsaint twice, 150 and 162 ms, the same 1,453
     * evaluations). The key holds everything the search reads; Confirm then applies the preview's picks without a search.
     * Single fits on the refit screen only (no plans, no station fits); kept MEMO_MS.
     */
    static final class Memo {
        String key, hullId;
        long at;
        /** Slot id -> the pick's candKey (weapon or wing id, price, where it comes from). */
        final Map<String, String> picks = new LinkedHashMap<>();
        final List<String> addMods = new ArrayList<>();
        int[] flux;
        final Map<String, String> drawbacks = new LinkedHashMap<>();
        boolean swarm;
        final Set<String> smods = new LinkedHashSet<>();
        String smodNote;
        JAOSMods.Plan smodPlan;
        boolean smodStop, smodStart, smodStopIfReal;
        int evaluations;

        static Memo of(String key, String hullId, List<JAOSearch.Slot> slots, List<String> addMods, int[] flux, Map<String, String> drawbacks,
                       boolean swarm, Set<String> smods, String smodNote, JAOSMods.Plan smodPlan, boolean smodStop, boolean smodStart,
                       boolean smodStopIfReal, int evaluations) {
            Memo m = new Memo();
            m.key = key; m.hullId = hullId; m.at = System.currentTimeMillis();
            for (JAOSearch.Slot s : slots) if (s.fixed == null && s.pick != null) m.picks.put(s.id, candKey(s.pick));
            m.addMods.addAll(addMods);
            m.flux = flux.clone();
            m.drawbacks.putAll(drawbacks);
            m.swarm = swarm;
            m.smods.addAll(smods);
            m.smodNote = smodNote; m.smodPlan = smodPlan;
            m.smodStop = smodStop; m.smodStart = smodStart; m.smodStopIfReal = smodStopIfReal;
            m.evaluations = evaluations;
            return m;
        }

        /** Puts the kept picks on these slots and takes them from the stock. False (nothing changed) if one isn't there. */
        boolean apply(List<JAOSearch.Slot> slots, Map<String, Integer> stock) {
            Map<JAOSearch.Slot, JAOSearch.Cand> found = new java.util.IdentityHashMap<>();
            Map<String, Integer> need = new HashMap<>();
            int matched = 0;
            for (JAOSearch.Slot s : slots) {
                if (s.fixed != null) continue;
                String want = picks.get(s.id);
                if (want == null) continue;
                JAOSearch.Cand c = null;
                for (JAOSearch.Cand x : s.cands) if (want.equals(candKey(x))) { c = x; break; }
                if (c == null) return false;
                found.put(s, c);
                need.merge(c.stockKey, 1, Integer::sum);
                matched++;
            }
            if (matched != picks.size()) return false;
            for (Map.Entry<String, Integer> e : need.entrySet()) {
                Integer q = stock.get(e.getKey());
                if (q == null || q < e.getValue()) return false;
            }
            for (JAOSearch.Slot s : slots) if (s.fixed == null) s.pick = found.get(s);
            for (Map.Entry<String, Integer> e : need.entrySet()) stock.merge(e.getKey(), -e.getValue(), Integer::sum);
            return true;
        }
    }

    static final long MEMO_MS = 300_000L;
    static final int MEMO_MAX = 32;
    private static final Map<String, Memo> MEMO = new LinkedHashMap<>(16, 0.75f, true);

    static synchronized Memo memoGet(String key, String hullId) {
        long now = System.currentTimeMillis();
        MEMO.values().removeIf(m -> now - m.at > MEMO_MS);
        Memo m = MEMO.get(key);
        if (m == null && JAOSettings.debug) {
            // the newest kept fit of this hull, for the line that differs (shows why a Confirm searched again)
            Memo last = null;
            for (Memo x : MEMO.values()) if (x.hullId.equals(hullId) && (last == null || x.at >= last.at)) last = x;
            if (last != null) Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + hullId + " fit not reused: " + memoDiff(last.key, key));
        }
        return m;
    }

    static synchronized void memoPut(Memo m) {
        MEMO.put(m.key, m);
        while (MEMO.size() > MEMO_MAX) {
            java.util.Iterator<String> it = MEMO.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    static synchronized void memoClear() { MEMO.clear(); }

    /** The first key line that differs, by its label. */
    static String memoDiff(String a, String b) {
        String[] x = a.split("\n"), y = b.split("\n");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "", q = i < y.length ? y[i] : "";
            if (!p.equals(q)) {
                int c = q.indexOf(':');
                return (c > 0 ? q.substring(0, c) : "line " + i) + " changed";
            }
        }
        return "same key";
    }

    /** A pick by what it is and where it comes from (cargo or a submarket), the same for the preview and Confirm. */
    static String candKey(JAOSearch.Cand c) {
        return c.w.id + "@" + c.price + "/" + source(c.entry);
    }

    static String source(Object entry) {
        com.fs.starfarer.api.campaign.econ.SubmarketAPI sub = null;
        try {
            if (entry instanceof AvailableWeapon) sub = ((AvailableWeapon) entry).getSubmarket();
            else if (entry instanceof AvailableFighter) sub = ((AvailableFighter) entry).getSubmarket();
            if (sub == null) return "fleet";
            return sub.getSpecId() + (sub.getMarket() == null ? "" : "." + sub.getMarket().getId());
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 0.5.95: everything this fit's search and s-mod plan read, one labelled line each (Memo); null for fits that aren't
     * kept (plans, station fits, the civilian logistics fit).
     */
    private String memoKey(ShipHullSpecAPI hull, List<AvailableWeapon> weapons, List<AvailableFighter> fighters, Set<String> keepMods,
                           List<String> modCands, int hullOp, int budget, JAOModel.Params p, JAOStyle st, JAOShip world) {
        if (planning || stationFit() || civilianFit) return null;
        try {
            StringBuilder k = new StringBuilder(8192);
            FleetMemberAPI m = delegate.getFleetMember();
            k.append("ship: ").append(m == null ? "-" : m.getId()).append(' ').append(hull.getHullId())
                    .append(module ? " module of " + parentSize + " x" + moduleCount : "").append('\n');
            k.append("hullmods: ").append(new TreeSet<>(current.getHullMods())).append('\n');
            k.append("s-mods: ").append(new TreeSet<>(current.getSMods())).append(' ').append(new TreeSet<>(current.getSModdedBuiltIns()))
                    .append(' ').append(new TreeSet<>(current.getPermaMods())).append(' ').append(new TreeSet<>(current.getSuppressedMods())).append('\n');
            Map<String, String> fitted = new java.util.TreeMap<>();
            for (String id : current.getFittedWeaponSlots()) fitted.put(id, current.getWeaponId(id));
            k.append("weapons on: ").append(fitted).append('\n');
            k.append("wings on: ").append(current.getWings()).append('\n');
            k.append("kept: ").append(keepMods).append('\n');
            k.append("candidates: ").append(modCands).append('\n');
            k.append("hullmods known: ").append(new TreeSet<>(delegate.getAvailableHullmods())).append('\n');
            k.append("op: ").append(hullOp).append(' ').append(budget).append(' ').append(plugin.getMaxVents(hull.getHullSize()))
                    .append(' ').append(plugin.getMaxCaps(hull.getHullSize())).append('\n');
            k.append("params: ").append(st).append(' ').append(p.sig()).append('\n');
            k.append("captain: ").append(m == null || m.getCaptain() == null ? "-" : m.getCaptain().getId()).append(' ').append(world.captainSkills).append('\n');
            int[] why = new int[4];
            int limit = module ? 0 : smodLimit(why);
            k.append("s-mod limit: ").append(plugin.option(JAOAutofitPlugin.SMODS)).append(' ').append(limit).append(' ').append(java.util.Arrays.toString(why))
                    .append(' ').append(storyPoints).append(' ').append(Global.getSector().getPlayerStats().getStoryPoints())
                    .append(' ').append(m == null || m.getId() == null ? 0f : JAOSMods.waitedDays(m.getId(), false)).append('\n');
            List<String> pool = new ArrayList<>();
            for (AvailableWeapon w : weapons) pool.add(w.getId() + "@" + w.getPrice() + "/" + source(w) + " x" + w.getQuantity());
            java.util.Collections.sort(pool);
            k.append("weapon stock: ").append(pool).append('\n');
            pool.clear();
            for (AvailableFighter f : fighters) pool.add(f.getId() + "@" + f.getPrice() + "/" + source(f) + " x" + f.getQuantity());
            java.util.Collections.sort(pool);
            k.append("wing stock: ").append(pool).append('\n');
            k.append("options: ").append(plugin.optionSig()).append('\n');
            k.append("settings: ").append(JAOSettings.loads).append(' ').append(JAOSettings.summary()).append('\n');
            return k.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The build's params: style and role, then the rest of the fleet: fighter roles already covered, and (Auto/Balanced)
     * the damage it's short of. The fleet plan passes the rest of its plan; a single fit reads the fleet as it stands.
     */
    private JAOModel.Params paramsFor(ShipHullSpecAPI hull, JAORole role, JAOStyle st) {
        JAOModel.Params p = params(st, Math.max(0, JAORatings.sizeIndex(hull.getHullSize())), role, flagshipFit());
        // 0.5.70 (Auto Colonies fits player stations): a station and its modules hold their orbit and face every size
        if (stationFit()) {
            p.station = true;
            p.evasionW = 0f;
            p.targets = JAORatings.targets(STATION_MIX, st);
            // 0.5.90: a station's carrier sections keep their guns (the carrier rule is for ships the AI would fly in to use
            // them), and its fight lasts until the attackers leave: limited ammo is spread over STATION_FIGHT x the fight
            p.carrierGuns = false;
            p.fightSeconds *= STATION_FIGHT;
        }
        if (!module && JAOSettings.fleetBalance) {
            try {
                JAOFleetMix others = fleetMix != null ? fleetMix : JAOFleetContext.of(delegate.getFleetMember()).mix;
                others.wings(p);
                if (st == JAOStyle.AUTO || st == JAOStyle.BALANCED) others.complement(p);
                if (JAOSettings.debug && !planning) Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: fleet context for " + shipName()
                        + ": " + others.ships + " other ships, " + others.describe() + " -> weights " + p.wS + "/" + p.wA + "/" + p.wH);
            } catch (Throwable t) {
                Global.getLogger(JAOOptimizer.class).warn("Auto Outfitting: couldn't read the fleet for balance", t);
            }
        }
        return p;
    }

    /**
     * The build score of the variant as it stands, every weapon, wing and hullmod kept, the OP left in vents and
     * capacitors the way a fit spends it, under the same params a fit would get (with fleetMix). The fleet plan compares
     * a ship's new plan with its old one this way, so both are judged alike.
     */
    float scoreAsIs() {
        ShipHullSpecAPI hull = current.getHullSpec();
        Set<String> skip = JAORatings.skipSlots(hull.getHullId(), hull.getBaseHullId());
        int bays = Global.getSettings().computeNumFighterBays(current);
        int builtInWings = hull.getBuiltInWings() == null ? 0 : hull.getBuiltInWings().size();
        List<JAOSearch.Slot> slots = buildSlots(hull, skip, bays, builtInWings, new ArrayList<>(), new ArrayList<>());
        int vents = current.getNumFluxVents(), caps = current.getNumFluxCapacitors();
        current.setNumFluxVents(0);
        current.setNumFluxCapacitors(0);
        int budget = hull.getOrdnancePoints(plugin.stats()) - current.computeOPCost(plugin.stats());
        current.setNumFluxVents(vents);
        current.setNumFluxCapacitors(caps);
        JAORole role = roleFor(hull);
        JAOStyle st = role != null ? style : resolve(style, hull);
        JAOModel.Params p = paramsFor(hull, role, st);
        JAOShip world = new JAOShip(current, delegate, plugin.stats(), budget, plugin.getMaxVents(hull.getHullSize()), plugin.getMaxCaps(hull.getHullSize()));
        JAOSearch s = new JAOSearch(world, p, slots, new LinkedHashMap<>(), new ArrayList<>(), new LinkedHashSet<>(current.getNonBuiltInHullmods()));
        return s.objective();
    }

    /**
     * After Confirm the game rebuilds the ship around the new loadout, and hullmods whose effects depend on it (OP cost
     * listeners, weapon-composition mods) can change what it costs. The user saw an Aquilon land at 220 of 250 OP with
     * nothing logged. So the OP is read again from the rebuilt ship: spare points go to vents, then capacitors (gun
     * ships), or capacitors first on carriers, phase ships, support and missile boats, which fire little and need the
     * buffer (research build_philosophy R2.6); an over-budget ship gives back the other kind first. Logged whenever it
     * changes anything.
     */
    private void settleOp(int hullOp, int maxV, int maxC, boolean capsFirst) {
        int left = hullOp - current.computeOPCost(plugin.stats());
        if (left == 0) return;
        int v = current.getNumFluxVents(), c = current.getNumFluxCapacitors(), v0 = v, c0 = c;
        if (left > 0) {
            if (capsFirst) {
                int addC = Math.min(left, Math.max(0, maxC - c));
                c += addC;
                v += Math.min(left - addC, Math.max(0, maxV - v));
            } else {
                int addV = Math.min(left, Math.max(0, maxV - v));
                v += addV;
                c += Math.min(left - addV, Math.max(0, maxC - c));
            }
        } else {
            int over = -left;
            if (capsFirst) {
                int cutV = Math.min(over, v);
                v -= cutV;
                c -= Math.min(over - cutV, c);
            } else {
                int cutC = Math.min(over, c);
                c -= cutC;
                v -= Math.min(over - cutC, v);
            }
        }
        if (v == v0 && c == c0) return;
        current.setNumFluxVents(v);
        current.setNumFluxCapacitors(c);
        delegate.syncUIWithVariant(current);
        Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + shipName() + " had " + left + " OP " + (left > 0 ? "spare" : "over")
                + " once the game rebuilt it (hullmods that change costs); vents " + v0 + " -> " + v + ", capacitors " + c0 + " -> " + c
                + ", now " + (hullOp - current.computeOPCost(plugin.stats())) + " OP left");
    }

    /** Story points the fleet plan has left for this ship (it shares them across the fleet); -1: the player's own. */
    int storyPoints = -1;
    /** The rest of the fleet's utility hullmods, from the fleet plan (null: read from the player fleet). */
    JAOUtility.Others utilityOthers;

    /** 0.5.85: the ship a module belongs to (its size picks the s-mod type box and cap); null on a ship. */
    HullSize parentSize;
    /** 0.5.85: how many modules the module's ship has (a module's gain is one part of the ship's). */
    int moduleCount;
    /** 0.5.85: the module's s-mod plan, run after its ship's s-mods (moduleSmods); null when it gets none. */
    java.util.function.Function<Boolean, Integer> deferredSmods;
    /** 0.5.85: the ship's module fits, for their s-mods once the ship's own are chosen. */
    private final List<JAOOptimizer> moduleFits = new ArrayList<>();
    /** 0.5.85: s-mods the modules got in this fit (planned or built). */
    int moduleNewSmods;
    /** 0.5.85: "slot: hullmod" for each s-mod planned on a module, for the notes. */
    final List<String> moduleSmodNotes = new ArrayList<>();

    /**
     * 0.5.85: the modules' s-mods after the ship's own (the ship gets the story points first), each module in turn from
     * what's left. A real fit (Confirm) spends the story points as the refit screen does; a plan only marks them.
     */
    private void moduleSmods(boolean real, int shipNew) {
        moduleNewSmods = 0;
        // 0.5.101: a preview spends nothing, so its modules count from the live story points less the ship's planned s-mods
        int live = -1;
        if (!real && storyPoints < 0) try { live = Global.getSector().getPlayerStats().getStoryPoints(); } catch (Throwable ignored) { }
        int sp = storyPoints >= 0 ? storyPoints - shipNew : live < 0 ? -1 : Math.max(0, live - shipNew);
        for (JAOOptimizer mo : moduleFits) {
            if (mo.deferredSmods == null) continue;
            mo.storyPoints = sp;
            int n;
            try {
                n = mo.deferredSmods.apply(real);
            } catch (Throwable t) {
                if (t instanceof JAOCoop.Cancelled) throw (JAOCoop.Cancelled) t;
                Global.getLogger(JAOOptimizer.class).warn("Auto Outfitting: module s-mods failed on " + shipName() + " (" + mo.current.getHullSpec().getHullId() + ")", t);
                continue;
            }
            moduleNewSmods += n;
            for (String note : mo.moduleSmodNotes) moduleSmodNotes.add(note);
            if (sp >= 0) sp -= n;
        }
    }

    /** 0.5.85: a module's s-mods (no waiting for missing hullmods, its ship's size for the box and cap). Returns how many. */
    private int planModuleSmods(ShipHullSpecAPI hull, List<String> modCands, Set<String> keepMods, JAOShip world, int budget,
                                JAOModel.Params p, Supplier<List<JAOSearch.Slot>> slotsOf, Map<String, Integer> stock, boolean real) {
        moduleSmodNotes.clear();
        List<String> available = smodCandidates(modCands, keepMods, hull);
        int limit = smodLimit(new int[4]);
        if (limit <= 0 || available.isEmpty()) return 0;
        // 0.5.101: judged on the module as fitted (this runs after its fit): its weapons and hullmods kept, the OP left
        // to vents and capacitors, no ordinary hullmods added (a ship's modules get none). Before, the fitted weapons
        // counted as free on top of the whole pre-fit budget.
        int vents = current.getNumFluxVents(), caps = current.getNumFluxCapacitors();
        current.setNumFluxVents(0);
        current.setNumFluxCapacitors(0);
        int asIs = hull.getOrdnancePoints(plugin.stats()) - current.computeOPCost(plugin.stats());
        current.setNumFluxVents(vents);
        current.setNumFluxCapacitors(caps);
        Set<String> keepNow = new LinkedHashSet<>(current.getNonBuiltInHullmods());
        JAOShip fitted = new JAOShip(current, delegate, plugin.stats(), asIs, plugin.getMaxVents(hull.getHullSize()), plugin.getMaxCaps(hull.getHullSize()));
        List<String> dmods = removableDmods(current);
        JAOShip smodWorld = dmods.isEmpty() ? fitted : restored(dmods, asIs, hull);
        JAOSMods sm = new JAOSMods(smodWorld, p, slotsOf, stock, new ArrayList<>(), keepNow, limit);
        sm.minGain = JAOSettings.smodMinGain * sizeWeight(delegate.getFleetMember(), hull) * moduleShare(moduleCount);
        JAOSMods.Plan plan = sm.plan(available, available);
        Set<String> smods = new LinkedHashSet<>(plan.mods);
        Set<String> after = new LinkedHashSet<>(current.getSMods());
        after.addAll(smods);
        smods.removeIf(id -> JAOCompat.smodLost(id, hull.getBuiltInMods(), after));
        if (smods.isEmpty()) return 0;
        // the module's OP doesn't change (s-mods cost none); its vents and capacitors stay as fitted
        int n = buildIn(smods, real, hull);
        for (String id : smods) moduleSmodNotes.add(hull.getHullName() + ": " + JAOSMods.name(id));
        if (real || JAOSettings.debug) Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + shipName() + " module " + hull.getHullId()
                + " s-mods" + (real ? "" : " (preview)") + ": " + smods + "; " + plan.note);
        return n;
    }

    /** 0.5.85: how much harder an s-mod on one module must work than on a ship: its share of a ship with this many modules. */
    static float moduleShare(int modules) { return Math.max(1f, modules); }

    /** S-mods this fit may build in: the ship's limit left, story points above the reserve, the per-ship setting. */
    private int smodLimit(int[] why) {
        int max = Misc.getMaxPermanentMods(delegate.getFleetMember(), plugin.stats()), cap = civilianFit ? 99 : sizeCap(parentSize != null ? parentSize : current.getHullSpec().getHullSize());
        sizeCapped = cap < max;
        int room = Math.min(max, cap) - current.getSMods().size();
        int sp = (storyPoints >= 0 ? storyPoints : Global.getSector().getPlayerStats().getStoryPoints()) - JAOSettings.smodReserve;
        // why: room left, story points above the reserve, the per-ship setting, s-mods already on the ship
        why[0] = room; why[1] = sp; why[2] = JAOSettings.smodCount; why[3] = current.getSMods().size();
        return smodLimit(room, sp + JAOSettings.smodReserve, JAOSettings.smodReserve, JAOSettings.smodCount);
    }

    /** 0.5.73: the civilian logistics fit (its s-mods follow the Civilian box, not the warship sizes). */
    private boolean civilianFit;

    /** The size cap (frigates, destroyers) is below the game's s-mod limit for this ship. */
    private boolean sizeCapped;

    /** Most s-mods Auto Outfitting builds into this hull size (jao_smodMaxFrigate / jao_smodMaxDestroyer; others: no cap). */
    static int sizeCap(HullSize size) {
        // 0.5.73: s-mods by class (warships; the civilian logistics fit uses its own setting, civilianFit)
        if (!JAOFleetOptions.smodsOn(size)) return 0;
        if (size == HullSize.FRIGATE) return JAOSettings.smodMaxFrigate;
        if (size == HullSize.DESTROYER) return JAOSettings.smodMaxDestroyer;
        return 99;
    }

    /**
     * How much more gain an s-mod must add on this ship (0.5.57): 20 / its deployment points, from 0.5 to 4, so a story
     * point goes where it adds the most to the fleet. 1 with "S-mods favor bigger ships" off.
     */
    static float sizeWeight(FleetMemberAPI m, ShipHullSpecAPI hull) {
        if (!JAOSettings.smodBySize) return 1f;
        float dp = 0f;
        try { dp = m != null ? m.getDeploymentPointsCost() : hull.getSuppliesToRecover(); } catch (Throwable ignored) { }
        if (dp <= 0f) { try { dp = hull.getSuppliesToRecover(); } catch (Throwable ignored) { } }
        return sizeWeight(dp);
    }

    static float sizeWeight(float dp) { return dp <= 0f ? 1f : Math.max(0.5f, Math.min(4f, 20f / dp)); }

    /** Hullmods the s-mod search may build in: buildable candidates and kept hullmods, and those worth something only as s-mods. */
    /** 0.5.80: Custom AI off a cruiser (JAOCompat.customAiDrop), where the refit screen allows the change. */
    private boolean dropCustomAi(ShipHullSpecAPI hull) {
        if (!JAOCompat.customAiDrop(hull.getHullSize()) || !current.getNonBuiltInHullmods().contains(JAOCompat.CUSTOM_AI)) return false;
        if (current.getPermaMods().contains(JAOCompat.CUSTOM_AI)) return false;
        if (delegate.isPlayerCampaignRefit() && !delegate.canAddRemoveHullmodInPlayerCampaignRefit(JAOCompat.CUSTOM_AI)) return false;
        current.removeMod(JAOCompat.CUSTOM_AI);
        return true;
    }

    private List<String> smodCandidates(List<String> modCands, Set<String> keepMods, ShipHullSpecAPI hull) {
        List<String> available = new ArrayList<>();
        for (String id : modCands) if (JAOSMods.buildable(id)) available.add(id);
        for (String id : keepMods) if (JAOSMods.buildable(id) && !available.contains(id)) available.add(id);
        // 0.5.66: never one the hull's script deletes next to the s-mods already built in (jao_rules smod_lost)
        available.removeIf(id -> JAOCompat.smodLost(id, hull.getBuiltInMods(), current.getSMods()));
        // hullmods worth something only as s-mods (Escort Package) aren't ordinary candidates
        for (String id : JAOShip.SMOD_VALUE) {
            if (available.contains(id) || !delegate.getAvailableHullmods().contains(id) || hull.getBuiltInMods().contains(id)
                    || JAOCompat.removedByHull(id, hull) || !JAOSMods.buildable(id)) continue;
            available.add(id);
        }
        return available;
    }

    /**
     * Optimize fleet's "Change s-mods with credits" (0.5.57, user 11/16: changes cost a lot, so "very, very judiciously").
     * At the player's colony with heavy industry the game's Change s-mods takes s-mods off and builds others in for
     * credits (changeSmodsCost per hull size, per s-mod removed and per s-mod added), no story points. One change at a
     * time, best first, at most 3: an s-mod comes off, and the best other hullmod goes in if it adds the minimum gain
     * per s-mod, only when the ship ends up jao_smodChangeMinGain (x the size weight) stronger than with the s-mods it
     * has, and the price fits the credits left. S-mods built in with credits this run aren't removed again, and removed
     * ones don't come back. Built-in s-mods never change.
     */
    private void planSmodChanges(ShipHullSpecAPI hull, Supplier<List<JAOSearch.Slot>> slotsOf, Map<String, Integer> stock) {
        if (changePrice <= 0f) return;
        // 0.5.73: no s-mod changes on a class with s-mods turned off
        if (!JAOFleetOptions.smodsOn(hull.getHullSize())) return;
        FleetMemberAPI member = delegate.getFleetMember();
        // only ships with no free s-mod slot (game limit and size cap): a free slot takes a new s-mod with a story point,
        // which keeps the s-mod already there
        int full = Math.min(Misc.getMaxPermanentMods(member, plugin.stats()), sizeCap(hull.getHullSize()));
        float weight = sizeWeight(member, hull);
        float need = JAOSettings.smodChangeMinGain * weight;
        JAORole role = roleFor(hull);
        JAOStyle st = role != null ? style : resolve(style, hull);
        JAOModel.Params p = paramsFor(hull, role, st);
        p.creditCost = buying ? JAOSettings.buyGain / 10000f : 0f;
        int maxV = plugin.getMaxVents(hull.getHullSize()), maxC = plugin.getMaxCaps(hull.getHullSize()), hullOp = hull.getOrdnancePoints(plugin.stats());
        for (int round = 0; round < 3; round++) {
            List<String> removable = new ArrayList<>();
            for (String id : current.getSMods()) {
                if (smodsBought.contains(id)) continue;
                // 0.5.60: hidden hullmods and never_remove rules stay, as in the strip
                HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
                if (spec == null || spec.isHidden() || spec.isHiddenEverywhere() || JAORatings.ruleMatches("never_remove", id)) continue;
                // 0.5.66: an s-mod of a complete set stays (jao_rules smod_set: the hull's script would drop the set's bonus)
                if (JAOCompat.inCompleteSet(id, hull.getBuiltInMods(), current.getSMods())) continue;
                removable.add(id);
            }
            if (removable.isEmpty() || current.getSMods().size() < full || changeCost + changePrice > changeCredits) return;
            Set<String> keep = new LinkedHashSet<>(current.getNonBuiltInHullmods());
            JAOShip asIsWorld = new JAOShip(current, delegate, plugin.stats(), hullOp - current.computeOPCost(plugin.stats()), maxV, maxC);
            float asIs = new JAOSMods(asIsWorld, p, slotsOf, stock, hullmodCandidates(keep), keep, 1).asIs();
            if (asIs <= 0f) return;
            String bestOff = null, bestOn = null;
            float bestScore = asIs * (1f + need);
            for (String off : removable) {
                ShipVariantAPI v = current.clone();
                v.removePermaMod(off);
                v.removeMod(off);
                // 0.5.60: the mount and bay list is the ship's as it is; an s-mod that adds or removes bays stays
                if (Global.getSettings().computeNumFighterBays(v) != Global.getSettings().computeNumFighterBays(current)) continue;
                Set<String> keepOff = new LinkedHashSet<>(v.getNonBuiltInHullmods());
                List<String> candsOff = hullmodCandidates(keepOff);
                JAOShip w = new JAOShip(v, delegate, plugin.stats(), hullOp - v.computeOPCost(plugin.stats()), maxV, maxC);
                JAOSMods sm = new JAOSMods(w, p, slotsOf, stock, candsOff, keepOff, 1);
                sm.minGain = JAOSettings.smodMinGain * weight;
                List<String> ons = new ArrayList<>();
                for (String id : smodCandidates(candsOff, keepOff, hull)) {
                    if (id.equals(off) || v.getSMods().contains(id) || smodsRemoved.contains(id)) continue;
                    Set<String> withOn = new LinkedHashSet<>(v.getSMods());
                    withOn.add(id);
                    if (!JAOCompat.smodLost(id, hull.getBuiltInMods(), withOn)) ons.add(id);
                }
                // with a new s-mod the change costs two (one off, one on); without, one. 0.5.60: a new one only while the
                // ship is at or under its size cap (s-mods already on count, as the setting says)
                boolean canBuy = changeCost + 2f * changePrice <= changeCredits && current.getSMods().size() <= full;
                JAOSMods.Pick pick = canBuy ? sm.best(ons) : null;
                String on = pick != null && !pick.mods.isEmpty() ? pick.mods.get(0) : null;
                float score = pick != null ? pick.score : sm.asIs();
                if (score > bestScore) { bestScore = score; bestOff = off; bestOn = on; }
            }
            if (bestOff == null) return;
            current.removePermaMod(bestOff);
            current.removeMod(bestOff);
            if (bestOn != null) current.addPermaMod(bestOn, true);
            smodsRemoved.add(bestOff);
            if (bestOn != null) smodsBought.add(bestOn);
            double cost = (bestOn != null ? 2.0 : 1.0) * changePrice;
            changeCost += cost;
            changeNotes.add("s-mod change: " + JAOSMods.name(bestOff) + (bestOn != null ? " to " + JAOSMods.name(bestOn) : " removed")
                    + " (+" + JAOSMods.pct(bestScore / asIs - 1f) + ", " + Misc.getDGSCredits((float) cost) + ")");
        }
    }

    /** In an Optimize fleet plan, whether the per-run cap (not the player's story points) is what's left at 0. */
    private boolean fleetCapUsed() {
        if (storyPoints < 0 || JAOSettings.smodFleetMax <= 0) return false;
        try { return Global.getSector().getPlayerStats().getStoryPoints() - JAOSettings.smodReserve > 0; }
        catch (Throwable t) { return false; }
    }

    /** A slot's weapon type as JAOModel.SLOT_* (ballistic, energy, hybrid; -1 other). */
    static int slotTypeOf(com.fs.starfarer.api.combat.WeaponAPI.WeaponType t) {
        if (t == null) return -1;
        switch (t) {
            case BALLISTIC: return JAOModel.SLOT_BAL;
            case ENERGY: return JAOModel.SLOT_ENE;
            case HYBRID: return JAOModel.SLOT_HYB;
            case MISSILE: return JAOModel.SLOT_MIS;
            default: return -1;
        }
    }

    /** D-mods on the variant that a restoration removes: every d-mod except those the hull itself builds in. */
    static List<String> removableDmods(ShipVariantAPI v) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new LinkedHashSet<>(v.getPermaMods());
        seen.addAll(v.getHullMods());
        for (String id : seen) {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (dmodRemovable(id, spec != null && spec.hasTag("dmod"), v.getHullSpec().getBuiltInMods())) out.add(id);
        }
        return out;
    }

    /** A d-mod a restoration removes: tagged dmod and not built into the hull. */
    static boolean dmodRemovable(String id, boolean dmodTag, java.util.Collection<String> hullBuiltIns) {
        // 0.5.97: d-mods that help the ship (Crab Shack super d-mods) are never repaired (jao_rules never_repair)
        return dmodTag && (hullBuiltIns == null || !hullBuiltIns.contains(id)) && !JAORatings.ruleMatches("never_repair", id);
    }

    /**
     * 0.5.97: the hull or the variant carries the game's no_autofit tag (Project Arondight, ED Shipyards, Khe's Tech,
     * the Omega hulls). The game's fleet autofit leaves these alone, and Optimize fleet does too; the Autofit button
     * still works on them.
     */
    static boolean noAutofit(FleetMemberAPI m) {
        try {
            String t = com.fs.starfarer.api.impl.campaign.ids.Tags.TAG_NO_AUTOFIT;
            return m != null && ((m.getHullSpec() != null && m.getHullSpec().hasTag(t)) || (m.getVariant() != null && m.getVariant().hasTag(t)));
        } catch (Throwable t) {
            return false;
        }
    }

    /** A scratch world for the ship as a restoration would leave it (the removable d-mods taken off a copy). */
    private JAOShip restored(List<String> dmods, int budget, ShipHullSpecAPI hull) {
        ShipVariantAPI v = current.clone();
        for (String id : dmods) { v.removePermaMod(id); v.removeMod(id); }
        return new JAOShip(v, delegate, plugin.stats(), budget, plugin.getMaxVents(hull.getHullSize()), plugin.getMaxCaps(hull.getHullSize()));
    }

    /** New s-mods one ship may get: its free s-mod slots, the story points above the reserve, the per-fit setting. */
    static int smodLimit(int room, int storyPoints, int reserve, int perFit) {
        return Math.max(0, Math.min(Math.min(room, storyPoints - reserve), perFit));
    }

    /**
     * Builds the planned s-mods in. Preview: added to the dialog's copy so the preview shows them, nothing spent.
     * Confirm: one story point each through the same path as the refit screen's own build-in (bonus XP, playthrough
     * log), then added. If the story points can't be spent, they go on as ordinary hullmods. Returns how many are s-mods.
     */
    private int buildIn(Set<String> smods, boolean real, ShipHullSpecAPI hull) {
        if (!real) {
            for (String id : smods) current.addPermaMod(id, true);
            return smods.size();
        }
        MutableCharacterStatsAPI ps = Global.getSector().getPlayerStats();
        int n = smods.size();
        int had = ps.getStoryPoints();
        if (had - JAOSettings.smodReserve < n) {
            for (String id : smods) if (!current.hasHullMod(id)) current.addMod(id);
            return 0;
        }
        float xp = 0f;
        List<String> names = new ArrayList<>();
        for (String id : smods) {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            xp += Misc.getBuildInBonusXP(spec, hull.getHullSize());
            names.add(spec.getDisplayName());
        }
        ps.spendStoryPoints(n, true, null, true, xp / n, "Built " + Misc.getAndJoined(names) + " into " + shipName() + (module ? "'s " + hull.getHullName() : "")
                + " (" + hull.getNameWithDesignationWithDashClass() + ")");
        if (ps.getStoryPoints() > had - n) {
            Global.getLogger(JAOOptimizer.class).error("Auto Outfitting: story points weren't spent for " + shipName() + "; " + names + " added as ordinary hullmods");
            for (String id : smods) if (!current.hasHullMod(id)) current.addMod(id);
            return 0;
        }
        for (String id : smods) {
            current.addPermaMod(id, true);
            SModRecord rec = new SModRecord(delegate.getFleetMember());
            rec.getSMods().add(id);
            rec.setBonusXPFractionGained(Misc.getBuildInBonusXP(Global.getSettings().getHullModSpec(id), hull.getHullSize()));
            rec.setSPSpent(1);
            PlaythroughLog.getInstance().getSModsInstalled().add(rec);
        }
        return n;
    }

    private String shipName() {
        FleetMemberAPI m = delegate.getFleetMember();
        return m == null || m.getShipName() == null ? current.getHullSpec().getHullName() : m.getShipName();
    }

    /** A fresh slot list (weapons by slot, wings by bay), so the s-mod planner can run several searches. */
    private List<JAOSearch.Slot> buildSlots(ShipHullSpecAPI hull, Set<String> skip, int bays, int builtInWings,
                                            List<AvailableWeapon> weapons, List<AvailableFighter> fighters) {
        boolean fragments = current.hasHullMod("fragment_swarm");
        // 0.5.97: weapon sizes that need the PD hint while a hullmod is on (jao_rules pd_only_size)
        Set<String> pdOnly = JAOCompat.pdOnlySizes(current.getHullMods(), JAORatings.rules());
        List<JAOSearch.Slot> slots = new ArrayList<>();
        // degrees from the bow to each weapon mount's arc, for how much of its fire reaches the main target
        Map<JAOSearch.Slot, Float> offs = new java.util.IdentityHashMap<>();
        // built-in weapons fire too: they set the engagement range and share the flux budget (fixed, no OP)
        for (WeaponSlotAPI slot : hull.getAllWeaponSlotsCopy()) {
            if (mountable(slot) || slot.isDecorative() || slot.isStationModule()) continue;
            WeaponSpecAPI spec = current.getWeaponSpec(slot.getId());
            if (spec == null || spec.getType() == WeaponType.DECORATIVE) continue;
            JAOSearch.Slot js = new JAOSearch.Slot(slot.getId(), slot.getSlotSize().ordinal(), slot.isHardpoint(),
                    Math.max(0.05f, Math.min(360f, slot.getArc()) / 360f), Misc.isInArc(slot.getAngle(), slot.getArc(), 0f), false);
            js.slotType = slotTypeOf(slot.getWeaponType());
            js.fixed = new JAOSearch.Cand(JAORatings.weapon(spec), null, "builtin", 0f);
            slots.add(js);
            offs.put(js, Misc.getDistanceFromArc(slot.getAngle(), slot.getArc(), 0f));
        }
        for (WeaponSlotAPI slot : hull.getAllWeaponSlotsCopy()) {
            if (!mountable(slot)) continue;
            WeaponSlotAPI s = current.getSlot(slot.getId());
            if (s == null) continue;
            if (skip.contains(s.getId())) {
                // slot owned by mod code (jao_rules skip_slots): its weapon counts, but we never touch it
                WeaponSpecAPI owned = current.getWeaponSpec(s.getId());
                if (owned != null) {
                    JAOSearch.Slot js = new JAOSearch.Slot(s.getId(), s.getSlotSize().ordinal(), s.isHardpoint(), Math.max(0.05f, Math.min(360f, s.getArc()) / 360f), Misc.isInArc(s.getAngle(), s.getArc(), 0f), false);
                    js.slotType = slotTypeOf(s.getWeaponType());
                    js.fixed = new JAOSearch.Cand(JAORatings.weapon(owned), null, "owned", 0f);
                    slots.add(js);
                    offs.put(js, Misc.getDistanceFromArc(s.getAngle(), s.getArc(), 0f));
                }
                continue;
            }
            float arc = Math.max(0f, Math.min(360f, s.getArc()));
            boolean front = Misc.isInArc(s.getAngle(), s.getArc(), 0f);
            JAOSearch.Slot js = new JAOSearch.Slot(s.getId(), s.getSlotSize().ordinal(), s.isHardpoint(), Math.max(0.05f, arc / 360f), front, false);
            js.slotType = slotTypeOf(s.getWeaponType());
            WeaponSpecAPI kept = current.getWeaponSpec(s.getId());
            if (kept != null) {
                js.fixed = new JAOSearch.Cand(JAORatings.weapon(kept), null, "kept", 0f);
            } else {
                float offAxis = Misc.getDistanceFromArc(s.getAngle(), s.getArc(), 0f);
                for (int i = 0; i < weapons.size(); i++) {
                    AvailableWeapon w = weapons.get(i);
                    WeaponSpecAPI spec = w.getSpec();
                    if (spec == null || w.getQuantity() <= 0 || !s.weaponFits(spec) || spec.getAIHints().contains(AIHints.SYSTEM)) continue;
                    // a weapon priced at 0 OP is some hull's built-in or a fighter's gun (IX cutting beam, Yunru strobe):
                    // free in an ordinary mount it beat every real weapon (0.5.6, the all-weapons run)
                    if (spec.getOrdnancePointCost(null) <= 0f) continue;
                    // console-only gear (no blueprint, no faction, no ship carries it) unless the setting allows it
                    if (!JAOSettings.cheatItems && JAOCompat.cheatOnly(spec)) continue;
                    JAOModel.W m = JAORatings.weapon(spec);
                    // restricted weapons aren't bought from markets; a known blueprint can still build them
                    if (m.ownedOnly && w.getPrice() > 0f && (!planning || w.getSubmarket() != null)) continue;
                    if (spec.hasTag("fragment") && !fragments) continue;
                    if (!pdOnly.isEmpty() && spec.getSize() != null && pdOnly.contains(spec.getSize().name()) && !spec.getAIHints().contains(AIHints.PD)) continue;
                    if (skipped(spec.getWeaponId())) continue;
                    // unguided missiles and strike weapons need to face the bow (CoreAutofitPlugin.getPossibleWeapons)
                    if ((spec.getType() == WeaponType.MISSILE || spec.getAIHints().contains(AIHints.STRIKE)) && !spec.getAIHints().contains(AIHints.DO_NOT_AIM)) {
                        if (offAxis > 45f || (!spec.getAIHints().contains(AIHints.GUIDED_POOR) && offAxis > 20f)) continue;
                    }
                    JAOSearch.Cand c = new JAOSearch.Cand(m, w, "w" + i, w.getPrice());
                    c.priority = priority(spec.getWeaponId(), false);
                    js.cands.add(c);
                }
            }
            slots.add(js);
            offs.put(js, Misc.getDistanceFromArc(s.getAngle(), s.getArc(), 0f));
        }
        applyBearing(slots, offs);
        // bay rules (JAOCompat): a Guru flies only Seeker's plague LPCs, a Trinity Worlds ship only its Nimbus drones;
        // wings only scripts place are never taken from cargo or storage
        List<String> allow = bays > builtInWings ? JAOCompat.bayWings(current) : null;
        // a built-in hullmod that turns crewed wings into a malfunction (AI Subsystem Integration): drones only (0.5.36)
        boolean dronesOnly = bays > builtInWings && JAOCompat.dronesOnly(current);
        // built-in wings fly too (0.5.47, build audit): their point defense and role count in the score and the fleet's
        // wing balance; fixed, never refitted
        for (int b = 0; b < builtInWings && b < bays; b++) {
            try {
                String id = hull.getBuiltInWings().get(b);
                com.fs.starfarer.api.loading.FighterWingSpecAPI spec = id == null ? null : Global.getSettings().getFighterWingSpec(id);
                if (spec == null) continue;
                JAOSearch.Slot fixed = new JAOSearch.Slot("builtinbay" + b, 1, false, 1f, true, true);
                fixed.fixed = new JAOSearch.Cand(JAORatings.wing(spec), null, "builtin", 0f);
                slots.add(fixed);
            } catch (Throwable ignored) { }
        }
        for (int b = builtInWings; b < bays; b++) {
            JAOSearch.Slot js = new JAOSearch.Slot("bay" + b, 1, false, 1f, true, true);
            String kept = current.getWingId(b);
            if (kept != null && Global.getSettings().getFighterWingSpec(kept) != null) {
                js.fixed = new JAOSearch.Cand(JAORatings.wing(Global.getSettings().getFighterWingSpec(kept)), null, "kept", 0f);
            } else {
                for (int i = 0; i < fighters.size(); i++) {
                    AvailableFighter f = fighters.get(i);
                    if (f.getWingSpec() == null || f.getQuantity() <= 0) continue;
                    if (JAOCompat.boundWing(f.getWingSpec()) || !JAOCompat.wingAllowed(allow, f.getId())) continue;
                    if (dronesOnly && !JAOCompat.uncrewed(f.getWingSpec())) continue;
                    if (!JAOSettings.cheatItems && JAOCompat.cheatOnly(f.getWingSpec())) continue;
                    JAOSearch.Cand c = new JAOSearch.Cand(JAORatings.wing(f.getWingSpec()), f, "f" + i, f.getPrice());
                    c.priority = priority(f.getId(), true);
                    js.cands.add(c);
                }
            }
            slots.add(js);
        }
        return slots;
    }

    /**
     * Marked as priority in the player faction's doctrine, with "Respect priority weapons" on (design 4): such a weapon or
     * wing wins when it scores within JAOSearch.PRIORITY_MARGIN of the best other choice. The game's own autofit
     * delegate reports nothing as priority for the player.
     */
    static boolean priority(String id, boolean wing) {
        if (!JAOSettings.keepPriority || id == null) return false;
        try {
            com.fs.starfarer.api.campaign.FactionAPI f = Global.getSector().getPlayerFaction();
            return wing ? f.isFighterPriority(id) : f.isWeaponPriority(id);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sets each weapon slot's bearing (JAOModel.bearing) from its degrees off the bow, weighted by size 1/2/4. */
    static void applyBearing(List<JAOSearch.Slot> slots, Map<JAOSearch.Slot, Float> offs) {
        List<JAOSearch.Slot> ws = new ArrayList<>();
        for (JAOSearch.Slot s : slots) if (!s.bay && offs.containsKey(s)) ws.add(s);
        float[] off = new float[ws.size()], wt = new float[ws.size()];
        for (int i = 0; i < ws.size(); i++) {
            off[i] = offs.get(ws.get(i));
            int sz = Math.max(0, Math.min(2, ws.get(i).size));
            wt[i] = sz == 0 ? 1f : sz == 1 ? 2f : 4f;
        }
        float[] b = JAOModel.bearing(off, wt);
        for (int i = 0; i < ws.size(); i++) ws.get(i).bear = b[i];
    }

    /** Each module's own slots with the same style; a ship's modules get no hullmods (the game's autofit does the same), a station's do (0.5.90). */
    private void fitModules() {
        int index = 0;
        for (String slotId : new ArrayList<>(current.getStationModules().keySet())) {
            ShipVariantAPI m = current.getModuleVariant(slotId);
            if (m == null) continue;
            // 0.5.79: a module the ship doesn't hold a copy of is the game's own stock variant, one object shared by every
            // ship and station with that module. Fitting it in place changed it for all of them, and the game's fleet
            // inflater then stripped it (user 11/23: star fortress modules had no weapons in battle). It gets a copy.
            boolean shared = sharedStock(m);
            if (shared) restoreStock(m);
            // planning always works on copies: the real ship's modules must never be touched
            if (m.isStockVariant() || planning || shared) {
                m = m.clone();
                m.setSource(VariantSource.REFIT);
                m.setHullVariantId(m.getHullVariantId() + "_" + index);
            }
            index++;
            // 0.5.94: Change s-mods takes the module's Shrouded s-mods off (planning works on a copy)
            if (dropShrouded() && m != current.getModuleVariant(slotId)) dropShroudedSmods(m, m.getHullSpec().getHullName());
            JAOOptimizer mo = new JAOOptimizer(plugin, m, delegate, style, true, planning);
            mo.buying = buying;
            mo.stationParent = stationFit();
            mo.parentFit = this;
            mo.augments.addAll(augments);
            mo.parentSize = current.getHullSpec().getHullSize();
            mo.moduleCount = current.getStationModules().size();
            mo.planSmods = planSmods;
            mo.run();
            moduleFits.add(mo);
            // planning fits a copy of the ship, so its module copies go into that copy (the plan carries them)
            current.setModuleVariant(slotId, m);
        }
    }

    /**
     * 0.5.79: the game's own copy of a stock variant (SettingsAPI.getVariant and getModuleVariant hand out the same
     * object). Changing it changes that module on every ship and station that uses it.
     */
    static boolean sharedStock(ShipVariantAPI v) {
        if (v == null || v.getHullVariantId() == null) return false;
        try {
            return Global.getSettings().doesVariantExist(v.getHullVariantId()) && Global.getSettings().getVariant(v.getHullVariantId()) == v;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 0.5.79: restoreStock on each module of the variant. */
    /** 0.5.94: a Shrouded hullmod (Mantle, Lens, Thunderhead: the game's "shrouded" tag). */
    static boolean shrouded(String id) {
        try {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            return spec != null && spec.hasTag("shrouded");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 0.5.94: Optimize fleet takes Shrouded s-mods off with Change s-mods while Shrouded hullmods are off. */
    private boolean dropShrouded() {
        return !module && planning && fullPlan && changeSmods && !JAOSettings.shroudedHullmods;
    }

    /**
     * 0.5.94: the Shrouded s-mods on this variant (the ship or a module's plan copy) come off with the game's Change
     * s-mods, one change each at the variant's own hull size price (the game prices the selected module by its size),
     * before any other change and whatever they're worth, while the credits last.
     */
    private void dropShroudedSmods(ShipVariantAPI v, String moduleName) {
        float price = JAOFleetOptions.changePrice(v.getHullSpec().getHullSize());
        if (price <= 0f) return;
        for (String id : new ArrayList<>(v.getSMods())) {
            if (!shrouded(id) || v.getHullSpec().getBuiltInMods().contains(id)) continue;
            if (changeCost + price > changeCredits) return;
            v.removePermaMod(id);
            v.removeMod(id);
            if (v == current) smodsRemoved.add(id);
            changeCost += price;
            changeNotes.add("s-mod change: " + (moduleName != null ? moduleName + ": " : "") + JAOSMods.name(id)
                    + " removed (Shrouded hullmods are off, " + Misc.getDGSCredits(price) + ")");
        }
    }

    static void restoreModules(ShipVariantAPI v) {
        try {
            if (v == null) return;
            for (String slotId : new ArrayList<>(v.getStationModules().keySet())) restoreStock(v.getModuleVariant(slotId));
        } catch (Throwable t) {
            Global.getLogger(JAOOptimizer.class).warn("Auto Outfitting: couldn't check the module variants of " + v.getHullVariantId(), t);
        }
    }

    private static final Set<String> restoredStock = new java.util.HashSet<>();

    /**
     * 0.5.79: a shared stock variant marked as a refit goes back to stock. The game's fleet inflater copies only stock
     * module variants before it fits a station; a shared one marked as a refit is stripped and fitted from itself, which
     * leaves that module empty on every station that uses it (Auto Colonies 0.3.15.4 marks them before its station fits).
     */
    static void restoreStock(ShipVariantAPI v) {
        if (!sharedStock(v) || v.getSource() != VariantSource.REFIT) return;
        v.setSource(VariantSource.STOCK);
        if (restoredStock.add(v.getHullVariantId())) Global.getLogger(JAOOptimizer.class).warn("Auto Outfitting: "
                + v.getHullVariantId() + " is the game's shared stock variant and was marked as a refit; set back to stock");
    }

    /** jao_rules never_use rows and the "Never use these weapons" setting. */
    static boolean skipped(String weaponId) {
        for (String p : JAOSettings.skipWeapons) if (JAORatings.glob(p, weaponId)) return true;
        return JAORatings.ruleMatches("never_use", weaponId);
    }

    static boolean mountable(WeaponSlotAPI s) {
        return !(s.isBuiltIn() || s.isDecorative() || s.isHidden() || s.isSystemSlot() || s.isStationModule()) && s.isWeaponSlot();
    }

    /** Known hullmods worth trying: combat value per jao_hullmods.csv, or unknown (measured); minus rules and settings. */
    private List<String> hullmodCandidates(Set<String> keep) {
        Set<String> out = new TreeSet<>();
        for (String id : delegate.getAvailableHullmods()) if (allowedHere(id, keep)) out.add(id);
        return new ArrayList<>(out);
    }

    /**
     * Every per-hull check of hullmodCandidates, known or not (0.5.101: the s-mod wait list uses them too; it held slots
     * for hullmods the rules keep off this hull).
     */
    boolean allowedHere(String id, Set<String> keep) {
        // 0.5.60: the ship's own s-mods aren't candidates (getNonBuiltInHullmods leaves them out of keep; adding one
        // changed nothing but booked its OP)
        if (keep.contains(id) || current.getHullSpec().getBuiltInMods().contains(id) || current.getPermaMods().contains(id)) return false;
        HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
        if (spec == null || spec.isHidden() || spec.isHiddenEverywhere() || spec.hasTag("dmod")) return false;
        if (JAOCompat.neverAdd(id) || (!stationFit() && JAOCompat.tooSmall(id, current.getHullSize())) || JAOCompat.notOnPhase(id, current.getHullSpec())
                || JAOCompat.removedByHull(id, current.getHullSpec()) || JAOCompat.missingNeed(id, current.getHullSpec())) return false;
        if (stationFit() && !stationHullmod(id, spec.hasUITag("Logistics"), spec.hasUITag("Engines"))) return false;
        if (spec.hasUITag("Logistics") && !JAOSettings.logistics) return false;
        if (id.equals(HullMods.SAFETYOVERRIDES) && (JAOSettings.safetyOverrides == 0
                || (JAOSettings.safetyOverrides == 1 && current.getHullSize().ordinal() > HullSize.DESTROYER.ordinal()))) return false;
        JSONObject row = JAORatings.hullmods().get(id);
        if (row != null) {
            if (!"TRUE".equalsIgnoreCase(row.optString("combat", "TRUE"))) return false;
            String how = row.optString("how", "stats");
            if ("listener".equals(how) && !JAOShip.modelled(id)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ civilian ships

    /** Freighters, tankers, liners, salvage and survey ships: hulls that never fight (design 7h). */
    static boolean civilian(ShipHullSpecAPI hull) {
        // 0.5.66: jao_rules warship rows: hulls with a civilian hint that do a combat job (Navarchy of Tania's Wader: its
        // ground support grows with its wing and hullmods, so the logistics fit would take its job away)
        if (JAORatings.ruleMatches("warship", hull.getHullId()) || JAORatings.ruleMatches("warship", hull.getBaseHullId())) return false;
        if (hull.isCivilianNonCarrier()) return true;
        String d = hull.getDesignation() == null ? "" : hull.getDesignation().toLowerCase(java.util.Locale.ROOT);
        // armed conversions (Prometheus Mk II "Modified Tanker", Colossus Mk II/III, "Combat Freighter") are warships
        if (hull.getHints().contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.COMBAT) || hull.getFighterBays() > 0
                || d.contains("modified") || d.contains("combat")) return false;
        return d.contains("salvage") || d.contains("survey") || d.contains("tanker") || d.contains("liner")
                || d.contains("freighter") || d.contains("transport");
    }

    /**
     * Campaign hullmods in order of value for this kind of civilian ship. Freighters want cargo, tankers fuel, liners
     * berths, salvage and survey rigs their gear; all of them then treasure hunting, burn speed, sensor profile,
     * sensors, supply use and corona protection. Hullmods from mods the list doesn't know come after it.
     */
    static List<String> logisticsOrder(ShipHullSpecAPI hull) {
        java.util.EnumSet<com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints> h = hull.getHints();
        List<String> out = new ArrayList<>();
        if (salvageRig(hull)) {
            out.add("surveying_equipment");
            out.add(TREASURE_HUNT);
        } else if (h.contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.TANKER) || hull.getFuel() > hull.getCargo()) {
            out.add("auxiliary_fuel_tanks");
        } else if (h.contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.LINER)
                || h.contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.TRANSPORT) || hull.getMaxCrew() > hull.getCargo()) {
            out.add("additional_berthing");
        } else {
            out.add("expanded_cargo_holds");
        }
        // Treasure Hunt Package right after the ship's own job (user 11/06: getting the nanoforges and other colony items
        // quickly is very valuable; it adds up across the fleet)
        for (String id : new String[]{TREASURE_HUNT, "augmentedengines", "adf_AttunedEngines", "stardust_EnhancedEngines", "IndEvo_augfield",
                "insulatedengine", "hiressensors", "surveying_equipment",
                "efficiency_overhaul", "solar_shielding", "stardust_EnvironmentalShielding", "militarized_subsystems"}) {
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    static final String TREASURE_HUNT = "spinloki.TreasureHunt.data.hullmods.THTreasureHuntPackage";

    /**
     * Fleet-wide campaign hullmods, best first: what civilian ships that stay in the fleet build in (user 11/06: "I would
     * just build for S-mods that are fleet-wide augments"). Treasure Hunt Package (1/2/4/8 by hull size) and Surveying
     * Equipment (5/10/20/40) add up across the whole fleet and double as s-mods; High Resolution Sensors adds to the
     * fleet's sensor range with diminishing returns (built in, it frees a logistics slot); then burn speed, since the
     * fleet moves at its slowest ship, which is usually a freighter.
     */
    static final String[] FLEET_WIDE = {TREASURE_HUNT, "surveying_equipment", "hiressensors",
            "augmentedengines", "adf_AttunedEngines", "stardust_EnhancedEngines", "yunru_drive_optimisation"};

    /**
     * What a civilian ship's OP buys once the logistics limit is reached (0.5.18, user 11/06: "a massive overflow of
     * points"): the only hullmods outside the limit that matter to a ship that never fights. Reinforced Bulkheads (more
     * hull, and a disabled ship is almost always recoverable, so an Atlas with s-mods isn't lost), then Blast Doors (more
     * hull, fewer crew lost). Vents and capacitors stay off.
     */
    static final String[] SURVIVAL = {"reinforcedhull", "blast_doors"};

    /** {vents, capacitors} for OP nothing else on a civilian ship uses: alternating, vents first, up to the hull's limits. */
    static int[] civilianFlux(int left, int maxVents, int maxCaps) {
        int v = 0, c = 0;
        while (left > 0 && (v < maxVents || c < maxCaps)) {
            if (v < maxVents && (v <= c || c >= maxCaps)) v++; else c++;
            left--;
        }
        return new int[]{v, c};
    }

    /** Salvage and survey ships (Salvage Rig: designation, or the built-in Salvage Gantry). */
    static boolean salvageRig(ShipHullSpecAPI hull) {
        String d = hull.getDesignation() == null ? "" : hull.getDesignation().toLowerCase(java.util.Locale.ROOT);
        return d.contains("salvage") || d.contains("survey") || hull.getBuiltInMods().contains("repair_gantry");
    }

    /**
     * Civilian ships that stay in the fleet for good and are worth story points: cruisers and capitals (Atlas, Prometheus,
     * Colossus, Phaeton) and salvage or survey rigs of any size (user 11/06).
     */
    static boolean keeper(ShipHullSpecAPI hull) {
        return hull.getHullSize().ordinal() >= HullSize.CRUISER.ordinal() || salvageRig(hull);
    }

    /**
     * Civilian fit: no weapons or wings, campaign hullmods in logisticsOrder up to the game's logistics limit
     * (maxLogisticsHullmods, built-in and s-modded ones don't count). With "Build in s-mods" ticked and the civilian
     * s-mods setting on, keepers (cruisers, capitals, salvage rigs) first build in the fleet-wide ones (FLEET_WIDE; story
     * points on Confirm only), which leaves the ordinary slots for the ship's own job. Logistics hullmods need a
     * spaceport, so away from one only the existing ones stay. The OP the logistics limit leaves goes to SURVIVAL
     * (Reinforced Bulkheads, Blast Doors); what is still left goes to vents and capacitors (0.5.48), never before.
     */
    private void runLogistics(ShipHullSpecAPI hull, long t0) {
        // the fleet plan refits from bare like every other ship (only hullmods the refit rules here allow come off)
        boolean strip = planning || plugin.option(CoreAutofitPlugin.STRIP);
        int bays = Global.getSettings().computeNumFighterBays(current);
        int builtInWings = hull.getBuiltInWings() == null ? 0 : hull.getBuiltInWings().size();
        for (String id : new ArrayList<>(current.getFittedWeaponSlots())) {
            WeaponSlotAPI slot = current.getSlot(id);
            if (slot != null && mountable(slot)) plugin.clearWeaponSlot(slot, delegate, current);
        }
        for (int i = builtInWings; i < bays; i++) {
            String w = current.getWingId(i);
            if (w != null && !JAOCompat.boundWing(w)) plugin.clearFighterSlot(i, delegate, current);
        }
        if (strip) {
            for (String id : new ArrayList<>(current.getNonBuiltInHullmods())) {
                HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
                if (spec == null || spec.isHidden() || spec.isHiddenEverywhere() || JAOCompat.neverRemove(id, hull.getHullSize())) continue;
                if (current.getPermaMods().contains(id)) continue; // s-mods stay
                if (delegate.isPlayerCampaignRefit() && !delegate.canAddRemoveHullmodInPlayerCampaignRefit(id)) continue;
                current.removeMod(id);
            }
        }
        current.setNumFluxVents(0);
        current.setNumFluxCapacitors(0);
        ShipAPI before = delegate.getShip();
        delegate.syncUIWithVariant(current);
        ShipAPI after = delegate.getShip();
        boolean real = after != before || (after != null && after.getVariant() == current);

        int hullOp = hull.getOrdnancePoints(plugin.stats());
        JAOShip world = new JAOShip(current, delegate, plugin.stats(), 0, 0, 0);
        Set<String> available = new java.util.HashSet<>(delegate.getAvailableHullmods());
        List<String> order = logisticsOrder(hull);
        for (String id : new TreeSet<>(available)) {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (spec != null && spec.hasUITag("Logistics") && !order.contains(id) && !id.equals("converted_fighterbay")) order.add(id);
        }
        int limit = JAOShip.maxLogistics(delegate.getFleetMember());
        // s-mods (user 11/06): with "Build in s-mods" ticked and the civilian s-mods setting on, keepers build in the
        // fleet-wide hullmods, in a single refit and in Optimize fleet alike (story points only on Confirm)
        boolean smodsOn = JAOFleetOptions.smodsCivilian() && (planning ? planSmods : plugin.option(JAOAutofitPlugin.SMODS));
        int smodRoom = smodsOn && keeper(hull) ? smodLimit(new int[4]) : 0;
        dropCustomAi(hull);
        Set<String> mods = new LinkedHashSet<>(current.getNonBuiltInHullmods());
        Set<String> smods = new LinkedHashSet<>();
        List<String> skipped = new ArrayList<>(), enough = new ArrayList<>();
        // utility hullmods whose fleet-wide effect stops adding up go on only while the rest of the fleet needs them
        // (0.5.31, user: "watch out that we don't over-equip surveying packages"; JAOUtility)
        JAOUtility.Others others = utilityOthers != null ? utilityOthers : JAOUtility.ofFleet(delegate.getFleetMember());
        for (String id : FLEET_WIDE) {
            if (smods.size() >= smodRoom) break;
            if (!available.contains(id) || hull.getBuiltInMods().contains(id) || JAOCompat.neverAdd(id) || JAOCompat.removedByHull(id, hull)) continue;
            if (current.getSMods().contains(id) || current.getPermaMods().contains(id)) continue;
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (spec == null || spec.isHidden() || spec.hasTag(com.fs.starfarer.api.impl.campaign.ids.Tags.HULLMOD_NO_BUILD_IN)) continue;
            world.setSMods(smods);
            // one already fitted as an ordinary hullmod is simply built in
            if (!mods.contains(id) && !logisticsAllowed(world, id, mods, skipped)) continue;
            if (!fleetNeeds(world, mods, smods, id, true, others, enough)) continue;
            mods.add(id);
            smods.add(id);
        }
        // the ship's own job and the rest of logisticsOrder, then (user 11/06: "a massive overflow of points") hullmods
        // that keep a ship that never fights from being lost when it's caught, with the OP the logistics limit leaves
        List<String> picks = new ArrayList<>(order);
        for (String id : SURVIVAL) if (!picks.contains(id)) picks.add(id);
        for (String id : picks) {
            if (!available.contains(id) || mods.contains(id) || hull.getBuiltInMods().contains(id) || JAOCompat.neverAdd(id)
                    || JAOCompat.removedByHull(id, hull)) continue;
            if (current.getPermaMods().contains(id)) continue;
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (spec == null || spec.isHidden()) continue;
            boolean logi = spec.hasUITag("Logistics");
            int counted = 0;
            for (String m : mods) {
                HullModSpecAPI o = Global.getSettings().getHullModSpec(m);
                if (o != null && o.hasUITag("Logistics") && !smods.contains(m) && !current.getPermaMods().contains(m)) counted++;
            }
            if (logi && counted >= limit) continue;
            int cost = spec.getCostFor(hull.getHullSize());
            int used = current.computeOPCost(plugin.stats());
            for (String m : mods) if (!current.hasHullMod(m) && !smods.contains(m)) used += Global.getSettings().getHullModSpec(m).getCostFor(hull.getHullSize());
            if (used + cost > hullOp) continue;
            world.setSMods(smods);
            if (!logisticsAllowed(world, id, mods, skipped)) continue;
            if (!fleetNeeds(world, mods, smods, id, false, others, enough)) continue;
            mods.add(id);
        }
        // 0.5.64: AI Tweaks' Custom AI on civilian capitals too (0 OP, not a logistics hullmod)
        if (JAOCompat.customAiWanted(hull.getHullSize(), available) && !mods.contains(JAOCompat.CUSTOM_AI)
                && world.canAdd(JAOCompat.CUSTOM_AI, mods)) mods.add(JAOCompat.CUSTOM_AI);
        for (String m : mods) if (!smods.contains(m) && !current.hasHullMod(m)) current.addMod(m);
        int built = smods.isEmpty() ? 0 : buildIn(smods, real, hull);
        // 0.5.60: s-mods that couldn't be built went on as ordinary hullmods with OP the plan didn't count; they come off
        // until the ship fits
        for (String id : smods) {
            if (current.computeOPCost(plugin.stats()) <= hullOp) break;
            if (!current.getPermaMods().contains(id) && current.getNonBuiltInHullmods().contains(id)) current.removeMod(id);
        }
        // vents and capacitors only with OP nothing else can use (0.5.48, user 11/14: "only if there is nowhere else to
        // put it"; 11/06: no flux on a ship that never fights): after every logistics and survival hullmod above
        int[] flux = civilianFlux(hullOp - current.computeOPCost(plugin.stats()), plugin.getMaxVents(hull.getHullSize()), plugin.getMaxCaps(hull.getHullSize()));
        current.setNumFluxVents(flux[0]);
        current.setNumFluxCapacitors(flux[1]);
        current.getWeaponGroups().clear();
        // 0.5.101: built-in weapons get their groups back (run() turned auto-assign off)
        current.setMayAutoAssignWeapons(true);
        current.autoGenerateWeaponGroups();
        current.setSource(VariantSource.REFIT);
        boolean docked = false;
        for (String id : available) { HullModSpecAPI s = Global.getSettings().getHullModSpec(id); if (s != null && s.hasUITag("Logistics")) { docked = true; break; } }
        current.setVariantDisplayName(JAOGoals.PREFIX + ": Logistics" + (built > 0 ? " +" + built + " s-mod" + (built > 1 ? "s" : "") : "")
                + (docked ? "" : " (dock at a spaceport to add logistics hullmods)"));
        delegate.syncUIWithVariant(current);
        if (real || JAOSettings.debug) {
            Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + shipName() + " (civilian) logistics fit: " + mods
                    + (smods.isEmpty() ? "" : ", built in " + smods + (built > 0 ? "" : " (not built: preview or no story points)"))
                    + (skipped.isEmpty() ? "" : "; skipped for higher supply/fuel cost: " + skipped)
                    + (enough.isEmpty() ? "" : "; the fleet has enough of " + enough + " (" + JAOUtility.describe(others) + ")") + " in " + (System.nanoTime() - t0) / 1_000_000L + " ms");
        }
    }

    /**
     * Whether the fleet still needs what id adds on this ship (JAOUtility: survey, Treasure Hunt, sensors, burn), as an
     * s-mod when asSmod. Hullmods that add none of those always pass. A miss goes into enough for the fit log.
     */
    private boolean fleetNeeds(JAOShip world, Set<String> mods, Set<String> smods, String id, boolean asSmod, JAOUtility.Others others, List<String> enough) {
        Set<String> m2 = new LinkedHashSet<>(mods);
        m2.add(id);
        Set<String> s2 = new LinkedHashSet<>(smods);
        if (asSmod) s2.add(id);
        float[] base = world.utility(mods, smods), with = world.utility(m2, s2);
        world.setSMods(smods);
        if (JAOUtility.useful(base, with, others, JAOUtility.surveyTarget(), JAOUtility.treasureTarget())) return true;
        enough.add(id + " (" + JAOUtility.what(base, with) + ")");
        return false;
    }

    /** Applicability like any hullmod; with drawbacks off, also no higher supply, recovery, deployment or fuel cost. */
    private boolean logisticsAllowed(JAOShip world, String id, Set<String> mods, List<String> skipped) {
        HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
        if (JAOShip.phaseCloak(hull()) && spec.hasTag(com.fs.starfarer.api.impl.campaign.ids.HullMods.TAG_NON_PHASE)) return false;
        if (delegate.isPlayerCampaignRefit() && !delegate.canAddRemoveHullmodInPlayerCampaignRefit(id)) return false;
        boolean drawbacks = JAOSettings.allowDrawbacks;
        JAOSettings.allowDrawbacks = true;   // combat drawbacks don't matter on a ship that never fights
        boolean ok;
        try { ok = world.canAdd(id, mods); } finally { JAOSettings.allowDrawbacks = drawbacks; }
        if (!ok) return false;
        if (!drawbacks) {
            String why = world.drawback(id, mods);
            if (why != null && (why.startsWith("supplies") || why.equals("deployment points") || why.equals("fuel use"))) { skipped.add(id + " (" + why + ")"); return false; }
        }
        return true;
    }

    private ShipHullSpecAPI hull() { return current.getHullSpec(); }

    /** Known fleet-level hullmods (no single-ship value): ECM, Nav Relay, Escort Package; Operations Center on the flagship. */
    private List<String> fleetHullmods(Set<String> keep) {
        List<String> out = new ArrayList<>();
        boolean flagship = delegate.getFleetMember() != null && delegate.getFleetMember().isFlagship();
        for (String id : new TreeSet<>(delegate.getAvailableHullmods())) {
            if (keep.contains(id) || current.getHullSpec().getBuiltInMods().contains(id) || current.getPermaMods().contains(id)) continue;
            boolean fleet = id.equals("ecm") || id.equals("nav_relay") || id.equals("escort_package") || (flagship && id.equals("operations_center"));
            if (!fleet || JAOCompat.neverAdd(id) || JAOCompat.removedByHull(id, current.getHullSpec())) continue;
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (spec == null || spec.isHidden() || (spec.hasUITag("Logistics") && !JAOSettings.logistics)) continue;
            out.add(id);
        }
        return out;
    }

    /** Auto: missile boats with strike missiles go Anti-armor, everything else Balanced (design 7). */
    static JAOStyle resolve(JAOStyle style, ShipHullSpecAPI hull) {
        if (style != JAOStyle.AUTO) return style;
        float missile = 0f, all = 0f;
        for (WeaponSlotAPI s : hull.getAllWeaponSlotsCopy()) {
            if (!mountable(s)) continue;
            float w = s.getSlotSize().ordinal() == 0 ? 1f : s.getSlotSize().ordinal() == 1 ? 2f : 4f;
            all += w;
            if (s.getWeaponType() == WeaponType.MISSILE) missile += w;
        }
        return all > 0f && missile / all >= 0.4f ? JAOStyle.ANTI_ARMOR : JAOStyle.BALANCED;
    }

    static JAOModel.Params params(JAOStyle style, int size, JAORole role) {
        return params(style, size, role, false);
    }

    /** flagship: the player's ship (0.5.59): balanced, fights every size, carries its own point defense (Params.flagship). */
    static JAOModel.Params params(JAOStyle style, int size, JAORole role, boolean flagship) {
        JAOModel.Params p = new JAOModel.Params();
        p.apply(style, role, JAOSettings.range);
        p.fightSeconds = JAOSettings.fightSeconds;
        p.pdFloor = JAOSettings.pdFloor;
        p.rareBonus = JAOSettings.preferRare ? JAOSettings.rareBonus : 0f;
        float[] mix = role != null && role.targets != null ? role.targets : JAORatings.TARGET_MIX[Math.max(0, Math.min(3, size))];
        if (flagship) {
            p.flagship(size);
            float[] wide = new float[4];
            for (int i = 0; i < 4; i++) wide[i] = (1f - JAOModel.FLAGSHIP_MIX) * mix[i] + JAOModel.FLAGSHIP_MIX * 0.25f;
            mix = wide;
        }
        p.targets = JAORatings.targets(mix, style);
        return p;
    }

    /** The hull's fleet role: jao_rules override, else its designation and numbers (JAORole.detect). */
    /** 0.5.70: fitted as a module of a station (set by the station's fit). */
    boolean stationParent;

    /** Target sizes a station faces: every size alike (fleets that raid a colony bring frigates to capitals). */
    static final float[] STATION_MIX = {0.25f, 0.25f, 0.25f, 0.25f};
    /** 0.5.90: a station fights STATION_FIGHT times as long as a ship (the Fight length setting). */
    static final float STATION_FIGHT = 2f;
    /** 0.5.90: the size a station and every one of its modules is judged at (capital), whatever its hull size. */
    static final int STATION_JUDGE_SIZE = 3;
    /** 0.5.90: Station Augments ids come in as variant tags with this prefix (Auto Colonies' station fit sets them). */
    static final String AUGMENT_TAG = "jao_augment:";
    /** 0.5.90: the station's augments (from its tags), passed on to its modules. */
    final Set<String> augments = new java.util.TreeSet<>();

    static Set<String> augmentsOf(ShipVariantAPI v) {
        Set<String> out = new java.util.TreeSet<>();
        if (v == null || v.getTags() == null) return out;
        for (String t : v.getTags()) if (t != null && t.startsWith(AUGMENT_TAG) && t.length() > AUGMENT_TAG.length()) out.add(t.substring(AUGMENT_TAG.length()));
        return out;
    }

    /**
     * 0.5.90: hullmods a station or station module may take. It never moves and has no campaign role, so engine and
     * logistics hullmods do nothing for it. Safety Overrides pulls every gun's range down past 450, which the station
     * needs most (Station Augments' Safety Shunt is left out by Auto Colonies for the same fight).
     */
    static boolean stationHullmod(String id, boolean logistics, boolean engines) {
        return !logistics && !engines && !HullMods.SAFETYOVERRIDES.equals(id);
    }

    /** 0.5.90: the scratch world of a station fit: judged as a capital, mobility losses free, its augments counted. */
    JAOShip stationWorld(JAOShip w) {
        if (!stationFit()) return w;
        w.station = true;
        w.judgeSize = STATION_JUDGE_SIZE;
        w.augments = new java.util.TreeSet<>(augments);
        return w;
    }

    /** A station (STATION hint) or a module of one. */
    boolean stationFit() {
        return stationParent || station(current.getHullSpec());
    }

    static boolean station(ShipHullSpecAPI hull) {
        return hull != null && hull.getHints().contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.STATION);
    }

    /** The role setting's role; a station and its modules are Anchors (carriers stay carriers). */
    JAORole roleFor(ShipHullSpecAPI hull) {
        if (!JAOSettings.roles) return null;
        JAORole r = roleOf(hull);
        if (stationFit() && r != JAORole.CARRIER && r != JAORole.BATTLE_CARRIER) r = JAORole.ANCHOR;
        return r;
    }

    static JAORole roleOf(ShipHullSpecAPI hull) {
        JAORole o = JAORatings.roleOverride(hull.getHullId(), hull.getBaseHullId());
        if (o != null) return o;
        float slots = 0f, missile = 0f;
        for (WeaponSlotAPI s : hull.getAllWeaponSlotsCopy()) {
            if (!mountable(s)) continue;
            float w = s.getSlotSize().ordinal() == 0 ? 5f : s.getSlotSize().ordinal() == 1 ? 10f : 20f;
            slots += w;
            if (s.getWeaponType() == WeaponType.MISSILE) missile += w;
        }
        int op;
        try { op = hull.getOrdnancePoints(null); } catch (Throwable t) { op = 0; }
        int size = Math.max(0, JAORatings.sizeIndex(hull.getHullSize()));
        boolean phase = JAOShip.phaseCloak(hull);
        // bays with built-in wings aren't a carrier's choice (0.5.45, build audit: Venture, Apex)
        int bays = hull.getFighterBays() - (hull.getBuiltInWings() == null ? 0 : hull.getBuiltInWings().size());
        return JAORole.detect(hull.getDesignation(), size, phase, Math.max(0, bays), op > 0 ? slots / op : 1f,
                slots > 0f ? missile / slots : 0f, hull.isCivilianNonCarrier(), hull.getShipSystemId(), speedRatio(hull, size));
    }

    /** The hull's max speed against the median of the loaded combat hulls of its size (0.5.59); 1 when unknown. */
    static float speedRatio(ShipHullSpecAPI hull, int size) {
        try {
            float median = JAORatings.refs()[Math.max(0, Math.min(3, size))][0].speed;
            float speed = hull.getEngineSpec() == null ? 0f : hull.getEngineSpec().getMaxSpeed();
            return median > 0f && speed > 0f ? speed / median : 1f;
        } catch (Throwable t) {
            return 1f;
        }
    }

    /**
     * A special hull (0.5.59, user 11/16: a unique ship gains priority in Optimize fleet): one no blueprint builds
     * (no "_bp" tag: Ziggurat, Omega, Remnant, Threat and Dweller hulls, one-off story ships), or tagged omega, threat,
     * dweller or restricted. Derelict drones and civilian hulls are not. A skin keeps only its own tags ((D) hulls have
     * just hide_in_codex), so the base hull's tags decide.
     */
    static boolean special(ShipHullSpecAPI hull) {
        if (hull == null || hull.isCivilianNonCarrier()) return false;
        java.util.Set<String> tags = new java.util.HashSet<>(hull.getTags());
        try {
            String base = hull.getBaseHullId();
            if (base != null && !base.equals(hull.getHullId())) {
                ShipHullSpecAPI b = Global.getSettings().getHullSpec(base);
                if (b != null) tags.addAll(b.getTags());
            }
        } catch (Throwable ignored) { }
        if (tags.contains("derelict")) return false;
        for (String t : new String[]{"omega", "threat", "dweller", "restricted"}) if (tags.contains(t)) return true;
        for (String t : tags) if (t != null && t.endsWith("_bp")) return false;
        return true;
    }

    /** The player's flagship gets the balanced flagship fit (0.5.59, setting jao_flagshipBalanced). */
    private boolean flagshipFit() {
        if (module || !JAOSettings.flagshipBalanced) return false;
        try {
            FleetMemberAPI m = delegate.getFleetMember();
            return m != null && m.isFlagship();
        } catch (Throwable t) {
            return false;
        }
    }

    /** jao_rules one_per_ship: more than one feedback weapon makes them hurt their own ship (Emergent Threats). */
    private static void oneFeedbackWeapon(JAOSearch s) {
        boolean seen = false;
        for (JAOSearch.Slot slot : s.slots) {
            JAOSearch.Cand c = slot.fixed != null ? slot.fixed : slot.pick;
            if (c == null || !isFeedback(c.w)) continue;
            if (!seen) { seen = true; continue; }
            if (slot.fixed != null) continue;
            s.give(slot.pick);
            slot.pick = null;
            slot.cands.removeIf(x -> isFeedback(x.w));
            s.pickBest(slot, false);
        }
    }

    /** 0.5.89: Threat Fragment Swarm (needs a Fragment Fabricator; the fragment weapons fire only with it). */
    static final String SWARM = "fragment_swarm";
    /** 0.5.89: how much better the fit with Fragment Swarm must score than the fit without. */
    static final float SWARM_MIN_GAIN = 0.01f;

    /** 0.5.89: the fit with Fragment Swarm on, when it won. */
    private static final class SwarmTrial {
        JAOSearch search;
        List<JAOSearch.Slot> slots;
        JAOShip world;
        Set<String> keepAll;
        Map<String, Integer> stock;
    }

    /**
     * 0.5.89 (user 11/24): Fragment Swarm and the fragment weapons tried together. Fragment weapons are candidates only on
     * a ship with the swarm (buildSlots), and the swarm alone adds nothing, so the search never put it on. When the swarm
     * can go on (known, a Fragment Fabricator in cargo or storage that no other ship in the plan takes, not a phase ship,
     * no Shrouded hullmods) and the pool has a fragment weapon, the fit is searched again with the swarm on, from the same
     * stock. It's kept when it fits a fragment weapon and scores SWARM_MIN_GAIN better. Secondary Fabricator and Fragment
     * Coordinator can go on in that search. Null: the fit without the swarm stays (the swarm is taken off again).
     */
    private SwarmTrial swarmTrial(ShipHullSpecAPI hull, int hullOp, JAOModel.Params p, Set<String> smods, Set<String> keepAll,
                                  List<String> cands, Supplier<List<JAOSearch.Slot>> slotsOf, Map<String, Integer> stock0,
                                  List<AvailableWeapon> weapons, JAOShip world, JAOSearch base, boolean real) {
        if (stock0 == null || current.hasHullMod(SWARM) || !cands.contains(SWARM)) return null;
        boolean anyFragment = false;
        for (AvailableWeapon w : weapons) {
            WeaponSpecAPI spec = w.getSpec();
            if (spec != null && w.getQuantity() > 0 && spec.hasTag("fragment") && !skipped(spec.getWeaponId())) { anyFragment = true; break; }
        }
        if (!anyFragment || !world.canAddTagged(SWARM, keepAll)) return null;
        float without = base.objective(true);
        current.addMod(SWARM);
        boolean keep = false;
        try {
            int budget = hullOp - current.computeOPCost(plugin.stats());
            JAOShip w2 = stationWorld(new JAOShip(current, delegate, plugin.stats(), budget, plugin.getMaxVents(hull.getHullSize()), plugin.getMaxCaps(hull.getHullSize())));
            w2.setSMods(smods);
            Set<String> keep2 = new LinkedHashSet<>(keepAll);
            keep2.add(SWARM);
            List<String> cands2 = new ArrayList<>(cands);
            cands2.remove(SWARM);
            List<JAOSearch.Slot> slots2 = slotsOf.get();
            Map<String, Integer> stock2 = new LinkedHashMap<>(stock0);
            JAOSearch s2 = new JAOSearch(w2, p, slots2, stock2, cands2, keep2);
            if (!planning || fullPlan) s2.fleetMods.addAll(fleetHullmods(keep2));
            s2.run();
            oneFeedbackWeapon(s2);
            float with = s2.objective(true);
            int fragments = 0;
            for (JAOSearch.Slot sl : slots2) if (sl.fixed == null && sl.pick != null && !sl.pick.w.wing && (sl.pick.w.fragment || sl.pick.w.fragUse > 0f)) fragments++;
            keep = swarmWins(fragments, with, without);
            if (real || JAOSettings.debug) Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + shipName() + " Fragment Swarm trial"
                    + (real ? "" : " (preview)") + ": " + fragments + " fragment weapons, score " + with + " with, " + without + " without; "
                    + (keep ? "swarm fitted" : "left off"));
            if (!keep) return null;
            SwarmTrial t = new SwarmTrial();
            t.search = s2; t.slots = slots2; t.world = w2; t.keepAll = keep2; t.stock = stock2;
            return t;
        } catch (Throwable t) {
            if (t instanceof JAOCoop.Cancelled) throw (JAOCoop.Cancelled) t;
            keep = false;
            Global.getLogger(JAOOptimizer.class).warn("Auto Outfitting: Fragment Swarm trial failed for " + shipName() + "; fit without it", t);
            return null;
        } finally {
            if (!keep) current.removeMod(SWARM);
        }
    }

    /** 0.5.89: the swarm stays when a fragment weapon is fitted and the score gains SWARM_MIN_GAIN. */
    static boolean swarmWins(int fragmentWeapons, float with, float without) {
        return fragmentWeapons > 0 && with > Math.max(0f, without) * (1f + SWARM_MIN_GAIN);
    }

    /** Fighter wings aren't weapons (getWeaponSpec throws for wing ids: the Legion crash on 11/02). */
    private static boolean isFeedback(JAOModel.W w) {
        if (w == null || w.wing) return false;
        try {
            WeaponSpecAPI spec = Global.getSettings().getWeaponSpec(w.id);
            return spec != null && spec.hasTag("feedback_weapon");
        } catch (Throwable t) {
            return false;
        }
    }

    private void log(JAOSearch s, JAOShip world, JAOModel.Params p, JAOStyle st, long ms) {
        int[] vc = s.fluxBest();
        // 0.5.101: a module with no open slot, no hullmod, vent or capacitor chosen has nothing to show (armor plates)
        if (module && s.mods.isEmpty() && vc[0] == 0 && vc[1] == 0) {
            boolean open = false;
            for (JAOSearch.Slot slot : s.slots) if (slot.fixed == null) { open = true; break; }
            if (!open) return;
        }
        Map<String, Float> b = new LinkedHashMap<>();
        JAOModel.evaluate(JAOModel.withFlux(s.fitShip(), vc[0], vc[1]), s.fits(), p, b);
        StringBuilder sb = new StringBuilder("Auto Outfitting: ").append(current.getHullSpec().getHullId())
                .append(module ? " (module)" : "").append(' ').append(p.role != null ? p.role.label + "/" : "").append(st.label).append(" in ").append(ms).append(" ms, ")
                .append(s.evaluations).append(" evaluations; ");
        for (JAOSearch.Slot slot : s.slots) {
            JAOSearch.Cand c = slot.fixed != null ? slot.fixed : slot.pick;
            sb.append(slot.id).append('=').append(c == null ? "-" : c.w.id).append(slot.fixed != null ? "(kept)" : "").append(' ');
        }
        sb.append("| hullmods ").append(s.mods).append(" | vents ").append(vc[0]).append(" caps ").append(vc[1]).append(" | ");
        List<String> dmods = new ArrayList<>();
        for (String id : current.getHullMods()) {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (spec != null && spec.hasTag("dmod")) dmods.add(id);
        }
        if (!dmods.isEmpty()) sb.append("d-mods ").append(dmods).append(" | ");
        if (!world.captainSkills.isEmpty()) sb.append("captain skills ").append(world.captainSkills).append(" | ");
        for (Map.Entry<String, Float> e : b.entrySet()) sb.append(e.getKey()).append(' ').append(String.format(java.util.Locale.ROOT, "%.3g", e.getValue())).append(' ');
        Global.getLogger(JAOOptimizer.class).info(sb.toString());
    }
}
