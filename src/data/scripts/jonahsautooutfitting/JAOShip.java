package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.HullModItemManager;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.plugins.AutofitPlugin.AutofitPluginDelegate;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableFighter;
import com.fs.starfarer.api.plugins.AutofitPlugin.AvailableWeapon;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The game side of the search: ship numbers for a hullmod set, read from a scratch fleet member (hullmod effects
 * and fleet-wide skills applied by the game itself, research engine_report.md 5.1), weapon OP with the ship's OP
 * modifiers, and hullmod legality the way CoreAutofitPlugin.addModIfPossible checks it.
 */
final class JAOShip implements JAOSearch.World {
    final ShipVariantAPI work;
    final ShipHullSpecAPI hull;
    final FleetMemberAPI scratch;
    final MutableCharacterStatsAPI cstats;
    final AutofitPluginDelegate delegate;
    final int size, budget, maxVents, maxCaps, personality;
    final boolean largeBallistic;
    private final Map<String, Float> opCache = new HashMap<>();
    private final Map<String, Boolean> addCache = new HashMap<>();
    private String modsKey = "";
    /** Hullmods treated as s-mods (0 OP, s-mod effects); set by the s-mod planner. Original perma mods stay as they are. */
    Set<String> smods = new java.util.LinkedHashSet<>();
    private final Set<String> originalPerma;
    /** Hullmod applicability for ships off the refit screen (JAOCheckShip), made on first use. */
    private JAOCheckShip checkShip;
    /** The ship's officer (or the player on the flagship, or an AI core), null for none or an inactive officer. */
    final PersonAPI captain;
    /** OP of the weapons and wings left on the ship before the search (kept, owned by mod code), built-ins excluded. */
    private final float fixedWeaponOp;
    /** Captain skills applied to the scratch stats (skill ids at elite or not), for the fit log. */
    final Set<String> captainSkills = new TreeSet<>();

    /** budget = OP this search may spend (hull OP minus what it doesn't own). */
    JAOShip(ShipVariantAPI current, AutofitPluginDelegate delegate, MutableCharacterStatsAPI cstats, int budget, int maxVents, int maxCaps) {
        this.delegate = delegate;
        this.cstats = cstats;
        this.budget = budget;
        this.maxVents = maxVents;
        this.maxCaps = maxCaps;
        hull = current.getHullSpec();
        size = Math.max(0, JAORatings.sizeIndex(hull.getHullSize()));
        work = current.clone();
        originalPerma = new java.util.HashSet<>(work.getPermaMods());
        work.setNumFluxVents(0);
        work.setNumFluxCapacitors(0);
        scratch = Global.getFactory().createFleetMember(FleetMemberType.SHIP, work);
        // the real member's id (0.5.46, build audit): hullmods that key on it (IndEvo Forge World Experiment's rolled stats,
        // Starship Legends traits) then show this ship's own numbers, and no new save entries are made per test
        try {
            FleetMemberAPI real = delegate.getFleetMember();
            if (real != null && real.getId() != null) scratch.setId(real.getId());
        } catch (Throwable ignored) { }
        PersonAPI player = Global.getSector() == null ? null : Global.getSector().getPlayerPerson();
        CampaignFleetAPI fleet = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
        if (player != null) scratch.setFleetCommanderForStats(player, fleet == null ? null : fleet.getFleetData());
        if (fleet != null) JAORefl.setFleetDataForStats(scratch, fleet.getFleetData());
        boolean lb = false;
        for (WeaponSlotAPI s : hull.getAllWeaponSlotsCopy()) {
            WeaponType t = s.getWeaponType();
            if (s.getSlotSize() == WeaponSize.LARGE && (t == WeaponType.BALLISTIC || t == WeaponType.HYBRID || t == WeaponType.COMPOSITE || t == WeaponType.UNIVERSAL)) lb = true;
        }
        largeBallistic = lb;
        PersonAPI who = personFor(delegate);
        personality = personalityOf(who);
        captain = activeCaptain(who);
        // the test ship carries the real captain (0.5.44, build audit): fleet skills that depend on having an officer
        // (Support Doctrine only without one; Wolfpack, Carrier Group, Cybernetic Augmentation only with one) then see
        // the right state; the game's updateStats still leaves personal skills to applyCaptain (no fleet data)
        if (captain != null) {
            try { scratch.setCaptain(captain); } catch (Throwable ignored) { }
        }
        float fw = 0f;
        try { fw = work.computeWeaponOPCost(cstats); } catch (Throwable ignored) { }
        fixedWeaponOp = fw;
    }

    /** The member's captain whose skills count: an active officer, the player on the flagship, an AI core. */
    /**
     * A phase ship (0.5.44, build audit): the hull is phase, or its defense is a phase cloak. A PHASE defense slot that
     * holds a damper or flak (Vanguard, Invictus and 87 more) is not.
     */
    static boolean phaseCloak(ShipHullSpecAPI h) {
        if (h == null) return false;
        if (h.isPhase()) return true;
        if (h.getShieldType() != ShieldType.PHASE) return false;
        try {
            com.fs.starfarer.api.combat.ShipSystemSpecAPI d = Global.getSettings().getShipSystemSpec(h.getShipDefenseId());
            return d == null || d.isPhaseCloak();
        } catch (Throwable t) {
            return true;
        }
    }

    static PersonAPI captainOf(FleetMemberAPI m) {
        return activeCaptain(m == null ? null : m.getCaptain());
    }

    /** The person in command whose skills count, or null (none, a default captain, an inactive officer). */
    static PersonAPI activeCaptain(PersonAPI c) {
        if (c == null || c.isDefault() || c.getStats() == null || !activeOfficer(c)) return null;
        return c;
    }

    /**
     * Who commands the ship this fit is for: the captain the fleet plan's officer placement gives it (0.5.39; null for
     * none), otherwise the member's own.
     */
    static PersonAPI personFor(AutofitPluginDelegate d) {
        if (d instanceof JAOFleetPlan.PlanDelegate && ((JAOFleetPlan.PlanDelegate) d).captainSet) return ((JAOFleetPlan.PlanDelegate) d).captain;
        FleetMemberAPI m = d == null ? null : d.getFleetMember();
        return m == null ? null : m.getCaptain();
    }

    @Override
    public float fixedWeaponOp() { return fixedWeaponOp; }

    /** JAOUtility numbers (survey, Treasure Hunt, sensors, burn) with these hullmods and these as s-mods. */
    float[] utility(Set<String> mods, Set<String> asSmods) {
        Set<String> keep = smods;
        try {
            smods = new java.util.LinkedHashSet<>(asSmods);
            setMods(mods);
            scratch.setVariant(work, false, true);
            return JAOUtility.read(scratch.getStats());
        } catch (Throwable t) {
            return new float[4];
        } finally {
            smods = keep;
        }
    }

    private static final String SKILLS = "com.fs.starfarer.api.impl.campaign.skills.";
    private static float oeFlux = -1f, oeCap = -1f, ewmNear = -1f, ewmFar = -1f, paSpeed = -1f, paEmp = -1f;
    private static boolean skillWarned;

    /** Ordnance Expertise, Energy Weapon Mastery and Polarized Armor numbers from the game's classes (defaults if missing). */
    private static void readSkillNumbers() {
        if (oeFlux >= 0f) return;
        float f = 1.5f, c = 20f, near = 600f, far = 1000f, spd = 20f, emp = 50f;
        try { f = com.fs.starfarer.api.impl.campaign.skills.OrdnanceExpertise.FLUX_PER_OP; c = com.fs.starfarer.api.impl.campaign.skills.OrdnanceExpertise.CAP_PER_OP; } catch (Throwable ignored) { }
        try { near = com.fs.starfarer.api.impl.campaign.skills.EnergyWeaponMastery.MIN_RANGE; far = com.fs.starfarer.api.impl.campaign.skills.EnergyWeaponMastery.MAX_RANGE; } catch (Throwable ignored) { }
        try { spd = com.fs.starfarer.api.impl.campaign.skills.PolarizedArmor.MAX_SPEED_BONUS; emp = com.fs.starfarer.api.impl.campaign.skills.PolarizedArmor.EMP_BONUS_PERCENT; } catch (Throwable ignored) { }
        oeFlux = f; oeCap = c; ewmNear = near; ewmFar = far; paSpeed = spd; paEmp = emp;
    }

