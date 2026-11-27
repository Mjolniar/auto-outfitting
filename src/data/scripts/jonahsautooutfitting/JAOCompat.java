package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.util.Misc;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Other mods' rules that live in their scripts, not their data (research modscan_1105, modded_report.txt section 2).
 * The user 11/05: a Guru-class carrier came out of Optimize fleet with every bay empty. Its built-in Plague Bearer
 * Cultist hullmod (Seeker) throws every LPC but the three plague ones back into cargo the moment the ship is shown, and
 * Trinity Worlds hulls fill empty bays with Nimbus drones by script and delete Nimbus LPCs from cargo, so the fleet plan,
 * which moves wings between ships, kept losing them ("items weren't there any more").
 *  - Hull-bound wings: an LPC tagged no_drop and no_sell can't exist as cargo. Scripts place them (Nimbus drones,
 *    Emergent Threats kites and Terminators, Arma bits) and most delete them from cargo, so such a wing stays in its bay:
 *    never taken off, moved to another ship or counted as stock.
 *  - Bay rules (jao_rules bay_wings): hulls whose scripts throw other wings out fly only their own. Plague Bearer and
 *    Cultist hulls take only Seeker's plague LPCs (free there; 1000+ OP anywhere else), Trinity Worlds hulls only their
 *    Nimbus drones, except the carriers with an Enhanced Control Node (Maquech), which fly anything.
 *  - Seeker's nano-plague: plague weapons and LPCs cost max CR on other hulls (modSettings seeker.plague_*), priced by
 *    JAOModel as a share of the ship.
 *  - MagicLib interference from every mod's modSettings (MagicLib.interferences_weapons), next to jao_rules rows.
 *  - probe: a scratch fleet member runs every hullmod's stat effects on a copy of a plan, as the game does whenever it
 *    refreshes a ship. Bays a script fills (a Maquech's empty bays take Nimbus drones at 10 OP each, which put it over its
 *    OP and gave it Equipment Malfunction, -100% CR) become part of the plan, their OP comes out of capacitors and vents,
 *    and hullmods a script adds are reported.
 *  - purge_lpc: LPCs their own mod deletes from cargo, left in storage by 0.5.1's Optimize fleet; removed on load and
 *    after every Optimize fleet.
 */
final class JAOCompat {
    private JAOCompat() { }

    static final String SEEKER_PLAGUE = "@seeker_plague";
    /** % of max CR lost per plague item by strength, and Hardened Subsystems' share (Seeker's defaults). */
    private static final float WEAK = 3f, MILD = 6f, STRONG = 12f;
    private static boolean loaded;
    private static final Map<String, Float> plague = new HashMap<>(), interference = new HashMap<>();
    private static final Set<String> plagueLpc = new LinkedHashSet<>();
    private static float hss = 0.666f;

    // ------------------------------------------------------------------ wings

    /** No drop and no sell: a wing only scripts place, which stays in the bay it's in. */
    static boolean boundWing(FighterWingSpecAPI spec) {
        return spec != null && spec.hasTag("no_drop") && spec.hasTag("no_sell");
    }

    static boolean boundWing(String wingId) {
        return wingId != null && !wingId.isEmpty() && boundWing(JAOFleetPlan.wingSpec(wingId));
    }

    /**
     * The wings this ship may fly (ids or patterns), or null for any: jao_rules bay_wings rows for the hullmods it has
     * (built in or fitted). "*" lifts every other row (Enhanced Control Node on a Trinity Worlds carrier).
     */
    static List<String> bayWings(ShipVariantAPI v) {
        List<String> allow = null;
        for (String[] r : JAORatings.rules()) {
            if (!r[0].equals("bay_wings") || r[1].isEmpty() || !v.hasHullMod(r[1])) continue;
            if (r[2].trim().equals("*")) return null;
            if (allow == null) allow = new ArrayList<>();
            for (String p : r[2].split(";")) {
                p = p.trim();
                if (p.equals(SEEKER_PLAGUE)) { load(); allow.addAll(plagueLpc); }
                else if (!p.isEmpty()) allow.add(p);
            }
        }
        return allow;
    }

