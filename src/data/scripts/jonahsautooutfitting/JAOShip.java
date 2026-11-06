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
        PersonAPI player = Global.getSector() == null ? null : Global.getSector().getPlayerPerson();
        CampaignFleetAPI fleet = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
        if (player != null) scratch.setFleetCommanderForStats(player, fleet == null ? null : fleet.getFleetData());
        boolean lb = false;
        for (WeaponSlotAPI s : hull.getAllWeaponSlotsCopy()) {
            WeaponType t = s.getWeaponType();
            if (s.getSlotSize() == WeaponSize.LARGE && (t == WeaponType.BALLISTIC || t == WeaponType.HYBRID || t == WeaponType.COMPOSITE || t == WeaponType.UNIVERSAL)) lb = true;
        }
        largeBallistic = lb;
        personality = personalityOf(delegate.getFleetMember());
    }

    static int personalityOf(FleetMemberAPI m) {
        PersonAPI c = m == null ? null : m.getCaptain();
        String p = c == null || c.isDefault() || c.getPersonalityAPI() == null ? "steady" : c.getPersonalityAPI().getId();
        if ("aggressive".equals(p) || "reckless".equals(p)) return 1;
        if ("cautious".equals(p) || "timid".equals(p)) return 2;
        return 0;
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
        setMods(mods);
        scratch.setVariant(work, false, true);
        MutableShipStatsAPI st = scratch.getStats();
        JAOModel.Ship s = new JAOModel.Ship();
        s.size = size;
        s.hull = st.getHullBonus().computeEffective(hull.getHitpoints());
        s.armor = st.getArmorBonus().computeEffective(hull.getArmorRating());
        s.cap = st.getFluxCapacity().getModifiedValue();
        s.diss = st.getFluxDissipation().getModifiedValue();
        ShieldType type = hull.getShieldType();
        s.phase = type == ShieldType.PHASE || hull.isPhase();
        s.shield = (type == ShieldType.FRONT || type == ShieldType.OMNI) && hull.getShieldSpec() != null && !work.hasHullMod(HullMods.SHIELD_SHUNT);
        if (s.shield) {
            s.shieldEff = hull.getShieldSpec().getFluxPerDamageAbsorbed() * st.getShieldAbsorptionMult().getModifiedValue();
            s.shieldTaken = st.getShieldDamageTakenMult().getModifiedValue();
            s.upkeep = hull.getShieldSpec().getUpkeepCost() * st.getShieldUpkeepMult().getModifiedValue();
            s.arc = st.getShieldArcBonus().computeEffective(hull.getShieldSpec().getArc());
        }
        s.speed = st.getMaxSpeed().getModifiedValue();
        s.baseSpeed = hull.getEngineSpec() == null ? s.speed : hull.getEngineSpec().getMaxSpeed();
        if (hull.getEngineSpec() != null && hull.getEngineSpec().getMaxTurnRate() > 0f && hull.getEngineSpec().getAcceleration() > 0f) {
            float turn = st.getMaxTurnRate().getModifiedValue() / hull.getEngineSpec().getMaxTurnRate();
            float accel = st.getAcceleration().getModifiedValue() / hull.getEngineSpec().getAcceleration();
            s.maneuver = (float) Math.sqrt(Math.max(0.05f, turn) * Math.max(0.05f, accel));
        }
        s.ppt = st.getPeakCRDuration().computeEffective(hull.getNoCRLossTime());
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
        s.fluxMult[JAOModel.BAL] = st.getBallisticWeaponFluxCostMod().computeEffective(1f);
        s.fluxMult[JAOModel.ENE] = st.getEnergyWeaponFluxCostMod().computeEffective(1f);
        s.fluxMult[JAOModel.MIS] = st.getMissileWeaponFluxCostMod().computeEffective(1f);
        s.beamFluxMult = st.getBeamWeaponFluxCostMult().getModifiedValue();
        s.ammoMult = st.getMissileAmmoBonus().computeEffective(1f);
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
        // capacity, 5 s cooldown), Phase Anchor makes phasing free and doubles dissipation/recharge while phased
        // as an s-mod the Autoloader waits 10 s after a reload instead of 5 (its s-mod penalty)
        if (work.hasHullMod("missile_autoloader")) s.ammoMult *= work.getSMods().contains("missile_autoloader") ? 1.35f : 1.5f;
        if (work.hasHullMod("phase_anchor") && s.phase) s.extra += 0.12f;
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
        float deploy = hull.getCRToDeploy();
        if (deploy > 1f) deploy /= 100f;
        s.deployCr = JAOModel.clamp(st.getCRPerDeploymentPercent().computeEffective(deploy), 0f, 1f);
        if (Global.getSettings().computeNumFighterBays(work) > 0) {
            float dmodEffect = dyn(st, "dmod_effect_mult");
            s.deck = JAOModel.deckFactor(mult(st.getFighterRefitTimeMult().getModifiedValue()), dyn(st, "replacement_rate_decrease_mult"),
                    dyn(st, "replacement_rate_increase_mult"), st.getFighterWingRange().computeEffective(1f), JAOCompat.wingMult(work, dmodEffect));
        }
        s.turnMult = mult(st.getWeaponTurnRateBonus().computeEffective(1f));
        s.beamTurnMult = mult(st.getBeamWeaponTurnRateBonus().computeEffective(1f));
        s.recoilMult = mult(st.getMaxRecoilMult().getModifiedValue());
        s.malfunction = Math.max(0f, st.getWeaponMalfunctionChance().getModifiedValue());
        s.maxVents = maxVents;
        s.maxCaps = maxCaps;
        s.personality = personality;
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

    /** Listener hullmods the model has its own numbers for (see ship()). */
    static final java.util.Set<String> MODELLED = new java.util.HashSet<>(java.util.Arrays.asList(
            "ballistic_rangefinder", "high_scatter_amp", "missile_autoloader", "phase_anchor", "valk_bryncore", "valk_microbryncore", "valk_valhallacore"));

    /** Hull-only hullmods (roles HULL or CR in jao_hullmods.csv, or the vanilla padding mods) and Flux Distributor/Coil. */
    @Override
    public boolean filler(String mod) {
        if (mod.equals(HullMods.REINFORCEDHULL) || mod.equals(HullMods.BLAST_DOORS) || mod.equals(HullMods.FLUX_DISTRIBUTOR) || mod.equals(HullMods.FLUX_COIL)) return true;
        JSONObject row = JAORatings.hullmods().get(mod);
        if (row == null) return false;
        String roles = " " + row.optString("roles", "") + " ";
        return roles.trim().equals("HULL");
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
        if (hull.isPhase() && spec.hasTag(HullMods.TAG_NON_PHASE)) return false;
        if (!hull.isPhase() && spec.hasTag(HullMods.TAG_PHASE)) return false;
        if (delegate.isPlayerCampaignRefit() && !delegate.canAddRemoveHullmodInPlayerCampaignRefit(mod)) return false;
        // incompatibilities the table knows (most mods also refuse in isApplicableToShip)
        JSONObject row = JAORatings.hullmods().get(mod);
        if (row != null) for (String other : row.optString("incompatible", "").split(" ")) if (!other.isEmpty() && (mods.contains(other) || work.hasHullMod(other))) return false;
        if (mod.equals(HullMods.INTEGRATED_TARGETING_UNIT) && mods.contains(HullMods.DEDICATED_TARGETING_CORE)) return false;
        if (mod.equals(HullMods.DEDICATED_TARGETING_CORE) && mods.contains(HullMods.INTEGRATED_TARGETING_UNIT)) return false;
        if (spec.hasUITag("Logistics")) {
            int n = 0;
            for (String m : mods) { HullModSpecAPI o = Global.getSettings().getHullModSpec(m); if (o != null && o.hasUITag("Logistics")) n++; }
            if (n >= 2) return false;
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
        if (!JAOSettings.allowDrawbacks) {
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
            "fighter bays", "crew needed", "sensor profile", "burn level", "shield"};

    /** Every stat a hullmod could make worse, each turned so that bigger is better. */
    private float[] snapshot() {
        MutableShipStatsAPI st = scratch.getStats();
        float arc = hull.getShieldSpec() == null ? 0f : hull.getShieldSpec().getArc();
        boolean shield = (hull.getShieldType() == ShieldType.FRONT || hull.getShieldType() == ShieldType.OMNI) && !work.hasHullMod(HullMods.SHIELD_SHUNT);
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
                st.getMaxBurnLevel().getModifiedValue(), shield ? 1f : 0f};
    }

    /** The first stat mod makes worse (by more than 0.5%), or null when it only helps. */
    String drawback(String mod, Set<String> mods) {
        if (KNOWN_DRAWBACKS.containsKey(mod)) return KNOWN_DRAWBACKS.get(mod);
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