    /** Hard flux level Polarized Armor is scored at (the skill uses 50% for ships without shields). */
    static final float POLARIZED_FLUX = 0.5f;

    /**
     * The captain's ship skill effects on the scratch stats, the way CharacterStats.applyPersonalToStats does it for a
     * member in a fleet (the scratch member is in none, so the game skips them): every SHIP effect the captain's level
     * reaches, with the game's own effect classes and the same ids. Ordnance Expertise's flux per weapon OP depends on
     * the fit, so the model adds it (JAOModel.ordnance); Polarized Armor's flux-based speed and EMP resistance run in a
     * combat listener, so they're added here at POLARIZED_FLUX. Returns true when Polarized Armor's listener applies.
     */
    private boolean applyCaptain(MutableShipStatsAPI st, JAOModel.Ship s) {
        if (captain == null) return false;
        boolean polarized = false;
        try {
            MutableCharacterStatsAPI cs = captain.getStats();
            ShipAPI.HullSize hs = hull.getHullSize();
            readSkillNumbers();
            for (MutableCharacterStatsAPI.SkillLevelAPI sl : cs.getSkillsCopy()) {
                com.fs.starfarer.api.characters.SkillSpecAPI skill = sl == null ? null : sl.getSkill();
                if (skill == null || skill.getEffectsAPI() == null) continue;
                List<com.fs.starfarer.api.characters.SkillSpecAPI.SkillEffectSpecAPI> effects = skill.getEffectsAPI();
                for (int i = 0; i < effects.size(); i++) {
                    com.fs.starfarer.api.characters.SkillSpecAPI.SkillEffectSpecAPI e = effects.get(i);
                    if (e == null || e.getType() != com.fs.starfarer.api.characters.SkillEffectType.SHIP) continue;
                    float level = cs.getSkillLevel(e.getGoverningSkill());
                    if (level < e.getRequiredSkillLevel()) continue;
                    com.fs.starfarer.api.characters.ShipSkillEffect fx = e.getAsShipEffect();
                    if (fx == null) continue;
                    String cls = fx.getClass().getName();
                    if ((SKILLS + "OrdnanceExpertise$Level1").equals(cls)) { s.fluxPerWeaponOp += oeFlux; captainSkills.add(skill.getId()); continue; }
                    if ((SKILLS + "OrdnanceExpertise$Level3").equals(cls)) { s.capPerWeaponOp += oeCap; captainSkills.add(skill.getId()); continue; }
                    if ((SKILLS + "PolarizedArmor$Level2").equals(cls) || (SKILLS + "PolarizedArmor$Level2A").equals(cls)) polarized = true;
                    try {
                        fx.apply(st, hs, skill.getId() + "_ship_" + i, level);
                        captainSkills.add(skill.getId());
                    } catch (Throwable t) {
                        warnSkill(skill.getId(), t);
                    }
                }
            }
        } catch (Throwable t) {
            warnSkill("captain", t);
        }
        return polarized;
    }

    private static void warnSkill(String id, Throwable t) {
        if (skillWarned) return;
        skillWarned = true;
        Global.getLogger(JAOShip.class).warn("Auto Outfitting: couldn't apply captain skill " + id + " to a fit; skipped", t);
    }

    static int personalityOf(FleetMemberAPI m) {
        return personalityOf(m == null ? null : m.getCaptain());
    }

    static int personalityOf(PersonAPI c) {
        // a ship without an officer fights with the personality the faction doctrine's aggression sets (Command >
        // Doctrine; FactionPersonalityPickerPluginImpl: 1 cautious, 2 steady, 3 aggressive, 4 aggressive or reckless,
        // 5 reckless). 0.5.12: it was always taken as steady (research builds_update_1106 rule 3)
        // 0.98.5a: an inactive officer (past the officer limit) doesn't command the ship
        String p = c == null || c.isDefault() || c.getPersonalityAPI() == null || !activeOfficer(c) ? doctrinePersonality() : c.getPersonalityAPI().getId();
        if ("aggressive".equals(p) || "reckless".equals(p)) return 1;
        if ("cautious".equals(p) || "timid".equals(p)) return 2;
        return 0;
    }

    /**
     * False for an officer set inactive (0.98.5a: more officers than the limit allows, only that many active). The same
     * memory flag Misc.isOfficerActive reads; 0.98a never sets it, so every officer counts as active there.
     */
    static boolean activeOfficer(PersonAPI c) {
        try {
            com.fs.starfarer.api.campaign.rules.MemoryAPI mem = c.getMemoryWithoutUpdate();
            return mem == null || !mem.contains("$inactive") || !mem.getBoolean("$inactive");
        } catch (Throwable t) {
            return true;
        }
    }

    static String doctrinePersonality() {
        try {
            int a = Global.getSector().getPlayerFaction().getDoctrine().getAggression();
            return a <= 1 ? "cautious" : a == 2 ? "steady" : "aggressive";
        } catch (Throwable t) {
            return "steady";
        }
    }

    String key(Set<String> mods) {
        return String.join(",", new TreeSet<>(mods)) + "|s:" + String.join(",", new TreeSet<>(smods));
    }

    /** Puts exactly these non-built-in hullmods on the work variant, the planned s-mods as s-mods. */
    void setMods(Set<String> mods) {
        String key = key(mods);
        if (key.equals(modsKey)) return;
        for (String id : new ArrayList<>(work.getPermaMods())) {
            if (!originalPerma.contains(id) && !smods.contains(id)) { work.removePermaMod(id); work.removeMod(id); }
        }
        for (String id : new ArrayList<>(work.getNonBuiltInHullmods())) if (!mods.contains(id) && !smods.contains(id)) work.removeMod(id);
        for (String id : smods) if (!work.getSMods().contains(id)) work.addPermaMod(id, true);
        for (String id : mods) if (!work.hasHullMod(id)) work.addMod(id);
        modsKey = key;
    }