    /**
     * Whether the ship may fly only uncrewed wings (jao_rules drones_only, 0.5.36): a listed hullmod is built in (hull
     * or s-mod) and none of the value's exemptions applies (a hullmod id on the ship, or a $sector memory flag that is
     * set). Emergent Threats' AI Subsystem Integration adds Drone Bay Malfunction (a large CR cut) for crewed wings
     * unless Remote Control, Autonomous Bays or Drone Tactics is in play.
     */
    static boolean dronesOnly(ShipVariantAPI v) {
        for (String[] r : JAORatings.rules()) {
            if (!r[0].equals("drones_only") || r[1].isEmpty()) continue;
            // on the ship at all (0.5.46: Adaptive Drone Bay counts when fitted)
            if (!v.hasHullMod(r[1])) continue;
            boolean exempt = false;
            for (String e : r[2].split(";")) {
                e = e.trim();
                if (e.isEmpty()) continue;
                if (e.startsWith("$")) {
                    // Drone Tactics counts only with AI Subsystem Integration as an s-mod or the Abomination Interface (AISubsystemIntegration)
                    if (e.equals("$xo_drone_tactics_is_active") && !(v.getSMods().contains("vice_ai_subsystem_integration") || v.hasHullMod("vice_abomination_interface"))) continue;
                    try { exempt |= Global.getSector().getMemoryWithoutUpdate().is(e, true); } catch (Throwable ignored) { }
                } else if (v.hasHullMod(e)) exempt = true;
            }
            if (!exempt) return true;
        }
        return false;
    }

    /**
     * 0.5.97: jao_rules pd_only_size (hullmod id or pattern -> weapon sizes split by ;). With such a hullmod on the ship,
     * weapons of those sizes are fitted only with the PD hint (Domain Phase Lab Defensive Design: any large weapon
     * without it makes all the ship's damage x0.1).
     */
    static Set<String> pdOnlySizes(java.util.Collection<String> hullmods, List<String[]> rules) {
        Set<String> out = new java.util.HashSet<>();
        if (hullmods == null || rules == null) return out;
        for (String[] r : rules) {
            if (!r[0].equals("pd_only_size")) continue;
            boolean on = false;
            for (String h : hullmods) if (JAORatings.glob(r[1], h)) { on = true; break; }
            if (!on) continue;
            for (String s : r[2].split(";")) if (!s.trim().isEmpty()) out.add(s.trim().toUpperCase(java.util.Locale.ROOT));
        }
        return out;
    }

    /**
     * 0.5.97: jao_rules wing_op (wing id or pattern -> OP): the OP a wing is rated at when its listed cost is a gate a
     * hullmod lowers (Domain Phase Lab Phase Rupture: 99999 listed, 20 under its drone factories). -1 when none.
     */
    static float wingOp(String wingId, List<String[]> rules) {
        if (wingId == null || rules == null) return -1f;
        for (String[] r : rules) {
            if (!r[0].equals("wing_op") || !JAORatings.glob(r[1], wingId)) continue;
            try { return Float.parseFloat(r[2]); } catch (NumberFormatException ignored) { }
        }
        return -1f;
    }

    /** A wing with no crew (minimum crew 0 on its fighter hull). */
    static boolean uncrewed(FighterWingSpecAPI spec) {
        try { return spec != null && spec.getVariant() != null && spec.getVariant().getHullSpec().getMinCrew() <= 0f; }
        catch (Throwable t) { return false; }
    }

    static boolean wingAllowed(List<String> allow, String wingId) {
        if (allow == null) return true;
        for (String p : allow) if (JAORatings.glob(p, wingId)) return true;
        return false;
    }

    /** "fly only Seeker's plague LPCs" for a bay note; null when the ship flies anything. */
    static String bayRule(ShipVariantAPI v) {
        List<String> allow = bayWings(v);
        if (allow == null) return null;
        List<String> names = new ArrayList<>();
        for (String p : allow) {
            if (p.contains("*")) { names.add(p); continue; }
            FighterWingSpecAPI s = JAOFleetPlan.wingSpec(p);
            String n = s == null ? p : JAOFleetPlanner.wingName(s);
            if (!names.contains(n)) names.add(n);
        }
        return names.isEmpty() ? "takes no LPCs" : "only flies " + Misc.getAndJoined(names);
    }

    // ------------------------------------------------------------------ items you only get with cheats

