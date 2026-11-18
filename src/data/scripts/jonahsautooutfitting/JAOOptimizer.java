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
        if (!module && (logistics || (!planning && JAOSettings.civilianLogistics && civilian(hull)))) {
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
            if (!module && (!planning || fullPlan)) {
                for (String id : new ArrayList<>(current.getNonBuiltInHullmods())) {
                    HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
                    if (spec == null || spec.isHidden() || spec.isHiddenEverywhere() || JAORatings.ruleMatches("never_remove", id)
                        || (spec.hasTag("shrouded") && !JAOSettings.shroudedHullmods)) continue;
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
                planSmodChanges(hull, slotsOf, stock);
            } catch (Throwable t) {
                if (t instanceof JAOCoop.Cancelled) throw (JAOCoop.Cancelled) t;
                // changes already made to the plan stay with their records; no more are tried
                Global.getLogger(JAOOptimizer.class).warn("Auto Outfitting: s-mod change planning failed for " + shipName(), t);
            }
        }
        Set<String> keepMods = new LinkedHashSet<>(current.getNonBuiltInHullmods());
        List<String> modCands = module || (planning && !fullPlan) ? new ArrayList<>() : hullmodCandidates(keepMods);

        // 4. search (s-mods first: they change how much OP everything else gets)
        int hullOp = hull.getOrdnancePoints(plugin.stats());
        int budget = hullOp - current.computeOPCost(plugin.stats());
        JAORole role = JAOSettings.roles ? roleOf(hull) : null;
        JAOStyle st = role != null ? style : resolve(style, hull);
        JAOModel.Params p = paramsFor(hull, role, st);
        // bought weapons must earn their price (survival mode's x10 market prices); plans use owned stock or blueprints
        p.creditCost = planning && !buying ? 0f : JAOSettings.buyGain / 10000f;
        JAOShip world = new JAOShip(current, delegate, plugin.stats(), budget, plugin.getMaxVents(hull.getHullSize()), plugin.getMaxCaps(hull.getHullSize()));
        // 0.5.64: AI Tweaks' Custom AI on cruisers and capitals; it costs no OP, so it's kept like a hullmod the ship has
        if (!module && JAOCompat.customAiWanted(hull.getHullSize(), delegate.getAvailableHullmods())
                && !current.hasHullMod(JAOCompat.CUSTOM_AI) && world.canAdd(JAOCompat.CUSTOM_AI, keepMods)) {
            current.addMod(JAOCompat.CUSTOM_AI);
            keepMods.add(JAOCompat.CUSTOM_AI);
        }
        Set<String> smods = new LinkedHashSet<>();
        String smodNote = null;
        if (!module && (planning ? planSmods : plugin.option(JAOAutofitPlugin.SMODS))) {
            List<String> available = smodCandidates(modCands, keepMods, hull);
            // s-mods the credit changes took off this run aren't built back in with story points
            available.removeAll(smodsRemoved);
            int[] why = new int[4];
            int limit = smodLimit(why);
            FleetMemberAPI member = delegate.getFleetMember();
            String memberId = member == null ? null : member.getId();
            if (limit <= 0) {
                smodNote = why[0] <= 0 && sizeCapped ? hull.getHullSize().name().toLowerCase(java.util.Locale.ROOT) + "s take at most " + sizeCap(hull.getHullSize()) + " s-mods (setting)"
                        : why[0] <= 0 ? "already has " + why[3] + " of " + (why[3] + Math.max(0, why[0])) + " s-mods"
                        : why[1] <= 0 && fleetCapUsed() ? "Story points per Optimize fleet (" + JAOSettings.smodFleetMax + ") used up by bigger ships"
                        : why[1] <= 0 ? "no story points" + (JAOSettings.smodReserve > 0 ? " above the " + JAOSettings.smodReserve + " kept in reserve" : "")
                        : "the s-mods per ship setting allows none";
                if (why[0] <= 0 && memberId != null && !planning) JAOSMods.stopWaiting(memberId);
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
                }
                JAOSMods.Plan plan = sm.plan(available, every);
                smodPlan = plan;
                smods.addAll(plan.mods);
                smodNote = plan.note + (dmods.isEmpty() ? "" : " (judged without the removable d-mods " + dmods + ")");
                if (plan.waiting && memberId != null && !planning) {
                    waited = JAOSMods.waitedDays(memberId, true);
                    smodNote += "; waited " + Math.round(waited) + " of " + JAOSettings.smodWaitDays + " days";
                } else if (JAOSettings.smodWait && !mayWait) {
                    smodNote = "stopped waiting after " + JAOSettings.smodWaitDays + " days; " + smodNote;
                }
                // 0.5.60: s-mods go in on Confirm, so the wait is over
                if (!plan.waiting && !plan.mods.isEmpty() && memberId != null && !planning && real) JAOSMods.stopWaiting(memberId);
            }
        }
        world.setSMods(smods);
        Set<String> keepAll = new LinkedHashSet<>(keepMods);
        keepAll.addAll(smods);
        List<String> cands = new ArrayList<>(modCands);
        cands.removeAll(smods);
        JAOSearch search = new JAOSearch(world, p, slots, stock, cands, keepAll);
        if (!module && (!planning || fullPlan)) search.fleetMods.addAll(fleetHullmods(keepAll));
        search.run();
        oneFeedbackWeapon(search);

        // 5. apply: hullmods, weapons and wings (OP-checked by the real delegate), then s-mods (no OP), then flux
        for (String m : search.mods) if (!keepAll.contains(m)) current.addMod(m);
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
        int[] vc = search.flux();
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
        if (unused > 0 && !planning && (real || JAOSettings.debug)) {
            StringBuilder why = new StringBuilder("vents and capacitors at maximum, no hullmod or weapon upgrade fits");
            if (!JAOSettings.allowDrawbacks && !world.drawbacks.isEmpty()) {
                why.append("; hullmods with drawbacks that \"Allow hullmods with drawbacks\" would let in: ");
                int n = 0;
                for (Map.Entry<String, String> e : world.drawbacks.entrySet()) {
                    if (world.modOp(e.getKey()) > unused) continue;
                    why.append(n++ == 0 ? "" : ", ").append(JAOSMods.name(e.getKey())).append(" (").append(e.getValue()).append(')');
                }
                if (n == 0) why.append("none that fits");
            }
            Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + shipName() + " has " + unused + " OP unused: " + why);
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
                    + (unused > 0 ? " (" + unused + " OP spare" + (!JAOSettings.allowDrawbacks && !world.drawbacks.isEmpty() ? ": drawbacks off)" : ")") : ""));
            delegate.syncUIWithVariant(current);
            if (real) settleOp(hullOp, maxV, maxC, role == JAORole.CARRIER || role == JAORole.PHASE || role == JAORole.MISSILE_BOAT || role == JAORole.SUPPORT);
        }
        if (smodNote != null && (real || JAOSettings.debug)) {
            Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + shipName() + " s-mods" + (real ? "" : " (preview)") + ": " + smodNote);
        }
        if (JAOSettings.debug) {
            log(search, world, p, st, (System.nanoTime() - t0) / 1_000_000L);
            if (!world.drawbacks.isEmpty()) Global.getLogger(JAOOptimizer.class).info("Auto Outfitting: " + shipName()
                    + " hullmods skipped for drawbacks: " + world.drawbacks);
        }
    }

    /**
     * The build's params: style and role, then the rest of the fleet: fighter roles already covered, and (Auto/Balanced)
     * the damage it's short of. The fleet plan passes the rest of its plan; a single fit reads the fleet as it stands.
     */
    private JAOModel.Params paramsFor(ShipHullSpecAPI hull, JAORole role, JAOStyle st) {
        JAOModel.Params p = params(st, Math.max(0, JAORatings.sizeIndex(hull.getHullSize())), role, flagshipFit());
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
        JAORole role = JAOSettings.roles ? roleOf(hull) : null;
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

    /** S-mods this fit may build in: the ship's limit left, story points above the reserve, the per-ship setting. */
    private int smodLimit(int[] why) {
        int max = Misc.getMaxPermanentMods(delegate.getFleetMember(), plugin.stats()), cap = sizeCap(current.getHullSpec().getHullSize());
        sizeCapped = cap < max;
        int room = Math.min(max, cap) - current.getSMods().size();
        int sp = (storyPoints >= 0 ? storyPoints : Global.getSector().getPlayerStats().getStoryPoints()) - JAOSettings.smodReserve;
        // why: room left, story points above the reserve, the per-ship setting, s-mods already on the ship
        why[0] = room; why[1] = sp; why[2] = JAOSettings.smodCount; why[3] = current.getSMods().size();
        return smodLimit(room, sp + JAOSettings.smodReserve, JAOSettings.smodReserve, JAOSettings.smodCount);
    }

    /** The size cap (frigates, destroyers) is below the game's s-mod limit for this ship. */
    private boolean sizeCapped;

    /** Most s-mods Auto Outfitting builds into this hull size (jao_smodMaxFrigate / jao_smodMaxDestroyer; others: no cap). */
    static int sizeCap(HullSize size) {
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
    private List<String> smodCandidates(List<String> modCands, Set<String> keepMods, ShipHullSpecAPI hull) {
        List<String> available = new ArrayList<>();
        for (String id : modCands) if (JAOSMods.buildable(id)) available.add(id);
        for (String id : keepMods) if (JAOSMods.buildable(id) && !available.contains(id)) available.add(id);
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
        FleetMemberAPI member = delegate.getFleetMember();
        // only ships with no free s-mod slot (game limit and size cap): a free slot takes a new s-mod with a story point,
        // which keeps the s-mod already there
        int full = Math.min(Misc.getMaxPermanentMods(member, plugin.stats()), sizeCap(hull.getHullSize()));
        float weight = sizeWeight(member, hull);
        float need = JAOSettings.smodChangeMinGain * weight;
        JAORole role = JAOSettings.roles ? roleOf(hull) : null;
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
                    if (!id.equals(off) && !v.getSMods().contains(id) && !smodsRemoved.contains(id)) ons.add(id);
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
        return dmodTag && (hullBuiltIns == null || !hullBuiltIns.contains(id));
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
        ps.spendStoryPoints(n, true, null, true, xp / n, "Built " + Misc.getAndJoined(names) + " into " + shipName() + " (" + hull.getNameWithDesignationWithDashClass() + ")");
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

    /** Each module's own slots with the same style; modules get no hullmods (the game's autofit does the same). */
    private void fitModules() {
        int index = 0;
        for (String slotId : new ArrayList<>(current.getStationModules().keySet())) {
            ShipVariantAPI m = current.getModuleVariant(slotId);
            if (m == null) continue;
            // planning always works on copies: the real ship's modules must never be touched
            if (m.isStockVariant() || planning) {
                m = m.clone();
                m.setSource(VariantSource.REFIT);
                m.setHullVariantId(m.getHullVariantId() + "_" + index);
            }
            index++;
            JAOOptimizer mo = new JAOOptimizer(plugin, m, delegate, style, true, planning);
            mo.buying = buying;
            mo.run();
            // planning fits a copy of the ship, so its module copies go into that copy (the plan carries them)
            current.setModuleVariant(slotId, m);
        }
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
        Map<String, JSONObject> table = JAORatings.hullmods();
        for (String id : delegate.getAvailableHullmods()) {
            // 0.5.60: the ship's own s-mods aren't candidates (getNonBuiltInHullmods leaves them out of keep; adding one
            // changed nothing but booked its OP)
            if (keep.contains(id) || current.getHullSpec().getBuiltInMods().contains(id) || current.getPermaMods().contains(id)) continue;
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (spec == null || spec.isHidden() || spec.isHiddenEverywhere() || spec.hasTag("dmod")) continue;
            if (JAOCompat.neverAdd(id) || JAOCompat.tooSmall(id, current.getHullSize()) || JAOCompat.notOnPhase(id, current.getHullSpec())
                    || JAOCompat.removedByHull(id, current.getHullSpec())) continue;
            if (spec.hasUITag("Logistics") && !JAOSettings.logistics) continue;
            if (id.equals(HullMods.SAFETYOVERRIDES) && (JAOSettings.safetyOverrides == 0
                    || (JAOSettings.safetyOverrides == 1 && current.getHullSize().ordinal() > HullSize.DESTROYER.ordinal()))) continue;
            JSONObject row = table.get(id);
            if (row != null) {
                if (!"TRUE".equalsIgnoreCase(row.optString("combat", "TRUE"))) continue;
                String how = row.optString("how", "stats");
                if ("listener".equals(how) && !JAOShip.modelled(id)) continue;
            }
            out.add(id);
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ civilian ships

    /** Freighters, tankers, liners, salvage and survey ships: hulls that never fight (design 7h). */
    static boolean civilian(ShipHullSpecAPI hull) {
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
                if (spec == null || spec.isHidden() || spec.isHiddenEverywhere() || JAORatings.ruleMatches("never_remove", id)
                        || (spec.hasTag("shrouded") && !JAOSettings.shroudedHullmods)) continue;
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
        boolean smodsOn = JAOSettings.civilianSmods && (planning ? planSmods : plugin.option(JAOAutofitPlugin.SMODS));
        int smodRoom = smodsOn && keeper(hull) ? smodLimit(new int[4]) : 0;
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
        // 0.5.64: AI Tweaks' Custom AI on civilian cruisers and capitals too (0 OP, not a logistics hullmod)
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
        Map<String, Float> b = new LinkedHashMap<>();
        int[] vc = s.flux();
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