    @Override
    public JAOModel.Ship ship(Set<String> mods) {
        JAOCoop.checkpoint();
        setMods(mods);
        scratch.setVariant(work, false, true);
        MutableShipStatsAPI st = scratch.getStats();
        JAOModel.Ship s = new JAOModel.Ship();
        s.size = size;
        // the captain's skills (0.5.29): the stats read below carry them
        boolean polarized = applyCaptain(st, s);
        // Support Doctrine on a ship without an officer gives Ordnance Expertise's 1.5 flux a second per weapon OP, which
        // the game reads from the test variant's kept weapons only: count it per weapon OP of the whole fit (0.5.44)
        if (captain == null && supportDoctrine()) {
            // its only dissipation mod; the effect id is <skill>_ships_<i> (fleet effects) or _ship_<i>, so clear both
            for (int i = 0; i < 6; i++) { try { st.getFluxDissipation().unmodify("support_doctrine_ships_" + i); st.getFluxDissipation().unmodify("support_doctrine_ship_" + i); } catch (Throwable ignored) { } }
            readSkillNumbers();
            s.fluxPerWeaponOp += oeFlux;
        }
        // UAF Secondary Focus (0.5.43): a fixed bonus the stats carry (its per-slot counts are never used by UAF's own code)
        // Threat fragment swarm (0.5.38): the fragments its weapons spend, by hull size, with Secondary Fabricator and
        // Fragment Coordinator (dynamic stats, read the way FragmentSwarmHullmod reads them)
        if (work.hasHullMod("fragment_swarm")) fragmentSwarm(st, hull.getHullSize(), s);
        // hullmods that deal damage by themselves (0.5.40): weapons without a mount in the model
        s.hullmodWeapons = hullmodWeapons();
        s.hull = st.getHullBonus().computeEffective(hull.getHitpoints());
        s.armor = st.getArmorBonus().computeEffective(hull.getArmorRating());
        // armor counted for damage reduction (0.5.44, build audit: UAF Supercap, Imperial Armor Package, TBJ Hek)
        try {
            float eff = st.getEffectiveArmorBonus().computeEffective(Math.max(1f, s.armor));
            if (s.armor > 0f && eff > 0f) s.armorEffMult = JAOModel.clamp(eff / s.armor, 0.05f, 20f);
        } catch (Throwable ignored) { }
        s.cap = st.getFluxCapacity().getModifiedValue();
        s.diss = st.getFluxDissipation().getModifiedValue();
        ShieldType type = hull.getShieldType();
        s.phase = phaseCloak(hull);
        s.shield = (type == ShieldType.FRONT || type == ShieldType.OMNI) && hull.getShieldSpec() != null && !work.hasHullMod(HullMods.SHIELD_SHUNT) && !ruleOn("no_shield");
        if (s.shield) {
            s.shieldEff = hull.getShieldSpec().getFluxPerDamageAbsorbed() * st.getShieldAbsorptionMult().getModifiedValue();
            s.shieldTaken = st.getShieldDamageTakenMult().getModifiedValue();
            s.upkeep = hull.getShieldSpec().getUpkeepCost() * st.getShieldUpkeepMult().getModifiedValue();
            s.arc = st.getShieldArcBonus().computeEffective(hull.getShieldSpec().getArc());
        }
        s.speed = st.getMaxSpeed().getModifiedValue();
        // the ship's own time rate (0.5.44: timeflow hulls and hullmods): it fires, dissipates and moves faster
        try {
            float tm = st.getTimeMult().getModifiedValue();
            if (tm > 0.2f && Math.abs(tm - 1f) > 0.001f) {
                s.diss *= tm;
                s.speed *= tm;
            }
        } catch (Throwable ignored) { }
        s.baseSpeed = hull.getEngineSpec() == null ? s.speed : hull.getEngineSpec().getMaxSpeed();
        if (hull.getEngineSpec() != null && hull.getEngineSpec().getMaxTurnRate() > 0f && hull.getEngineSpec().getAcceleration() > 0f) {
            float turn = st.getMaxTurnRate().getModifiedValue() / hull.getEngineSpec().getMaxTurnRate();
            float accel = st.getAcceleration().getModifiedValue() / hull.getEngineSpec().getAcceleration();
            s.maneuver = (float) Math.sqrt(Math.max(0.05f, turn) * Math.max(0.05f, accel));
        }
        s.ppt = st.getPeakCRDuration().computeEffective(hull.getNoCRLossTime());
        s.turnRate = st.getMaxTurnRate().getModifiedValue();
        s.baseTurn = hull.getEngineSpec() == null ? 0f : hull.getEngineSpec().getMaxTurnRate();
        s.baseCap = hull.getFluxCapacity();
        bonus(s, JAOModel.BAL, st.getBallisticWeaponRangeBonus());
        bonus(s, JAOModel.ENE, st.getEnergyWeaponRangeBonus());
        bonus(s, JAOModel.MIS, st.getMissileWeaponRangeBonus());
        StatBonus beam = st.getBeamWeaponRangeBonus();
        s.beamPct = beam.getPercentMod(); s.beamFlat = beam.getFlatBonus(); s.beamMult = beam.getMult();
        float th = st.getWeaponRangeThreshold().getModifiedValue();
        if (th > 0f) { s.threshold = th; s.pastThreshold = st.getWeaponRangeMultPastThreshold().getModifiedValue(); }
        s.dmgMult[JAOModel.BAL] = st.getBallisticWeaponDamageMult().getModifiedValue();
        s.dmgMult[JAOModel.ENE] = st.getEnergyWeaponDamageMult().getModifiedValue();
        s.dmgMult[JAOModel.MIS] = st.getMissileWeaponDamageMult().getModifiedValue();
        s.beamDmgMult = st.getBeamWeaponDamageMult().getModifiedValue();
        s.rofMult[JAOModel.BAL] = st.getBallisticRoFMult().getModifiedValue();
        s.rofMult[JAOModel.ENE] = st.getEnergyRoFMult().getModifiedValue();
        s.rofMult[JAOModel.MIS] = st.getMissileRoFMult().getModifiedValue();
        try {
            float tm = st.getTimeMult().getModifiedValue();
            if (tm > 0.2f && Math.abs(tm - 1f) > 0.001f) for (int c = 0; c < s.rofMult.length; c++) s.rofMult[c] *= tm;
        } catch (Throwable ignored) { }
        s.fluxMult[JAOModel.BAL] = st.getBallisticWeaponFluxCostMod().computeEffective(1f);
        s.fluxMult[JAOModel.ENE] = st.getEnergyWeaponFluxCostMod().computeEffective(1f);
        s.fluxMult[JAOModel.MIS] = st.getMissileWeaponFluxCostMod().computeEffective(1f);
        s.beamFluxMult = st.getBeamWeaponFluxCostMult().getModifiedValue();
        // per 10 rounds (0.5.44): a flat bonus (RAT Missile Reserves +2) read on 1 round counted as x3
        s.ammoMult = st.getMissileAmmoBonus().computeEffective(10f) / 10f;
        s.pdDmg = st.getDamageToMissiles().getModifiedValue();
        s.armorTaken = st.getArmorDamageTakenMult().getModifiedValue();
        s.hullTaken = st.getHullDamageTakenMult().getModifiedValue();
        // listener hullmods the scratch member can't see (research hullmods report 1; jao_hullmods.csv "listener")
        if (work.hasHullMod("ballistic_rangefinder")) {
            s.bfSmall = largeBallistic ? 200f : 100f;
            s.bfMedium = largeBallistic ? 100f : 0f;
            s.bfCap = largeBallistic ? 900f : 800f;
        }
        s.hsa = work.hasHullMod("high_scatter_amp");
        s.interferenceMult = work.hasHullMod("fluxbreakers") ? 0.5f : 1f;
        s.noVent = st.getVentRateMult().getModifiedValue() <= 0f;
        // more listener hullmods, modelled roughly: Missile Autoloader reloads small finite missiles (+6/9/15/24
        // capacity, 5 s cooldown), Phase Anchor makes phasing free and raises dissipation/recharge while phased
        // (x2 in 0.98a, x1.5 in 0.98.5a; read from the game)
        // as an s-mod the Autoloader waits 10 s after a reload instead of 5 (its s-mod penalty)
        if (work.hasHullMod("missile_autoloader")) s.ammoMult *= work.getSMods().contains("missile_autoloader") ? 1.35f : 1.5f;
        if (work.hasHullMod("phase_anchor") && s.phase) s.extra += JAOModel.phaseAnchorExtra(phaseAnchorMult());
        // Adaptive Phase Coils (0.98a): a phased ship keeps its speed to 50% more hard flux, so it can phase in, fire and
        // get out; experts take it or Phase Anchor on every phase ship (research meta_report 5, builds_update_1106)
        if (work.hasHullMod("adaptive_coils") && s.phase) s.extra += ADAPTIVE_COILS;
        // Shield Conversion - Omni on slow low-tech front-shield hulls (0.5.12; research builds_update_1106 2.5, several
        // sources incl. Grievous69's 0.98 guide): the AI swings the omni shield onto torpedoes and big shells and lets the
        // armor take small arms. AI behaviour the stats can't show (they only see the arc -30%), so a flat bonus
        if (work.hasHullMod("adaptiveshields") && hull.getShieldType() == ShieldType.FRONT && lowTech(hull)
                && hull.getEngineSpec() != null && hull.getEngineSpec().getMaxTurnRate() <= 30f) s.extra += OMNI_BONUS;
        // Valkyrian Brunhild Cyclonic Core (and Micro): +100 base range to "valk" weapons through a combat-only
        // WeaponBaseRangeModifier (checked in Valkyrians.jar). Valhalla Core (Elegy) gives +20% damage above 66% flux,
        // so it runs hot; Safety Overrides ships can't vent anyway (research build_philosophy R2.5, R3.1, R3.3)
        if (work.hasHullMod("valk_bryncore") || work.hasHullMod("valk_microbryncore")) s.valkRange = 100f;
        if (work.hasHullMod("valk_valhallacore")) { s.fluxSoft = Math.max(s.fluxSoft, 1.45f); s.fluxLimit = Math.max(s.fluxLimit, 1.75f); }
        if (s.noVent) s.fluxLimit = Math.max(s.fluxLimit, 1.75f);
        s.noAutoEscort = hull.getHints().contains(ShipHullSpecAPI.ShipTypeHints.NO_AUTO_ESCORT);
        // Seeker's nano-plague: free on its own plague hulls, a third off with Hardened Subsystems (JAOCompat)
        s.plagueMult = JAOCompat.plagueMult(work);
        // d-mods and the rest the live stats above don't carry (0.5.10, the user 11/06: build against every ship's
        // d-mods): max CR, the carrier deck, turret turning and spread, malfunctions
        s.maxCr = maxCr(st.getMaxCombatReadiness());
        float[] crLines = crThresholds(st);
        s.malfAt = crLines[0]; s.critAt = crLines[1]; s.degradeAt = crLines[2];
        // Escort Package built in: shield (0.98.5a also armor) damage taken cut near a bigger friendly ship, a combat
        // script the stats don't carry; scored for the share of a fight an escort stays near one
        if (work.getSMods().contains(ESCORT)) {
            float[] e = JAOModel.escortSMod(size, escortArmor(), escortBonus());
            s.shieldTaken *= 1f - e[0] * JAOModel.ESCORT_UPTIME;
            s.armorTaken *= 1f - e[1] * JAOModel.ESCORT_UPTIME;
        }
        float deploy = hull.getCRToDeploy();
        if (deploy > 1f) deploy /= 100f;
        s.deployCr = JAOModel.clamp(st.getCRPerDeploymentPercent().computeEffective(deploy), 0f, 1f);
        if (Global.getSettings().computeNumFighterBays(work) > 0) {
            float dmodEffect = dyn(st, "dmod_effect_mult");
            float wingRange = st.getFighterWingRange().computeEffective(1f);
            // Defensive Targeting Array sets wing range to 0: the wings stay at the ship, priced by role in the model
            if (wingRange <= 0.05f) { s.dta = true; wingRange = 1f; }
            s.deck = JAOModel.deckFactor(mult(st.getFighterRefitTimeMult().getModifiedValue()), dyn(st, "replacement_rate_decrease_mult"),
                    dyn(st, "replacement_rate_increase_mult"), wingRange, JAOCompat.wingMult(work, dmodEffect));
        }
        // weapon-boost systems the AI fires whenever those weapons are in range (research ai_behaviour_1106 SYS-2/3):
        // Accelerated Ammo Feeder doubles ballistic rate at half flux per shot for 5 s in about 17 (+29% ballistic),
        // High Energy Focus about +7.5% energy damage
        // a shorter system cooldown or faster recharge (Systems Expertise) keeps them on longer
        String sys = hull.getShipSystemId();
        float sysUp = systemUptime(st);
        // 0.5.44: every system in jao_systems.csv (Accelerated Ammo Feeder x1.3, High Energy Focus x1.115, Lidar Array
        // +35% range, missile forges, fighter feeds and the mod copies); applied at the end, once the stats are read
        s.turnMult = mult(st.getWeaponTurnRateBonus().computeEffective(1f));
        s.beamTurnMult = mult(st.getBeamWeaponTurnRateBonus().computeEffective(1f));
        s.recoilMult = mult(st.getMaxRecoilMult().getModifiedValue());
        s.malfunction = Math.max(0f, st.getWeaponMalfunctionChance().getModifiedValue());
        s.ammoBal = mult(st.getBallisticAmmoBonus().computeEffective(10f) / 10f);
        s.ammoEn = mult(st.getEnergyAmmoBonus().computeEffective(10f) / 10f);
        s.regenBal = mult(st.getBallisticAmmoRegenMult().getModifiedValue());
        s.regenEn = mult(st.getEnergyAmmoRegenMult().getModifiedValue());
        s.empTaken = Math.max(0f, st.getEmpDamageTakenMult().getModifiedValue());
        s.ventRate = Math.max(0f, st.getVentRateMult().getModifiedValue());
        skillStats(st, s);
        if (polarized) {
            // Polarized Armor's combat listener: up to +20 speed and -50% EMP damage taken by hard flux level
            s.speed += paSpeed * POLARIZED_FLUX;
            s.empTaken *= 1f - JAOModel.clamp(paEmp / 100f * POLARIZED_FLUX, 0f, 1f);
        }
        s.maxVents = maxVents;
        s.maxCaps = maxCaps;
        s.personality = personality;
        applySystems(s, sysUp);
        applyHullmodRules(s);
        return s;
    }