    private static Set<String> legalWeapons, legalWings;

    /**
     * A weapon you can't get in a normal game (0.5.8, the user: "anything that's cheat available only shouldn't be included
     * in calculation and shouldn't be added"): no blueprint tag, no NPC faction knows it, no ship in any mod carries it in
     * a normal mount (so it never drops), and it isn't Omega, Threat or Dweller gear. Yunru's gyrojets, the built-in TPC.
     * The player faction's own known list doesn't count: console allblueprints puts everything in it.
     */
    static boolean cheatOnly(com.fs.starfarer.api.loading.WeaponSpecAPI s) {
        if (s == null || endgameOrBlueprint(s.getTags())) return false;
        legal();
        return !legalWeapons.contains(s.getWeaponId());
    }

    static boolean cheatOnly(FighterWingSpecAPI s) {
        if (s == null || endgameOrBlueprint(s.getTags())) return false;
        legal();
        return !legalWings.contains(s.getId());
    }

    private static boolean endgameOrBlueprint(java.util.Collection<String> tags) {
        if (tags == null) return false;
        for (String t : tags) if (t.endsWith("_bp") || t.equals("omega") || t.equals("threat") || t.equals("dweller")) return true;
        return false;
    }

    /** Weapons and wings NPC factions know or NPC ship variants carry (not built in), read once per load. */
    private static void legal() {
        if (legalWeapons != null) return;
        legalWeapons = new java.util.HashSet<>();
        legalWings = new java.util.HashSet<>();
        try {
            if (Global.getSector() != null) for (com.fs.starfarer.api.campaign.FactionAPI f : Global.getSector().getAllFactions()) {
                if (f.isPlayerFaction()) continue;
                legalWeapons.addAll(f.getKnownWeapons());
                legalWings.addAll(f.getKnownFighters());
            }
        } catch (Throwable t) {
            Global.getLogger(JAOCompat.class).warn("Auto Outfitting: couldn't read the factions' known weapons", t);
        }
        int variants = 0;
        for (String id : Global.getSettings().getAllVariantIds()) {
            try {
                ShipVariantAPI v = Global.getSettings().getVariant(id);
                // a fighter's own guns (the vanilla bomb) only come from wings: they don't make a ship weapon obtainable
                if (v == null || v.getHullSpec() == null || v.getHullSpec().getHullSize() == com.fs.starfarer.api.combat.ShipAPI.HullSize.FIGHTER) continue;
                variants++;
                for (String slot : v.getNonBuiltInWeaponSlots()) { String w = v.getWeaponId(slot); if (w != null) legalWeapons.add(w); }
                for (String w : v.getNonBuiltInWings()) if (w != null && !w.isEmpty()) legalWings.add(w);
            } catch (Throwable ignored) { }
        }
        Global.getLogger(JAOCompat.class).info("Auto Outfitting: " + legalWeapons.size() + " weapons and " + legalWings.size()
                + " wings obtainable in a normal game (NPC factions and " + variants + " ship variants)");
    }

    static void reset() { legalWeapons = null; legalWings = null; }

    // ------------------------------------------------------------------ Seeker's plague, MagicLib interference

    /** % of max CR one fitted copy costs on a hull the plague harms (Seeker); 0 for anything else. */
    static float plagueCost(String id) {
        load();
        Float f = plague.get(id);
        if (f != null) return f;
        for (String[] r : JAORatings.rules()) if (r[0].equals("plague") && JAORatings.glob(r[1], id)) return rate(null, r[2], 0f);
        return 0f;
    }

    /** The share of the plague cost this ship pays: none on Plague Bearer or Cultist hulls, x0.666 with Hardened Subsystems. */
    static float plagueMult(ShipVariantAPI v) {
        for (String[] r : JAORatings.rules()) if (r[0].equals("plague_immune") && v.hasHullMod(r[1])) return 0f;
        load();
        return v.hasHullMod("hardened_subsystems") ? hss : 1f;
    }

