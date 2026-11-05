package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import java.util.ArrayList;
import java.util.List;

/**
 * What the player's fleet carries now, read from the ships' current variants and modules (civilian hulls count for
 * nothing): the fleet's JAOFleetMix, and per ship what the fleet analysis lists (its own mix, point defense, range band,
 * empty mounts, fighter bays). One Optimal fit balances against it with the ship itself left out; the fleet plan
 * (JAOFleetPlan) builds its mix from the plan instead.
 */
final class JAOFleetContext {
    final JAOFleetMix mix = new JAOFleetMix();
    int ships;
    final List<Ship> perShip = new ArrayList<>();

    /** One combat ship's own numbers, for the fleet analysis. */
    static final class Ship {
        FleetMemberAPI member;
        final JAOFleetMix own = new JAOFleetMix();
        float pd, minRange = Float.MAX_VALUE, maxRange;
        int bays, emptyMounts;
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
            List<JAOModel.W> ws = new ArrayList<>();
            add(s, v, ws);
            for (String slot : v.getStationModules().keySet()) {
                ShipVariantAPI mv = v.getModuleVariant(slot);
                if (mv != null) add(s, mv, ws);
            }
            s.own.addShip(ws);
            c.mix.addShip(ws);
            c.ships++;
            c.perShip.add(s);
        }
        return c;
    }

    /** A variant's fitted weapons and wings (the W list JAOFleetMix counts), plus the analysis' per-ship numbers. */
    static void add(Ship s, ShipVariantAPI v, List<JAOModel.W> ws) {
        for (String slot : v.getFittedWeaponSlots()) {
            WeaponSpecAPI spec = v.getWeaponSpec(slot);
            if (spec == null || v.getSlot(slot) == null || v.getSlot(slot).isDecorative()) continue;
            JAOModel.W w;
            try { w = JAORatings.weapon(spec); } catch (Throwable t) { continue; }
            ws.add(w);
            if (s == null) continue;
            if (w.pd || w.pdOnly) s.pd += w.dps;
            else if (w.cls != JAOModel.MIS && w.dps > 0f) { s.minRange = Math.min(s.minRange, w.range); s.maxRange = Math.max(s.maxRange, w.range); }
        }
        if (s != null) for (com.fs.starfarer.api.loading.WeaponSlotAPI sl : v.getHullSpec().getAllWeaponSlotsCopy()) {
            if (JAOOptimizer.mountable(sl) && v.getWeaponId(sl.getId()) == null) s.emptyMounts++;
        }
        int bays = 0;
        try { bays = Global.getSettings().computeNumFighterBays(v); } catch (Throwable ignored) { }
        if (s != null) s.bays += bays;
        for (int i = 0; i < bays; i++) {
            String id = v.getWingId(i);
            if (id == null) continue;
            FighterWingSpecAPI spec = Global.getSettings().getFighterWingSpec(id);
            if (spec != null) ws.add(JAORatings.wing(spec));
        }
    }

    /** A variant and its modules as the W list JAOFleetMix counts. */
    static List<JAOModel.W> weaponsOf(ShipVariantAPI v) {
        List<JAOModel.W> ws = new ArrayList<>();
        add(null, v, ws);
        for (String slot : v.getStationModules().keySet()) {
            ShipVariantAPI mv = v.getModuleVariant(slot);
            if (mv != null) add(null, mv, ws);
        }
        return ws;
    }
}