    /**
     * Max CR the way the game builds it (CRPluginImpl: 70% "Basic maintenance", then skills, hullmods and d-mods),
     * without the crew shortfall (the scratch member has no crew) and without Seeker's plague debuff (the score prices
     * the plague from the weapons it fits).
     */
    static float maxCr(MutableStat m) {
        float flat = 0f;
        for (Map.Entry<String, MutableStat.StatMod> e : m.getFlatMods().entrySet()) {
            String k = e.getKey();
            if (k == null || CR_SKIP.contains(k) || e.getValue() == null) continue;
            flat += e.getValue().getValue();
        }
        return JAOModel.clamp((m.getBaseValue() + JAOModel.CR_BASE + flat) * m.getMult(), 0f, 1f);
    }

    private static final Set<String> CR_SKIP = new java.util.HashSet<>(java.util.Arrays.asList(
            "crew skill bonus", "crew understrength", "SKR_plagueWarning"));

    /**
     * The ship's CR thresholds from the game's CR plugin with its stats: {malfunction, critical malfunction, degrade}.
     * CRPluginImpl scales all three by the dynamic stat cr_malfunction_range; the degrade line isn't on the plugin's
     * interface, so it takes the same scale as the malfunction line. The defaults if the plugin can't be read.
     */
    static float[] crThresholds(MutableShipStatsAPI st) {
        try {
            com.fs.starfarer.api.combat.CombatReadinessPlugin crp = Global.getSettings().getCRPlugin();
            float malf = crp.getMalfunctionThreshold(st), crit = crp.getCriticalMalfunctionThreshold(st), base = crp.getMalfunctionThreshold(null);
            if (malf > 0f && crit >= 0f && base > 0f) return new float[]{malf, crit, JAOModel.CR_DEGRADE * malf / base};
        } catch (Throwable ignored) { }
        return new float[]{JAOModel.CR_MALF, JAOModel.CR_CRIT, JAOModel.CR_DEGRADE};
    }