    /**
     * Hullmods that change the fighters a ship launches rather than the ship (applyEffectsToFighterSpawnedByShip, which
     * the scratch member never runs): jao_rules wing_mult rows, hullmod id -> multiplier on its wings' value (Defective
     * Manufactory: craft 25% slower and taking 25% more damage). A d-mod's share shrinks with the ship's d-mod effect
     * (skills that soften d-mods).
     */
    static float wingMult(ShipVariantAPI v, float dmodEffect) {
        float m = 1f;
        for (String[] r : JAORatings.rules()) {
            if (!r[0].equals("wing_mult") || !v.hasHullMod(r[1])) continue;
            float f;
            try { f = Float.parseFloat(r[2].trim()); } catch (RuntimeException e) { continue; }
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(r[1]);
            float effect = spec != null && spec.hasTag("dmod") ? Math.max(0f, dmodEffect) : 1f;
            m *= 1f - (1f - f) * effect;
        }
        return m;
    }

    /** MagicLib interference strength from the merged modSettings (dissipation per other interference weapon), or 0. */
    static float interference(String weaponId) {
        load();
        Float f = interference.get(weaponId);
        return f == null ? 0f : f;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        JSONObject all = modSettings();
        if (all == null) return;
        try {
            JSONObject seeker = all.optJSONObject("seeker");
            if (seeker != null) {
                JSONObject rates = seeker.optJSONObject("plague_rates");
                hss = (float) seeker.optDouble("plague_HSSmult", hss);
                JSONObject ws = seeker.optJSONObject("plague_weapons");
                if (ws != null) for (Iterator<?> it = ws.keys(); it.hasNext(); ) {
                    String id = String.valueOf(it.next());
                    plague.put(id, rate(rates, ws.optString(id), MILD));
                }
                // the plague flight deck charges STRONG per plague wing (SKR_plagueLPC)
                JSONArray lpc = seeker.optJSONArray("plague_LPC");
                if (lpc != null) for (int i = 0; i < lpc.length(); i++) {
                    String id = lpc.optString(i, "").trim();
                    if (id.isEmpty()) continue;
                    plagueLpc.add(id);
                    plague.put(id, rate(rates, "STRONG", STRONG));
                }
            }
            JSONObject magic = all.optJSONObject("MagicLib");
            JSONObject ws = magic == null ? null : magic.optJSONObject("interferences_weapons");
            if (ws != null) {
                JSONObject rates = magic.optJSONObject("interferences_rates");
                for (Iterator<?> it = ws.keys(); it.hasNext(); ) {
                    String id = String.valueOf(it.next());
                    String s = ws.optString(id);
                    float v = rates != null && rates.has(s) ? (float) rates.optDouble(s, 0) : "STRONG".equalsIgnoreCase(s) ? 80f : "MILD".equalsIgnoreCase(s) ? 40f : 20f;
                    if (v > 0f) interference.put(id, v);
                }
            }
        } catch (Throwable t) {
            Global.getLogger(JAOCompat.class).warn("Auto Outfitting: couldn't read Seeker's or MagicLib's modSettings", t);
        }
        if (!plague.isEmpty() || !interference.isEmpty()) Global.getLogger(JAOCompat.class).info("Auto Outfitting: "
                + plague.size() + " plague items (" + plagueLpc.size() + " LPCs), " + interference.size() + " MagicLib interference weapons from modSettings");
    }

    /** data/config/modSettings.json merged across mods, the way MagicLib's MagicSettings reads it; null without it. */
    private static JSONObject modSettings() {
        try {
            return Global.getSettings().getMergedJSONForMod("data/config/modSettings.json", "MagicLib");
        } catch (Throwable t) {
            try { return Global.getSettings().getMergedJSON("data/config/modSettings.json"); } catch (Throwable t2) { return null; }
        }
    }

    /** WEAK / MILD / STRONG (from rates, else Seeker's defaults) or a plain number. */
    private static float rate(JSONObject rates, String name, float dflt) {
        if (name == null) return dflt;
        String n = name.trim();
        if (rates != null && rates.has(n)) return (float) rates.optDouble(n, dflt);
        if (n.equalsIgnoreCase("WEAK")) return WEAK;
        if (n.equalsIgnoreCase("MILD")) return MILD;
        if (n.equalsIgnoreCase("STRONG")) return STRONG;
        try { return Float.parseFloat(n); } catch (NumberFormatException e) { return dflt; }
    }

    // ------------------------------------------------------------------ hullmods

