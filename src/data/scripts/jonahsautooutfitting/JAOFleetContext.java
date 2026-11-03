package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the player's fleet already carries, so one ship's Optimal fit can complement the rest (user 11/03: every
 * carrier had Gunheads, and "is this whole fleet primed for one damage type?"):
 *  - firepower by damage type (guns at sustained dps, missiles at their load over 90 s, wings at their value except
 *    interceptors; PD left out), and how much of it
 *    can crack armor (hit >= 300 after the armor multiplier: HE guns, burst beams, torpedoes);
 *  - fighter bays by role (JAOModel.W_*) and copies of each wing.
 * The ship being fitted is left out (its own loadout is what's being chosen). Read from the ships' current variants
 * and modules; civilian hulls count for nothing.
 */
final class JAOFleetContext {
    /**
     * Firepower targets the fleet aims for: about half kinetic (shields; players run 2 kinetic to 1 HE, biased
     * anti-shield) and 30% armor-cracking (research meta 4.2, build_philosophy R2.2). Fighters count: Longbows and
     * Broadswords as kinetic, Daggers as armor-cracking.
     */
    static final float KINETIC_TARGET = 0.50f, ARMOR_TARGET = 0.30f;

    final float[] dmg = new float[4];
    float armorDps, total;
    final float[] wingBays = new float[4];
    final Map<String, Integer> wingIds = new LinkedHashMap<>();
    int ships;
    final List<Ship> perShip = new ArrayList<>();

    /** One combat ship's own numbers, for the fleet analysis. */
    static final class Ship {
        FleetMemberAPI member;
        final float[] dmg = new float[4];
        float armorDps, total, pd, minRange = Float.MAX_VALUE, maxRange;
        int bays, emptyMounts;
        final float[] wingBays = new float[4];
    }

    /** The player's fleet without except (null: everyone). */
    static JAOFleetContext of(FleetMemberAPI except) {
        JAOFleetContext c = new JAOFleetContext();
        CampaignFleetAPI fleet = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
        if (fleet == null) return c;
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (m.isFighterWing() || m.getVariant() == null) continue;
            if (except != null && m.getId().equals(except.getId())) continue;
            if (m.getHullSpec() != null && JAOOptimizer.civilian(m.getHullSpec())) continue;
            Ship s = new Ship();
            s.member = m;
            ShipVariantAPI v = m.getVariant();
            add(c, s, v);
            for (String slot : v.getStationModules().keySet()) {
                ShipVariantAPI mv = v.getModuleVariant(slot);
                if (mv != null) add(c, s, mv);
            }
            c.ships++;
            c.perShip.add(s);
        }
        return c;
    }

    private static void add(JAOFleetContext c, Ship s, ShipVariantAPI v) {
        for (String slot : v.getFittedWeaponSlots()) {
            WeaponSpecAPI spec = v.getWeaponSpec(slot);
            if (spec == null || v.getSlot(slot) == null || v.getSlot(slot).isDecorative()) continue;
            JAOModel.W w;
            try { w = JAORatings.weapon(spec); } catch (Throwable t) { continue; }
            float dps = w.dps > 0f ? w.dps : w.pool / 90f;
            if (w.pd || w.pdOnly) { s.pd += w.dps; continue; }
            if (dps <= 0f) continue;
            int d = Math.max(0, Math.min(3, w.dmg));
            c.dmg[d] += dps; s.dmg[d] += dps;
            c.total += dps; s.total += dps;
            if (w.hit * JAOModel.ARMOR_MULT[d] >= 300f) { c.armorDps += dps; s.armorDps += dps; }
            if (w.cls != JAOModel.MIS) { s.minRange = Math.min(s.minRange, w.range); s.maxRange = Math.max(s.maxRange, w.range); }
        }
        for (com.fs.starfarer.api.loading.WeaponSlotAPI sl : v.getHullSpec().getAllWeaponSlotsCopy()) {
            if (JAOOptimizer.mountable(sl) && v.getWeaponId(sl.getId()) == null) s.emptyMounts++;
        }
        int bays = 0;
        try { bays = Global.getSettings().computeNumFighterBays(v); } catch (Throwable ignored) { }
        s.bays += bays;
        for (int i = 0; i < bays; i++) {
            String id = v.getWingId(i);
            if (id == null) continue;
            FighterWingSpecAPI spec = Global.getSettings().getFighterWingSpec(id);
            if (spec == null) continue;
            JAOModel.W w = JAORatings.wing(spec);
            int role = w.wingRole;
            c.wingBays[role] += 1f;
            s.wingBays[role] += 1f;
            c.wingIds.merge(id, 1, Integer::sum);
            if (w.interceptor || w.dps <= 0f) continue;
            int d = Math.max(0, Math.min(3, w.dmg));
            c.dmg[d] += w.dps; s.dmg[d] += w.dps;
            c.total += w.dps; s.total += w.dps;
            if (w.hit * JAOModel.ARMOR_MULT[d] >= 300f) { c.armorDps += w.dps; s.armorDps += w.dps; }
        }
    }

    float kineticShare() { return total > 0f ? dmg[JAOModel.KIN] / total : 0f; }
    float armorShare() { return total > 0f ? armorDps / total : 0f; }

    /**
     * Leans one ship's kill-phase weights toward what the rest of the fleet is short of: up to 25% more weight on
     * shields when kinetic is scarce, on armor when armor-cracking is (and down to 20% less when there's plenty). Only
     * with three or more other combat ships, and only for the Auto/Balanced styles (an explicit style is the player's).
     */
    void complement(JAOModel.Params p) {
        if (ships < 3 || total <= 0f) return;
        p.wS *= JAOModel.clamp(1f + 0.8f * (KINETIC_TARGET - kineticShare()), 0.8f, 1.25f);
        p.wA *= JAOModel.clamp(1f + 0.8f * (ARMOR_TARGET - armorShare()), 0.8f, 1.25f);
    }

    /** Wing balance inputs for the model. */
    void wings(JAOModel.Params p) {
        p.fleetWingBays = wingBays.clone();
        p.fleetWingIds = new HashMap<>(wingIds);
    }
}