    /**
     * Stats the captain skills move that the rest of ship() doesn't read (0.5.29): damage by target size and to
     * fighters, point-defense range, missile hit points, Energy Weapon Mastery's flux-based damage, hard flux dissipation
     * with shields up, the armor's damage reduction cap and CR loss past peak time. Each read on its own, so a missing
     * stat (another game version) leaves the default.
     */
    /** Damage-dealing hullmods on the work variant (jao_hullmod_damage.csv), for this hull size and s-mod state. */
    private List<JAOModel.HullmodWeapon> hullmodWeapons() {
        List<JAOModel.HullmodWeapon> out = null;
        for (Map.Entry<String, JSONObject> e : JAORatings.hullmodDamage().entrySet()) {
            String id = e.getKey();
            if (!work.hasHullMod(id)) continue;
            boolean smod = work.getSMods().contains(id) || work.getSModdedBuiltIns().contains(id);
            JAOModel.HullmodWeapon h = JAORatings.hullmodWeapon(id, e.getValue(), size, smod);
            if (h == null) continue;
            if (out == null) out = new ArrayList<>();
            out.add(h);
        }
        return out == null ? java.util.Collections.emptyList() : out;
    }

    static void fragmentSwarm(MutableShipStatsAPI st, ShipAPI.HullSize size, JAOModel.Ship s) {
        try {
            s.fragSize = st.getDynamic().getValue(com.fs.starfarer.api.impl.campaign.ids.Stats.FRAGMENT_SWARM_SIZE_MOD,
                    com.fs.starfarer.api.impl.combat.threat.FragmentSwarmHullmod.getBaseSwarmSize(size));
            s.fragRegen = com.fs.starfarer.api.impl.combat.threat.FragmentSwarmHullmod.getBaseSwarmRespawnRateMult(size)
                    * st.getDynamic().getValue(com.fs.starfarer.api.impl.campaign.ids.Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT);
        } catch (Throwable t) {
            int i = Math.max(0, Math.min(3, s.size));
            s.fragSize = new float[]{20f, 40f, 60f, 100f}[i];
            s.fragRegen = new float[]{1f, 2f, 3f, 5f}[i];
        }
    }

    private static void skillStats(MutableShipStatsAPI st, JAOModel.Ship s) {
        try {
            s.sizeDmg[0] = mult(st.getDamageToFrigates().getModifiedValue());
            s.sizeDmg[1] = mult(st.getDamageToDestroyers().getModifiedValue());
            s.sizeDmg[2] = mult(st.getDamageToCruisers().getModifiedValue());
            s.sizeDmg[3] = mult(st.getDamageToCapital().getModifiedValue());
        } catch (Throwable ignored) { }
        try { s.fighterDmg = mult(st.getDamageToFighters().getModifiedValue()); } catch (Throwable ignored) { }
        try {
            s.pdRange = st.getNonBeamPDWeaponRangeBonus().getFlatBonus();
            s.pdBeamRange = st.getBeamPDWeaponRangeBonus().getFlatBonus();
        } catch (Throwable ignored) { }
        try { s.missileHp = mult(st.getMissileHealthBonus().computeEffective(1f)); } catch (Throwable ignored) { }
        try {
            s.ewm = Math.max(0f, st.getEnergyWeaponFluxBasedBonusDamageMagnitude().getModifiedValue());
            if (s.ewm > 0f) { readSkillNumbers(); s.ewmNear = ewmNear; s.ewmFar = ewmFar; }
        } catch (Throwable ignored) { }
        try { s.hardDiss = JAOModel.clamp(st.getHardFluxDissipationFraction().getModifiedValue(), 0f, 1f); } catch (Throwable ignored) { }
        try {
            MutableStat dr = st.getMaxArmorDamageReduction();
            s.maxDr = JAOModel.clamp(JAOModel.MAX_DR + dr.getModifiedValue() - dr.getBaseValue(), 0.5f, 0.99f);
        } catch (Throwable ignored) { }
        try { s.crLoss = Math.max(0f, st.getCRLossPerSecondPercent().computeEffective(1f)); } catch (Throwable ignored) { }
    }

    /** Share of a system cycle spent active in the stock numbers (Accelerated Ammo Feeder: 5 s in about 17). */
    static final float SYSTEM_ACTIVE = 0.3f;

    /**
     * How much longer the ship system is up against the hull's own cycle: 1 normally; Systems Expertise (cooldown -33%,
     * recharge +50%) about 1.3.
     */
    /**
     * jao_rules effects of hullmods the stats can't show (0.5.45, build audit): hull_regen (fraction of hull a second;
     * cap per second, 0 = none), shield_taken (multiplier on shield damage taken), beams_hard (beams deal hard flux on
     * shields: blank = every beam, or weapon ids split by ;), range_mult (every weapon's range). And Combat Endurance's
     * elite repair: 0.5% of hull a second, at most max(2000, half the hull) a fight.
     */
    private void applyHullmodRules(JAOModel.Ship s) {
        for (String[] r : JAORatings.rules()) {
            boolean has = false;
            switch (r[0]) {
                case "hull_regen": case "shield_taken": case "beams_hard": case "range_mult": case "weapon_range": has = work.hasHullMod(r[1]); break;
                case "combat": has = work.hasHullMod(r[1]); break;
                default: continue;
            }
            if (!has) continue;
            try {
                switch (r[0]) {
                    case "hull_regen": {
                        String[] v = r[2].split(";");
                        float rate = Float.parseFloat(v[0].trim()) * s.hull, cap = v.length > 1 ? Float.parseFloat(v[1].trim()) : 0f;
                        s.hullRegen += cap > 0f ? Math.min(rate, cap) : rate;
                        break;
                    }
                    case "shield_taken": s.shieldTaken *= Float.parseFloat(r[2]); break;
                    case "combat": {
                        // KIND:mult:share[:conditions] (0.5.49): a jao_systems effect kind, or TAKEN_SHIELD/ARMOR/HULL/ALL,
                        // ARMOR_REGEN, UPKEEP, SPEED, HARDDISS, TIME; conditions split by & (+hullmod, -hullmod, hull size,
                        // SHIELDHULL / NOSHIELDHULL = the hull spec has a front or omni shield)
                        String[] v = r[2].split(":");
                        if (v.length < 3 || (v.length > 3 && !conditionsMet(v[3]))) break;
                        JAOModel.applySystem(s, v[0].trim(), Float.parseFloat(v[1].trim()), Float.parseFloat(v[2].trim()), 1f);
                        break;
                    }
                    case "beams_hard":
                        if (r[2].isEmpty()) s.hardBeamsAll = true;
                        else {
                            java.util.Set<String> ids = new java.util.HashSet<>(s.hardBeamIds);
                            for (String id : r[2].split(";")) if (!id.trim().isEmpty()) ids.add(id.trim());
                            s.hardBeamIds = ids;
                        }
                        break;
                    case "range_mult": { float m = Float.parseFloat(r[2]); for (int c = 0; c < s.rangeMult.length; c++) s.rangeMult[c] *= m; break; }
                    case "weapon_range": {
                        List<Object[]> list = new ArrayList<>(s.weaponRange);
                        for (String part : r[2].split(";")) {
                            String[] kv = part.split("=");
                            if (kv.length != 2) continue;
                            String v = kv[1].trim();
                            boolean pct = v.endsWith("%");
                            float n = Float.parseFloat(pct ? v.substring(0, v.length() - 1) : v);
                            list.add(new Object[]{kv[0].trim(), pct ? 0f : n, pct ? n : 0f});
                        }
                        s.weaponRange = list;
                        break;
                    }
                    default: break;
                }
            } catch (RuntimeException ignored) { }
        }
        // RAT Chronos conversion or a Chronos core in command (ChronosRangeModifier)
        try {
            s.chronos = work.hasHullMod("rat_chronos_conversion") || (captain != null && "rat_chronos_core".equals(captain.getAICoreId()));
        } catch (Throwable ignored) { }
        try {
            if (captain != null && captain.getStats().getSkillLevel("combat_endurance") >= 2f) {
                s.hullRegen += 0.005f * s.hull;
                s.hullRegenCap = Math.max(s.hullRegenCap, Math.max(2000f, 0.5f * s.hull));
            }
        } catch (Throwable ignored) { }
    }