    /** Hullmods the optimizer never adds: jao_rules never_add ids, and never_add_ui UI tags (Yunru's "Install:" converters). */
    /**
     * jao_rules min_size rows: hullmod id -> the smallest hull size it's worth on (FRIGATE, DESTROYER, CRUISER,
     * CAPITAL_SHIP). 0.5.12: Integrated Targeting Unit from destroyers up; Grievous69's and GrumpyThumper's 0.98 guides
     * (research builds_update_1106 4.2) say +10% range isn't worth its OP on a frigate.
     */
    /**
     * jao_rules not_on_phase rows: hullmods never put on phase hulls (0.5.15: Heavy Armor; phase ships survive by
     * phasing, experts spend their OP on capacitors and Phase Anchor or Adaptive Phase Coils, and the model can't see
     * phasing well enough to rule it out on its own).
     */
    static boolean notOnPhase(String id, com.fs.starfarer.api.combat.ShipHullSpecAPI hull) {
        if (hull == null || !JAOShip.phaseCloak(hull)) return false;
        return JAORatings.ruleMatches("not_on_phase", id);
    }

    /**
     * jao_rules removed_by rows: built-in hullmod id (or pattern) -> hullmods its script takes off the ship when the
     * game shows it, split by ;. Planning one would leave its OP unused. 0.5.19: Tahlan's Infernalis Drives (Legio
     * hulls) removes Unstable Injector; the Silberherz cores (Stahlherz, Glanzwurf) remove Safety Overrides.
     */
    static boolean removedByHull(String id, com.fs.starfarer.api.combat.ShipHullSpecAPI hull) {
        if (hull == null || id == null) return false;
        for (String[] r : JAORatings.rules()) {
            if (!r[0].equals("removed_by")) continue;
            boolean hit = false;
            for (String m : r[2].split(";")) if (JAORatings.glob(m.trim(), id)) { hit = true; break; }
            if (!hit) continue;
            for (String b : hull.getBuiltInMods()) if (JAORatings.glob(r[1], b)) return true;
        }
        return false;
    }

    /**
     * 0.5.100: jao_rules needs_system and needs_shield rows: hullmods whose combat script crashes on a hull with no ship
     * system (Advanced Modifications Overcharge) or no shield (Flux Shield Matrix with Delta Subsystem).
     */
    static boolean missingNeed(String id, com.fs.starfarer.api.combat.ShipHullSpecAPI hull) {
        if (hull == null || id == null) return false;
        return missingNeed(id, hull.getShipSystemId(), hull.getShieldType(), JAORatings.rules());
    }

    static boolean missingNeed(String id, String systemId, com.fs.starfarer.api.combat.ShieldAPI.ShieldType t, List<String[]> rules) {
        boolean system = systemId != null && !systemId.isEmpty();
        boolean shield = t == com.fs.starfarer.api.combat.ShieldAPI.ShieldType.FRONT || t == com.fs.starfarer.api.combat.ShieldAPI.ShieldType.OMNI;
        for (String[] r : rules) {
            if (r[0].equals("needs_system") && !system && JAORatings.glob(r[1], id)) return true;
            if (r[0].equals("needs_shield") && !shield && JAORatings.glob(r[1], id)) return true;
        }
        return false;
    }

    static boolean tooSmall(String id, com.fs.starfarer.api.combat.ShipAPI.HullSize size) {
        if (size == null) return false;
        for (String[] r : JAORatings.rules()) {
            if (!r[0].equals("min_size") || !JAORatings.glob(r[1], id)) continue;
            try {
                if (size.ordinal() < com.fs.starfarer.api.combat.ShipAPI.HullSize.valueOf(r[2].trim().toUpperCase(java.util.Locale.ROOT)).ordinal()) return true;
            } catch (IllegalArgumentException ignored) { }
        }
        return false;
    }

    static boolean neverAdd(String id) {
        if (JAORatings.ruleMatches("never_add", id)) return true;
        HullModSpecAPI spec;
        try { spec = Global.getSettings().getHullModSpec(id); } catch (Throwable t) { return false; }
        if (spec == null) return false;
        for (String[] r : JAORatings.rules()) if (r[0].equals("never_add_ui") && !r[1].isEmpty() && spec.hasUITag(r[1])) return true;
        return false;
    }

    // ------------------------------------------------------------------ what scripts do to a plan

