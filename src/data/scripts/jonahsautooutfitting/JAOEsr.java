package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extra System Reloaded - Oddisz Edition (mod id esr_oddisz) augments (0.5.52). Augments are special items a ship holds
 * in slots (1/2/2/3 by hull size, ESR's LunaLib settings); ESR keeps each ship's list in sector persistent data
 * ("esro_ships") and applies it through its hidden hullmod esro_extra_systems. Installing is free at a friendly market
 * with a spaceport (ESR's Extra Systems bay); removing gives the item back unless ESR's "Removing destroys the augment"
 * is on.
 *  - ESR's own calls (ShipData, Augment, Settings, HullmodSync) are reached through method handles; nothing here names
 *    an ESR class, so the mod loads without ESR.
 *  - The model: stat effects mirror ESR's Augment.applyStats (the numbers are ESR 1.0.0's). The test ship takes the real
 *    member's id, so ESR already applies what the ship has; a planned set adds the missing augments' stats and takes
 *    the removed ones' back off under AO's own modifier ids. Combat-script effects (armor repair, missile reloads,
 *    the range equalizer, the overload cancel) go onto the model ship directly (scriptEffects).
 */
final class JAOEsr {
    static final String MOD_ID = "esr_oddisz";
    static final String OVERLOAD = "esro_overload_damper", NANOLATTICE = "esro_nanolattice", CRYO = "esro_cryo_exchanger",
            TEMPORAL = "esro_temporal_capacitor", PHASE = "esro_phase_coil", PD = "esro_pd_integrator", HANGAR = "esro_hangar_forge",
            MISSILE = "esro_missile_autoforge", VENT = "esro_vent_thrusters", RANGE = "esro_range_equalizer";
    static final String[] IDS = {OVERLOAD, NANOLATTICE, CRYO, TEMPORAL, PHASE, PD, HANGAR, MISSILE, VENT, RANGE};
    /** Modifier id prefix for the planned-set stat changes. */
    static final String MOD_PREFIX = "jao_esr_";
    /** Overload Damper on a shielded ship (overload time x0.5, one overload in 30 s cancelled): a score bonus (estimate). */
    static final float OVERLOAD_VALUE = 0.03f;

    private static boolean tried, ok;
    private static MethodHandle shipGet, shipGetOrCreate, getAugments, addAugment, removeAugment, fixQuality, byId, augId,
            canInstallOn, slots, removalDestroys, sync;

    private JAOEsr() { }

    /** ESR is enabled and its calls are reachable. */
    static boolean present() {
        if (tried) return ok;
        tried = true;
        try {
            if (!Global.getSettings().getModManager().isModEnabled(MOD_ID)) return ok = false;
            ClassLoader cl = Global.getSettings().getScriptClassLoader();
            Class<?> data = Class.forName("esroddisz.ShipData", true, cl);
            Class<?> aug = Class.forName("esroddisz.augments.Augment", true, cl);
            Class<?> settings = Class.forName("esroddisz.Settings", true, cl);
            Class<?> hullSync = Class.forName("esroddisz.campaign.HullmodSync", true, cl);
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            shipGet = l.findStatic(data, "get", MethodType.methodType(data, FleetMemberAPI.class));
            shipGetOrCreate = l.findStatic(data, "getOrCreate", MethodType.methodType(data, FleetMemberAPI.class));
            getAugments = l.findVirtual(data, "getAugments", MethodType.methodType(List.class));
            addAugment = l.findVirtual(data, "addAugment", MethodType.methodType(void.class, aug));
            removeAugment = l.findVirtual(data, "removeAugment", MethodType.methodType(void.class, aug));
            fixQuality = l.findVirtual(data, "fixQuality", MethodType.methodType(void.class));
            byId = l.findStatic(aug, "byId", MethodType.methodType(aug, String.class));
            augId = l.findVirtual(aug, "getId", MethodType.methodType(String.class));
            canInstallOn = l.findVirtual(aug, "canInstallOn", MethodType.methodType(boolean.class, ShipHullSpecAPI.class, MutableShipStatsAPI.class));
            slots = l.findStatic(settings, "augmentSlots", MethodType.methodType(int.class, ShipAPI.HullSize.class));
            removalDestroys = l.findStatic(settings, "removalDestroysAugment", MethodType.methodType(boolean.class));
            sync = l.findStatic(hullSync, "sync", MethodType.methodType(void.class, FleetMemberAPI.class));
            ok = true;
            Global.getLogger(JAOEsr.class).info("Auto Outfitting: Extra System Reloaded found; augments are placed by Optimize fleet");
        } catch (Throwable t) {
            ok = false;
            Global.getLogger(JAOEsr.class).warn("Auto Outfitting: Extra System Reloaded is enabled but its calls aren't reachable; augments are left alone", t);
        }
        return ok;
    }

    /** The augment ids the ship holds now, in ESR's order (empty without ESR). */
    static Set<String> installed(FleetMemberAPI m) {
        if (m == null || !present()) return Collections.emptySet();
        try {
            Object d = (Object) shipGet.invoke(m);
            if (d == null) return Collections.emptySet();
            Set<String> out = new LinkedHashSet<>();
            for (Object a : (List<?>) (Object) getAugments.invoke(d)) out.add((String) (Object) augId.invoke(a));
            return out;
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    /** Augment slots for this hull size (ESR's setting). */
    static int slots(ShipAPI.HullSize size) {
        try { return Math.max(0, (int) slots.invoke(size)); } catch (Throwable t) { return 0; }
    }

    /** ESR's "Removing destroys the augment" setting (on: installed augments never move). */
    static boolean removalDestroys() {
        try { return (boolean) removalDestroys.invoke(); } catch (Throwable t) { return true; }
    }

    /** ESR's own install requirement (ship system, phase, fighter bays, missile slots). */
    static boolean canInstall(FleetMemberAPI m, String id) {
        try {
            Object a = (Object) byId.invoke(id);
            return a != null && (boolean) canInstallOn.invoke(a, m.getHullSpec(), m.getStats());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Installs an augment the way ESR's bay does (the item is taken by the caller). True only when ESR lists it after. */
    static boolean install(FleetMemberAPI m, String id) {
        try {
            Object a = (Object) byId.invoke(id);
            if (a == null) return false;
            Object d = (Object) shipGetOrCreate.invoke(m);
            fixQuality.invoke(d);
            addAugment.invoke(d, a);
            try { sync.invoke(m); m.updateStats(); } catch (Throwable t) {
                Global.getLogger(JAOEsr.class).warn("Auto Outfitting: " + m.getShipName() + " took " + id + " but its refresh failed; ESR syncs it again", t);
            }
            return installed(m).contains(id);
        } catch (Throwable t) {
            Global.getLogger(JAOEsr.class).error("Auto Outfitting: couldn't install " + id + " on " + m.getShipName(), t);
            // ESR may hold it even though a call failed: then it counts as installed and the item isn't given back
            return installed(m).contains(id);
        }
    }

    /** ESR's own hullmod and owner tag on the ship's current variant (HullmodSync.sync). */
    static void sync(FleetMemberAPI m) {
        try { sync.invoke(m); } catch (Throwable ignored) { }
    }

    /**
     * Removes an augment the way ESR's bay does; the item goes to this cargo, only once ESR no longer lists it (no copy
     * when the removal didn't happen). Never with ESR's "Removing destroys the augment" on.
     */
    static boolean uninstall(FleetMemberAPI m, String id, CargoAPI cargo) {
        try {
            if (removalDestroys() || !installed(m).contains(id)) return false;
            Object a = (Object) byId.invoke(id);
            Object d = (Object) shipGet.invoke(m);
            if (a == null || d == null) return false;
            removeAugment.invoke(d, a);
            if (installed(m).contains(id)) return false;
            cargo.addSpecial(new SpecialItemData(id, null), 1f);
            sync.invoke(m);
            m.updateStats();
            return true;
        } catch (Throwable t) {
            Global.getLogger(JAOEsr.class).error("Auto Outfitting: couldn't remove " + id + " from " + m.getShipName(), t);
            return false;
        }
    }

    /** Augment items of this id in a cargo. */
    static int count(CargoAPI c, String id) {
        if (c == null) return 0;
        try { return (int) Math.floor(c.getQuantity(CargoAPI.CargoItemType.SPECIAL, new SpecialItemData(id, null)) + 0.001f); } catch (Throwable t) { return 0; }
    }

    /** Takes one item from cargo, else from storage. */
    static boolean take(CargoAPI cargo, CargoAPI storage, String id) {
        SpecialItemData item = new SpecialItemData(id, null);
        if (count(cargo, id) > 0) { cargo.removeItems(CargoAPI.CargoItemType.SPECIAL, item, 1f); return true; }
        if (count(storage, id) > 0) { storage.removeItems(CargoAPI.CargoItemType.SPECIAL, item, 1f); return true; }
        return false;
    }

    static String name(String id) {
        try { return Global.getSettings().getSpecialItemSpec(id).getName(); } catch (Throwable t) { return id; }
    }

    // ------------------------------------------------------------------ the model

    /**
     * The test ship's stats for a planned augment set: ESR applies now (the ship's own list), so the augments in want and
     * not in now are added and those in now and not in want are taken back off, under AO's own modifier ids.
     */
    static void applyStats(MutableShipStatsAPI st, Set<String> now, Set<String> want) {
        for (String id : IDS) clear(st, id);
        if (want == null || want.equals(now)) return;
        for (String id : want) if (!now.contains(id)) stats(st, id, true);
        for (String id : now) if (!want.contains(id)) stats(st, id, false);
    }

    private static void clear(MutableShipStatsAPI st, String id) {
        String k = MOD_PREFIX + id;
        st.getOverloadTimeMod().unmodify(k);
        st.getFluxDissipation().unmodify(k);
        st.getVentRateMult().unmodify(k);
        st.getHardFluxDissipationFraction().unmodify(k);
        st.getSystemCooldownBonus().unmodify(k);
        st.getSystemFluxCostBonus().unmodify(k);
        st.getSystemUsesBonus().unmodify(k);
        st.getSystemRegenBonus().unmodify(k);
        st.getPhaseCloakActivationCostBonus().unmodify(k);
        st.getPhaseCloakUpkeepCostBonus().unmodify(k);
        st.getPhaseCloakCooldownBonus().unmodify(k);
        st.getDamageToFighters().unmodify(k);
        st.getDamageToMissiles().unmodify(k);
        st.getNonBeamPDWeaponRangeBonus().unmodify(k);
        st.getBeamPDWeaponRangeBonus().unmodify(k);
        st.getFighterRefitTimeMult().unmodify(k);
        st.getDynamic().getStat("replacement_rate_increase_mult").unmodify(k);
        st.getDynamic().getStat("replacement_rate_decrease_mult").unmodify(k);
        st.getMissileAmmoBonus().unmodify(k);
    }

    /** One augment's stat effects (ESR 1.0.0 Augment.applyStats), added or taken back off. */
    private static void stats(MutableShipStatsAPI st, String id, boolean add) {
        String k = MOD_PREFIX + id;
        float sign = add ? 1f : -1f;
        switch (id) {
            case OVERLOAD: st.getOverloadTimeMod().modifyMult(k, add ? 0.5f : 2f); break;
            case CRYO:
                st.getFluxDissipation().modifyPercent(k, 25f * sign);
                st.getVentRateMult().modifyPercent(k, 25f * sign);
                st.getHardFluxDissipationFraction().modifyFlat(k, 0.25f * sign);
                break;
            case TEMPORAL:
                st.getSystemCooldownBonus().modifyMult(k, add ? 0.5f : 2f);
                st.getSystemFluxCostBonus().modifyMult(k, add ? 0.5f : 2f);
                // +1 charge and +100% charge regen only for systems with charges (ESR's hasSystemCharges)
                if (systemCharges(st)) {
                    st.getSystemUsesBonus().modifyFlat(k, sign);
                    st.getSystemRegenBonus().modifyPercent(k, 100f * sign);
                }
                break;
            case PHASE:
                st.getPhaseCloakActivationCostBonus().modifyMult(k, add ? 0.5f : 2f);
                st.getPhaseCloakUpkeepCostBonus().modifyMult(k, add ? 0.5f : 2f);
                st.getPhaseCloakCooldownBonus().modifyMult(k, add ? 0.5f : 2f);
                break;
            case PD:
                st.getDamageToFighters().modifyPercent(k, 50f * sign);
                st.getDamageToMissiles().modifyPercent(k, 50f * sign);
                st.getNonBeamPDWeaponRangeBonus().modifyFlat(k, 100f * sign);
                st.getBeamPDWeaponRangeBonus().modifyFlat(k, 100f * sign);
                break;
            case HANGAR:
                st.getFighterRefitTimeMult().modifyMult(k, add ? 0.6f : 1f / 0.6f);
                st.getDynamic().getStat("replacement_rate_increase_mult").modifyPercent(k, 100f * sign);
                st.getDynamic().getStat("replacement_rate_decrease_mult").modifyMult(k, add ? 0.5f : 2f);
                break;
            case MISSILE: st.getMissileAmmoBonus().modifyPercent(k, 25f * sign); break;
            case VENT: st.getVentRateMult().modifyPercent(k, 30f * sign); break;
            default: break;
        }
    }

    /** The ship's system has charges: max uses above 0 and below the game's unlimited value (ESR Augment.hasSystemCharges). */
    private static boolean systemCharges(MutableShipStatsAPI st) {
        try {
            String sys = st.getVariant().getHullSpec().getShipSystemId();
            if (sys == null || sys.isEmpty()) return false;
            com.fs.starfarer.api.combat.ShipSystemSpecAPI spec = Global.getSettings().getShipSystemSpec(sys);
            if (spec == null) return false;
            int uses = spec.getMaxUses(st);
            return uses > 0 && uses < Integer.MAX_VALUE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Combat-script effects on the model ship for the augments it holds: Nanolattice armor repair (2% a second, at most
     * twice the armor a battle), Missile Autoforge reloads (30% of each missile weapon's ammo every 45 s), Fire Control
     * Equalizer (ballistic and energy base range under 800: +200, never past 800), Overload Damper on shielded ships.
     * Not modelled: Point Defense Integrator's small guns acting as point defense, Vent Thrusters' speed while venting.
     */
    static void scriptEffects(JAOModel.Ship s, Set<String> set, float fight) {
        if (set == null || set.isEmpty()) return;
        float f = Math.max(30f, fight);
        if (set.contains(NANOLATTICE)) s.armorRegen += Math.min(0.02f, 2f / f);
        if (set.contains(MISSILE)) {
            float reloads = (float) Math.floor(f / 45f);
            if (reloads > 0f) s.ammoMult *= 1f + 0.3f * reloads;
        }
        if (set.contains(RANGE)) { s.eqAdd = 200f; s.eqCap = 800f; }
        if (set.contains(OVERLOAD) && s.shield) s.extra += OVERLOAD_VALUE;
    }
}