    /** A jao_rules row of this kind names a hullmod on the ship (0.5.49: no_shield). */
    boolean ruleOn(String kind) {
        for (String[] r : JAORatings.rules()) if (r[0].equals(kind) && work.hasHullMod(r[1])) return true;
        return false;
    }

    /** Conditions of a combat row, split by &: +hullmod, -hullmod, FRIGATE/DESTROYER/CRUISER/CAPITAL, SHIELDHULL, NOSHIELDHULL. */
    boolean conditionsMet(String conds) {
        for (String c : conds.split("&")) {
            c = c.trim();
            if (c.isEmpty()) continue;
            boolean shieldHull = hull.getShieldType() == ShieldType.FRONT || hull.getShieldType() == ShieldType.OMNI;
            if (c.startsWith("+")) { if (!work.hasHullMod(c.substring(1))) return false; }
            else if (c.startsWith("-")) { if (work.hasHullMod(c.substring(1))) return false; }
            else if (c.equals("SHIELDHULL")) { if (!shieldHull) return false; }
            else if (c.equals("NOSHIELDHULL")) { if (shieldHull) return false; }
            else if (!hull.getHullSize().name().startsWith(c)) return false;
        }
        return true;
    }

    /**
     * Hullmods that take cargo items when installed and never give them back (0.5.49, jao_rules paid_install: Advanced
     * Modifications' subsystems, Weapon Control Supercomputer and Crystallized Armor). Only ships that already paid get
     * them: the ship has it now, or the mod's memory flag ($ + member id + hullmod id) is set. Checked before
     * isApplicableToShip, which takes the items when the variant already has the hullmod.
     */
    boolean paidOrFree(String mod) {
        boolean listed = false;
        for (String[] r : JAORatings.rules()) if (r[0].equals("paid_install") && r[1].equals(mod)) { listed = true; break; }
        if (!listed) return true;
        try {
            FleetMemberAPI m = delegate.getFleetMember();
            if (m == null) return false;
            if (m.getVariant() != null && m.getVariant().hasHullMod(mod)) return true;
            return Global.getSector() != null && Global.getSector().getMemoryWithoutUpdate().contains("$" + m.getId() + mod);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The hull's system and defense ids, and their stats script classes for "class:" rows. */
    private List<String> systemKeys() {
        List<String> keys = new ArrayList<>();
        for (String id : new String[]{hull.getShipSystemId(), hull.getShipDefenseId()}) {
            if (id == null || id.isEmpty()) continue;
            keys.add(id);
            try {
                com.fs.starfarer.api.combat.ShipSystemSpecAPI spec = Global.getSettings().getShipSystemSpec(id);
                if (spec != null && spec.getStatsScriptClassName() != null) keys.add("class:" + spec.getStatsScriptClassName());
            } catch (Throwable ignored) { }
        }
        return keys;
    }

    /** jao_systems.csv effects of the hull's systems on the model ship (0.5.44). */
    private void applySystems(JAOModel.Ship s, float sysUp) {
        List<String> keys = systemKeys();
        if (keys.isEmpty()) return;
        // rows for the system's own id win; rows matched by its stats script only count for copies without their own rows
        boolean byId = false;
        for (String[] r : JAORatings.systems()) if (!r[0].startsWith("class:") && keys.contains(r[0])) { byId = true; break; }
        for (String[] r : JAORatings.systems()) {
            if (!keys.contains(r[0]) || (byId && r[0].startsWith("class:"))) continue;
            float mult, up;
            try { mult = Float.parseFloat(r[2]); up = Float.parseFloat(r[3]); } catch (NumberFormatException e) { continue; }
            JAOModel.applySystem(s, r[1], mult, up, sysUp);
        }
    }

    /** The fleet commander has Support Doctrine (ships without an officer get part of Helmsmanship, Ordnance Expertise and more). */
    static boolean supportDoctrine() {
        try {
            return Global.getSector() != null && Global.getSector().getPlayerStats().getSkillLevel("support_doctrine") > 0f;
        } catch (Throwable t) {
            return false;
        }
    }

    static float systemUptime(MutableShipStatsAPI st) {
        try {
            float cd = st.getSystemCooldownBonus().computeEffective(1f);
            float regen = Math.max(0.1f, st.getSystemRegenBonus().computeEffective(1f));
            // a system has a cooldown or recharging charges: the larger of the two gains counts
            float wait = Math.min(Math.max(0.1f, cd), 1f / regen);
            return 1f / (SYSTEM_ACTIVE + (1f - SYSTEM_ACTIVE) * Math.min(wait, 3f));
        } catch (Throwable t) {
            return 1f;
        }
    }

    static final String ESCORT = "escort_package";

    /** Hullmods valued only as s-mods (no single-ship value as ordinary hullmods): JAOSMods may build them in. */
    static final Set<String> SMOD_VALUE = java.util.Collections.singleton(ESCORT);

    private static Boolean escortArmorRead;
    private static float escortBonusRead = -1f;

    /**
     * True when the Escort Package s-mod also cuts armor damage and works on cruisers (0.98.5a). Read from the hullmod
     * itself: 0.98.5a's s-mod text has a second number (the cruiser's 10%), 0.98a's has one.
     */
    static boolean escortArmor() {
        if (escortArmorRead == null) {
            boolean v = false;
            try {
                HullModSpecAPI spec = Global.getSettings().getHullModSpec(ESCORT);
                v = spec != null && spec.getEffect() != null && spec.getEffect().getSModDescriptionParam(1, ShipAPI.HullSize.CRUISER) != null;
            } catch (Throwable ignored) { }
            escortArmorRead = v;
        }
        return escortArmorRead;
    }

    /** EscortPackage.SHIELD_BONUS as a fraction (0.1 in 0.98a and 0.98.5a). */
    static float escortBonus() {
        if (escortBonusRead < 0f) {
            float b = 0.1f;
            try {
                b = com.fs.starfarer.api.impl.hullmods.EscortPackage.SHIELD_BONUS / 100f;
            } catch (Throwable ignored) { }
            escortBonusRead = b > 0f && b < 1f ? b : 0.1f;
        }
        return escortBonusRead;
    }

    /** A multiplier stat, 1 when it reads as unset. */
    private static float mult(float v) { return v > 0f ? v : 1f; }

    /** A dynamic multiplier stat (Stats ids), 1 when unset. */
    private static float dyn(MutableShipStatsAPI st, String id) {
        try {
            return mult(st.getDynamic().getValue(id));
        } catch (Throwable t) {
            return 1f;
        }
    }

    private static void bonus(JAOModel.Ship s, int cls, StatBonus b) {
        s.rangePct[cls] = b.getPercentMod();
        s.rangeFlat[cls] = b.getFlatBonus();
        s.rangeMult[cls] = b.getMult();
    }

    @Override
    public int hullOp() { return budget; }

    @Override
    public int modOp(String mod) {
        HullModSpecAPI spec = Global.getSettings().getHullModSpec(mod);
        return spec == null ? 0 : spec.getCostFor(hull.getHullSize());
    }

    @Override
    public float op(JAOSearch.Cand c, Set<String> mods) {
        String key = key(mods) + "|" + c.w.id + (c.w.wing ? "#w" : "");
        Float v = opCache.get(key);
        if (v != null) return v;
        setMods(mods);
        // priced on the scratch member's live stats: hullmods that cut weapon OP through a WeaponOPCostModifier listener
        // (Valkyrian Brunhild Cyclonic Core: 2/4/6 OP off Valkyrian weapons by size, 1/3/5 off other guns, 1/2/3 off
        // missiles) only exist there; the cloned variant's own
        // stats-for-OP never have them, which left Valkyrian mounts empty and the savings in vents
        MutableShipStatsAPI opStats;
        try {
            scratch.setVariant(work, false, true);
            opStats = scratch.getStats();
        } catch (Throwable t) {
            opStats = work.getStatsForOpCosts();
        }
        float op;
        if (c.entry instanceof AvailableWeapon) op = ((AvailableWeapon) c.entry).getOPCost(cstats, opStats);
        else if (c.entry instanceof AvailableFighter) op = ((AvailableFighter) c.entry).getWingSpec().getOpCost(opStats);
        else op = 0f;
        opCache.put(key, op);
        return op;
    }

    @Override
    public float baseOp(JAOSearch.Cand c) {
        try {
            if (c.entry instanceof AvailableWeapon) return ((AvailableWeapon) c.entry).getSpec().getOrdnancePointCost(null);
            if (c.entry instanceof AvailableFighter) return ((AvailableFighter) c.entry).getWingSpec().getOpCost(null);
        } catch (Throwable ignored) { }
        return op(c, java.util.Collections.emptySet());
    }

    @Override
    public void setSMods(Set<String> s) { smods = new java.util.LinkedHashSet<>(s); }

    static final float OMNI_BONUS = 0.04f, ADAPTIVE_COILS = 0.10f;

    private static float anchorMult = -1f;

    /**
     * Phase Anchor's dissipation and weapon recharge multiplier while phased, from the game's own PhaseAnchor class
     * (a public static field in 0.98a and 0.98.5a: 2 and 1.5). 2 if it can't be read.
     */
    static float phaseAnchorMult() {
        if (anchorMult < 0f) {
            float m = 2f;
            try {
                m = com.fs.starfarer.api.impl.hullmods.PhaseAnchor.PHASE_DISSIPATION_MULT;
            } catch (Throwable t) {
                Global.getLogger(JAOShip.class).warn("Auto Outfitting: couldn't read Phase Anchor's multiplier; using 2", t);
            }
            anchorMult = m > 0f ? m : 2f;
        }
        return anchorMult;
    }

    static boolean lowTech(ShipHullSpecAPI h) {
        String m = h.getManufacturer();
        return m != null && m.toLowerCase(java.util.Locale.ROOT).contains("low tech");
    }

    /** Listener hullmods the model has its own numbers for (see ship()). */
    /** A listener hullmod whose effect the model carries: in MODELLED, or a damage-dealing hullmod (jao_hullmod_damage.csv, 0.5.41). */
    static boolean modelled(String id) {
        if (MODELLED.contains(id) || JAORatings.hullmodDamage().containsKey(id)) return true;
        for (String[] r : JAORatings.rules()) {
            if ((r[0].equals("hull_regen") || r[0].equals("shield_taken") || r[0].equals("beams_hard") || r[0].equals("range_mult") || r[0].equals("weapon_range")
                    || r[0].equals("combat") || r[0].equals("no_shield")) && r[1].equals(id)) return true;
        }
        return false;
    }

    static final java.util.Set<String> MODELLED = new java.util.HashSet<>(java.util.Arrays.asList(
            "ballistic_rangefinder", "high_scatter_amp", "missile_autoloader", "phase_anchor", "adaptiveshields", "adaptive_coils", "valk_bryncore", "valk_microbryncore", "valk_valhallacore"));

    /** Hull-only hullmods (roles HULL or CR in jao_hullmods.csv, or the vanilla padding mods) and Flux Distributor/Coil. */
    @Override
    public boolean filler(String mod) {
        if (mod.equals(HullMods.REINFORCEDHULL) || mod.equals(HullMods.BLAST_DOORS) || mod.equals(HullMods.FLUX_DISTRIBUTOR) || mod.equals(HullMods.FLUX_COIL)) return true;
        JSONObject row = JAORatings.hullmods().get(mod);
        if (row == null) return false;
        String roles = " " + row.optString("roles", "") + " ";
        return roles.trim().equals("HULL");
    }

    /** Ordinary logistics hullmods this ship may carry: the game's maxLogisticsHullmods with the ship's own modifiers (skills). */
    static int maxLogistics(FleetMemberAPI member) {
        int base;
        try { base = Global.getSettings().getInt("maxLogisticsHullmods"); } catch (Throwable t) { base = 2; }
        try {
            if (member != null) return Math.round(member.getStats().getDynamic()
                    .getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.MAX_LOGISTICS_HULLMODS_MOD).computeEffective(base));
        } catch (Throwable ignored) { }
        return base;
    }

    @Override
    public boolean canAdd(String mod, Set<String> mods) {
        String key = key(mods) + "|" + mod;
        Boolean v = addCache.get(key);
        if (v != null) return v;
        boolean ok = check(mod, mods);
        addCache.put(key, ok);
        return ok;
    }

    private boolean check(String mod, Set<String> mods) {
        HullModSpecAPI spec = Global.getSettings().getHullModSpec(mod);
        if (spec == null) return false;
        // the game's own autofit never adds these (Fragment Swarm: it needs an item, and only fragment weapons use it; 0.5.38)
        if (spec.hasTag("do_not_apply_hullmod_during_autofit")) return false;
        // Shrouded hullmods (0.5.40): only with "Use Shrouded hullmods" on; Luddic factions turn hostile on seeing one
        if (spec.hasTag("shrouded") && !JAOSettings.shroudedHullmods) return false;
        if (!paidOrFree(mod)) return false;
        if (phaseCloak(hull) && spec.hasTag(HullMods.TAG_NON_PHASE)) return false;
        if (!phaseCloak(hull) && spec.hasTag(HullMods.TAG_PHASE)) return false;
        if (delegate.isPlayerCampaignRefit() && !delegate.canAddRemoveHullmodInPlayerCampaignRefit(mod)) return false;
        // incompatibilities the table knows (most mods also refuse in isApplicableToShip)
        JSONObject row = JAORatings.hullmods().get(mod);
        if (row != null) for (String other : row.optString("incompatible", "").split(" ")) if (!other.isEmpty() && (mods.contains(other) || work.hasHullMod(other))) return false;
        // and the other way round (0.5.41): a fitted hullmod whose own row lists this one (Yunru's targeting core and ITU)
        for (String other : mods) {
            JSONObject o = other.equals(mod) ? null : JAORatings.hullmods().get(other);
            if (o != null && (" " + o.optString("incompatible", "") + " ").contains(" " + mod + " ")) return false;
        }
        if (mod.equals(HullMods.INTEGRATED_TARGETING_UNIT) && mods.contains(HullMods.DEDICATED_TARGETING_CORE)) return false;
        if (mod.equals(HullMods.DEDICATED_TARGETING_CORE) && mods.contains(HullMods.INTEGRATED_TARGETING_UNIT)) return false;
        // the game's limit on logistics hullmods counts only ordinary ones: built-in hullmods and s-mods don't (0.5.18;
        // before, an Atlas with cargo holds and a drive field built in had no room left for anything ordinary)
        if (spec.hasUITag("Logistics") && !smods.contains(mod) && !originalPerma.contains(mod)) {
            int n = 0;
            for (String m : mods) {
                if (m.equals(mod) || hull.isBuiltInMod(m) || originalPerma.contains(m) || smods.contains(m)) continue;
                HullModSpecAPI o = Global.getSettings().getHullModSpec(m);
                if (o != null && o.hasUITag("Logistics")) n++;
            }
            if (n >= maxLogistics(delegate.getFleetMember())) return false;
        }
        setMods(mods);
        // the refit screen's ship when this is its ship; a stand-in for ships the fleet plan fits off screen
        ShipAPI ship = delegate.getShip();
        if (ship == null) {
            if (checkShip == null) checkShip = new JAOCheckShip(work, scratch, delegate.getFleetMember());
            ship = checkShip;
        }
        if (spec.getEffect() != null) {
            ShipVariantAPI orig = ship.getVariant();
            try {
                ship.setVariantForHullmodCheckOnly(work);
                if (!spec.getEffect().isApplicableToShip(ship)) return false;
            } catch (Throwable t) {
                return false;
            } finally {
                ship.setVariantForHullmodCheckOnly(orig);
            }
        }
        try {
            if (!HullModItemManager.getInstance().isRequiredItemAvailable(mod, delegate.getFleetMember(), work, delegate.getMarket())) return false;
        } catch (Throwable t) {
            return false;
        }
        if (!JAOSettings.allowDrawbacks && !(JAOSettings.shroudedHullmods && spec.hasTag("shrouded"))) {
            String why = drawback(mod, mods);
            if (why != null) { drawbacks.putIfAbsent(mod, why); return false; }
        }
        return true;
    }

    /** Hullmods turned down for a drawback (with "Allow hullmods with drawbacks" off), with the stat that got worse. */
    final Map<String, String> drawbacks = new java.util.LinkedHashMap<>();

    /**
     * Hullmods whose drawback the scratch fleet member can't see. Yunru's Inverted Reverse Thrusters roughly doubles speed,
     * acceleration and turning but the ship can no longer fly backwards (the AI can't back off to vent), 0.5.6.
     */
    static final Map<String, String> KNOWN_DRAWBACKS = Map.of("high_scatter_amp", "beam range above 200 halved",
            "yunru_irt", "can't fly backwards");

    static final String[] STAT_NAMES = {"hull", "armor", "flux capacity", "flux dissipation", "top speed", "acceleration",
            "turn rate", "ballistic range", "energy range", "missile range", "beam range", "range past a threshold",
            "shield efficiency", "shield upkeep", "shield arc", "shield damage taken", "armor damage taken", "hull damage taken",
            "EMP damage taken", "weapon damage taken", "engine damage taken", "ballistic damage", "energy damage",
            "missile damage", "beam damage", "ballistic rate of fire", "energy rate of fire", "missile rate of fire",
            "ballistic flux cost", "energy flux cost", "missile flux cost", "beam flux cost", "missile ammo",
            "weapon turn rate", "beam turn rate", "peak operating time", "CR loss rate", "CR per deployment",
            "supplies per month", "supplies to recover", "deployment points", "fuel use", "venting", "fighter refit time",
            "fighter bays", "crew needed", "sensor profile", "burn level", "shield", "crew salary", "crew losses"};

    /**
     * Crew salary multiplier and Safety Overrides' extra crew losses (0.98.5a stats ship_crew_salary_mult and
     * so_crew_loss_mod; Safety Overrides doubles the salary). Read by id, so on 0.98a they stay unset and never differ.
     */
    private static float salaryMult(MutableShipStatsAPI st) {
        try {
            return st.getDynamic().getStat("ship_crew_salary_mult").getModifiedValue();
        } catch (Throwable t) {
            return 1f;
        }
    }

    private static float crewLoss(MutableShipStatsAPI st) {
        try {
            return st.getDynamic().getMod("so_crew_loss_mod").computeEffective(0f);
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** Every stat a hullmod could make worse, each turned so that bigger is better. */
    private float[] snapshot() {
        MutableShipStatsAPI st = scratch.getStats();
        float arc = hull.getShieldSpec() == null ? 0f : hull.getShieldSpec().getArc();
        boolean shield = (hull.getShieldType() == ShieldType.FRONT || hull.getShieldType() == ShieldType.OMNI) && !work.hasHullMod(HullMods.SHIELD_SHUNT) && !ruleOn("no_shield");
        return new float[]{
                st.getHullBonus().computeEffective(hull.getHitpoints()), st.getArmorBonus().computeEffective(hull.getArmorRating()),
                st.getFluxCapacity().getModifiedValue(), st.getFluxDissipation().getModifiedValue(),
                st.getMaxSpeed().getModifiedValue(), st.getAcceleration().getModifiedValue(), st.getMaxTurnRate().getModifiedValue(),
                st.getBallisticWeaponRangeBonus().computeEffective(1000f), st.getEnergyWeaponRangeBonus().computeEffective(1000f),
                st.getMissileWeaponRangeBonus().computeEffective(1000f), st.getBeamWeaponRangeBonus().computeEffective(1000f),
                st.getWeaponRangeThreshold().getModifiedValue() > 0f ? -1f : 0f,
                -st.getShieldAbsorptionMult().getModifiedValue(), -st.getShieldUpkeepMult().getModifiedValue(), st.getShieldArcBonus().computeEffective(arc),
                -st.getShieldDamageTakenMult().getModifiedValue(), -st.getArmorDamageTakenMult().getModifiedValue(), -st.getHullDamageTakenMult().getModifiedValue(),
                -st.getEmpDamageTakenMult().getModifiedValue(), -st.getWeaponDamageTakenMult().getModifiedValue(), -st.getEngineDamageTakenMult().getModifiedValue(),
                st.getBallisticWeaponDamageMult().getModifiedValue(), st.getEnergyWeaponDamageMult().getModifiedValue(),
                st.getMissileWeaponDamageMult().getModifiedValue(), st.getBeamWeaponDamageMult().getModifiedValue(),
                st.getBallisticRoFMult().getModifiedValue(), st.getEnergyRoFMult().getModifiedValue(), st.getMissileRoFMult().getModifiedValue(),
                -st.getBallisticWeaponFluxCostMod().computeEffective(1f), -st.getEnergyWeaponFluxCostMod().computeEffective(1f),
                -st.getMissileWeaponFluxCostMod().computeEffective(1f), -st.getBeamWeaponFluxCostMult().getModifiedValue(),
                st.getMissileAmmoBonus().computeEffective(100f),
                st.getWeaponTurnRateBonus().computeEffective(10f), st.getBeamWeaponTurnRateBonus().computeEffective(10f),
                st.getPeakCRDuration().computeEffective(hull.getNoCRLossTime()), -st.getCRLossPerSecondPercent().computeEffective(1f),
                -st.getCRPerDeploymentPercent().computeEffective(10f),
                -st.getSuppliesPerMonth().getModifiedValue(), -st.getSuppliesToRecover().getModifiedValue(), -scratch.getDeploymentPointsCost(),
                -st.getFuelUseMod().computeEffective(1f), st.getVentRateMult().getModifiedValue(), -st.getFighterRefitTimeMult().getModifiedValue(),
                st.getNumFighterBays().getModifiedValue(), -st.getMinCrewMod().computeEffective(100f), -st.getSensorProfile().getModifiedValue(),
                st.getMaxBurnLevel().getModifiedValue(), shield ? 1f : 0f, -salaryMult(st), -crewLoss(st)};
    }

    /** The first stat mod makes worse (by more than 0.5%), or null when it only helps. */
    String drawback(String mod, Set<String> mods) {
        if (KNOWN_DRAWBACKS.containsKey(mod)) return KNOWN_DRAWBACKS.get(mod);
        // drawbacks only combat shows (0.5.49, jao_rules drawback)
        for (String[] r : JAORatings.rules()) if (r[0].equals("drawback") && r[1].equals(mod)) return r[2].isEmpty() ? "combat drawback" : r[2];
        try {
            setMods(mods);
            scratch.setVariant(work, false, true);
            float[] before = snapshot();
            Set<String> with = new java.util.LinkedHashSet<>(mods);
            with.add(mod);
            setMods(with);
            scratch.setVariant(work, false, true);
            float[] after = snapshot();
            setMods(mods);
            scratch.setVariant(work, false, true);
            for (int i = 0; i < before.length; i++) {
                if (after[i] < before[i] - Math.max(0.005f * Math.abs(before[i]), 1e-4f)) return STAT_NAMES[i];
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }
}