    /** What a ship's hullmod scripts do to a planned variant when the game refreshes it. */
    static final class Probe {
        /** Bay -> wing a script put into a bay the plan left empty. */
        final Map<Integer, String> wings = new TreeMap<>();
        final List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        /** OP over the hull's limit after the scripts (0 or less: fits). */
        int over;
    }

    /**
     * Runs the plan's hullmod stat effects on a copy (a scratch fleet member, like JAOShip's) and reports what they
     * changed. Never touches the plan or the ship. Captain-dependent effects are left out (the scratch member has none).
     */
    static Probe probe(ShipVariantAPI plan) { return probe(plan, null); }

    /** 0.5.101: memberId = the real ship's id, so hullmods keyed on it (Starship Legends, IndEvo) read that ship and add no save entries. */
    static Probe probe(ShipVariantAPI plan, String memberId) {
        Probe p = new Probe();
        try {
            ShipVariantAPI copy = plan.clone();
            FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, copy);
            if (memberId != null) { try { m.setId(memberId); } catch (Throwable ignored) { } }
            PersonAPI player = Global.getSector() == null ? null : Global.getSector().getPlayerPerson();
            CampaignFleetAPI fleet = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
            if (player != null) m.setFleetCommanderForStats(player, fleet == null ? null : fleet.getFleetData());
            m.updateStats();
            ShipVariantAPI after = m.getVariant();
            int bays = Global.getSettings().computeNumFighterBays(after);
            for (int i = 0; i < bays; i++) {
                String was = JAOFleetApply.wingAt(plan, i), now = JAOFleetApply.wingAt(after, i);
                if (was == null && now != null) p.wings.put(i, now);
            }
            Set<String> a = new LinkedHashSet<>(plan.getHullMods()), b = new LinkedHashSet<>(after.getHullMods());
            for (String id : b) if (!a.contains(id)) p.added.add(id);
            for (String id : a) if (!b.contains(id)) p.removed.add(id);
            MutableCharacterStatsAPI cs = Global.getSector().getPlayerStats();
            p.over = after.computeOPCost(cs) - after.getHullSpec().getOrdnancePoints(cs);
        } catch (Throwable t) {
            Global.getLogger(JAOCompat.class).warn("Auto Outfitting: couldn't check what hullmod scripts do to " + plan.getHullVariantId(), t);
        }
        return p;
    }

    /**
     * Brings a plan in line with its scripts: wings they place go into the plan (free: they come from the script, not
     * stock) and the OP they take comes off capacitors, then vents (vents first on carriers, phase, support and missile
     * ships, which keep the buffer). Returns the bays the scripts fill.
     */
    static Probe settle(ShipVariantAPI plan, boolean capsFirst) { return settle(plan, capsFirst, null); }

    static Probe settle(ShipVariantAPI plan, boolean capsFirst, String memberId) {
        Probe p = probe(plan, memberId);
        for (Map.Entry<Integer, String> e : p.wings.entrySet()) plan.setWingId(e.getKey(), e.getValue());
        if (p.over > 0) {
            int over = p.over, v = plan.getNumFluxVents(), c = plan.getNumFluxCapacitors();
            int first = Math.min(over, capsFirst ? v : c);
            if (capsFirst) v -= first; else c -= first;
            over -= first;
            if (capsFirst) c = Math.max(0, c - over); else v = Math.max(0, v - over);
            plan.setNumFluxVents(v);
            plan.setNumFluxCapacitors(c);
        }
        return p;
    }

    /** Hullmod names for a log line or note. */
    static String names(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) out.add(JAOSMods.name(id));
        return Misc.getAndJoined(out);
    }

    // ------------------------------------------------------------------ AI Tweaks

    /** AI Tweaks' Custom AI hullmod (0 OP, no_build_in, known from the start). */
    static final String CUSTOM_AI = "aitweaks_custom_ship_ai";

    /**
     * Custom AI goes on this hull (0.5.64, user 11/17: "a definite improvement for any ship cruiser size and up"): the
     * setting is on, AI Tweaks is enabled, the player knows the hullmod, and the hull is a capital. 0.5.80 (user 11/23:
     * cruisers "Kamikaze into enemy battle lines"): Custom AI puts a ship on Search and Destroy, so cruisers left the
     * line. Frigates and destroyers keep whatever they have (never_remove keeps it on any ship that has it).
     */
    static boolean customAiWanted(ShipAPI.HullSize size, java.util.Collection<String> known) {
        boolean on;
        try { on = Global.getSettings().getModManager().isModEnabled("aitweaks"); } catch (Throwable t) { on = false; }
        return customAiWanted(JAOSettings.customAi, on, size, known);
    }

    static boolean customAiWanted(boolean setting, boolean aiTweaks, ShipAPI.HullSize size, java.util.Collection<String> known) {
        return setting && aiTweaks && size == ShipAPI.HullSize.CAPITAL_SHIP
                && known != null && known.contains(CUSTOM_AI);
    }

    /** 0.5.80: Custom AI comes off cruisers (0.5.64 to 0.5.79 put it there) while the setting is on and AI Tweaks is enabled. */
    static boolean customAiDrop(ShipAPI.HullSize size) {
        boolean on;
        try { on = Global.getSettings().getModManager().isModEnabled("aitweaks"); } catch (Throwable t) { on = false; }
        return customAiDrop(JAOSettings.customAi, on, size);
    }

    static boolean customAiDrop(boolean setting, boolean aiTweaks, ShipAPI.HullSize size) {
        return setting && aiTweaks && size == ShipAPI.HullSize.CRUISER;
    }

    /** never_remove keeps the hullmod on this hull (0.5.80: except Custom AI on a cruiser, customAiDrop). */
    static boolean neverRemove(String id, ShipAPI.HullSize size) {
        if (CUSTOM_AI.equals(id) && customAiDrop(size)) return false;
        return JAORatings.ruleMatches("never_remove", id);
    }

    // ------------------------------------------------------------------ s-mod sets (Navarchy of Tania's Ancient Frame)

    /**
     * jao_rules smod_lost (0.5.66): on hulls with the built-in hullmod (id), the hull's script deletes the s-mod before
     * the @ once every s-mod after it is built in (Ancient Frame swaps an Integrated Point Defense AI s-mod for Expanded
     * Cargo Holds and a Flux Coil s-mod for Expanded Magazines): the story point would be lost, so AO never builds it.
     */
    static boolean smodLost(List<String[]> rules, String smod, java.util.Collection<String> builtIns, java.util.Collection<String> smods) {
        if (smod == null || builtIns == null || smods == null) return false;
        for (String[] r : rules) {
            if (!"smod_lost".equals(r[0]) || !builtIns.contains(r[1])) continue;
            int at = r[2].indexOf('@');
            if (at <= 0 || !r[2].substring(0, at).trim().equals(smod)) continue;
            boolean all = true;
            for (String need : r[2].substring(at + 1).split(";")) if (!need.trim().isEmpty() && !smods.contains(need.trim())) all = false;
            if (all) return true;
        }
        return false;
    }

    static boolean smodLost(String smod, java.util.Collection<String> builtIns, java.util.Collection<String> smods) {
        return smodLost(JAORatings.rules(), smod, builtIns, smods);
    }

    /**
     * jao_rules smod_set (0.5.66): on hulls with the built-in hullmod (id), these s-mods together add a set bonus (Ancient
     * Frame: Solaris Sensor Suite and five more). An s-mod of a complete set is never taken off by s-mod changes, since
     * the hull's script then removes the set's bonus.
     */
    static boolean inCompleteSet(List<String[]> rules, String smod, java.util.Collection<String> builtIns, java.util.Collection<String> smods) {
        if (smod == null || builtIns == null || smods == null) return false;
        for (String[] r : rules) {
            if (!"smod_set".equals(r[0]) || !builtIns.contains(r[1])) continue;
            List<String> set = new ArrayList<>();
            for (String s : r[2].split(";")) if (!s.trim().isEmpty()) set.add(s.trim());
            if (set.contains(smod) && smods.containsAll(set)) return true;
        }
        return false;
    }

    static boolean inCompleteSet(String smod, java.util.Collection<String> builtIns, java.util.Collection<String> smods) {
        return inCompleteSet(JAORatings.rules(), smod, builtIns, smods);
    }

    // ------------------------------------------------------------------ Reverse Engineered Private Arsenal

    /**
     * Reverse Engineered Private Arsenal (re_private_arsenal, 0.5.66, user 11/18): the Private Arsenal at a colony with a
     * Reverse Engineering Hub sells 50 of every item the hub reverse-engineered, at base value x 2.0 / 1.6 / 1.0 / 0.4 / 0
     * by the hub's core (none / gamma / beta / alpha / omega; PrivateArsenalSubmarketPlugin.getTariff = mult - 1, and
     * isFreeTransfer when the mult is 0). The Reverse Engineering Storage next to it holds what the hub is analysing
     * (free transfer); 0.5.81: Optimize fleet puts one of each item the hub hasn't done in it (JAOReverse).
     */
    static final String REPA_ARSENAL = "RePA_PrivateArsenalSub";

    /** A free-transfer submarket that is still a shop: the Private Arsenal with an Omega core sells at 0. */
    static boolean freeShop(String submarketId) {
        return REPA_ARSENAL.equals(submarketId);
    }

    /**
     * Order fleet weapons: what every free Private Arsenal holds counts as owned stock, like storage (an Omega-core hub
     * gives its stock away). A priced arsenal is a shop: Optimize fleet buys there when docked with Legal shops on.
     */
    static void addFreeArsenals(Map<String, Integer> w, Map<String, Integer> f) {
        try {
            for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
                if (m == null || !m.isPlayerOwned() || !m.hasSubmarket(REPA_ARSENAL)) continue;
                com.fs.starfarer.api.campaign.econ.SubmarketAPI sub = m.getSubmarket(REPA_ARSENAL);
                if (sub == null || sub.getPlugin() == null || !sub.getPlugin().isFreeTransfer()) continue;
                CargoAPI c = sub.getPlugin().getCargo();
                if (c != null) JAOFleetPlan.addCargo(c, w, f);
            }
        } catch (Throwable t) {
            Global.getLogger(JAOCompat.class).warn("Auto Outfitting: couldn't read the Private Arsenal", t);
        }
    }

    // ------------------------------------------------------------------ cargo

    /**
     * Removes LPCs their own mod deletes from cargo (jao_rules purge_lpc) from fleet cargo and every storage: 0.5.1's
     * Optimize fleet took Nimbus drones off Trinity Worlds carriers into cargo, and the leftovers went to storage. Returns
     * how many.
     */
    static int purgeLpcs() {
        List<String> pats = new ArrayList<>();
        for (String[] r : JAORatings.rules()) if (r[0].equals("purge_lpc") && !r[1].isEmpty()) pats.add(r[1]);
        if (pats.isEmpty()) return 0;
        int n = 0;
        try {
            n += purge(Global.getSector().getPlayerFleet().getCargo(), pats);
            for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
                CargoAPI c = Misc.getStorageCargo(m);
                if (c != null) n += purge(c, pats);
            }
        } catch (Throwable t) {
            Global.getLogger(JAOCompat.class).warn("Auto Outfitting: couldn't check for script-only LPCs", t);
        }
        if (n > 0) Global.getLogger(JAOCompat.class).info("Auto Outfitting: removed " + n + " script-only LPCs (drones their own mod deletes from cargo)");
        return n;
    }

    /**
     * A wing no shop sells and no fight drops (tagged no_drop, no_sell or restricted), or one the game doesn't know. A
     * purge rule never deletes an LPC the player can buy (0.5.60: nimbus_tw_* matched IX's real Nimbus LPC).
     */
    static boolean scriptOnly(String wingId) {
        try {
            com.fs.starfarer.api.loading.FighterWingSpecAPI spec = Global.getSettings().getFighterWingSpec(wingId);
            if (spec == null) return true;
            return spec.hasTag("no_drop") || spec.hasTag("no_sell") || spec.hasTag("restricted");
        } catch (Throwable t) {
            return true;
        }
    }

    private static int purge(CargoAPI c, List<String> pats) {
        int n = 0;
        for (CargoAPI.CargoItemQuantity<String> q : c.getFighters()) {
            String id = q.getItem();
            if (id == null || !scriptOnly(id)) continue;
            for (String p : pats) {
                if (!JAORatings.glob(p, id)) continue;
                n += q.getCount();
                c.removeFighters(id, q.getCount());
                break;
            }
        }
        return n;
    }
}
