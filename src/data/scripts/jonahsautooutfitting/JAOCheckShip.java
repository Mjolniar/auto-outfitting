package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A stand-in ShipAPI for hullmod checks on ships that aren't on the refit screen (the fleet plan). Outside combat the
 * game's only ShipAPI is the refit screen's own ship, and borrowing it for another hull gives that ship's hull size and
 * shield, which is exactly what vanilla's shield hullmods, Safety Overrides and Dedicated Targeting Core check. This
 * one answers from the checked variant: hull spec and size, a shield when the hull has one (not with Shield Shunt),
 * phase cloak and ship system ids, stats from a scratch fleet member, the fleet member and captain. Everything else is
 * empty (JAOStubShip); a hullmod whose check needs more throws or says no, and is left out.
 */
final class JAOCheckShip extends JAOStubShip {
    private ShipVariantAPI variant;
    private final FleetMemberAPI scratch, member;

    JAOCheckShip(ShipVariantAPI variant, FleetMemberAPI scratch, FleetMemberAPI member) {
        this.variant = variant; this.scratch = scratch; this.member = member;
    }

    @Override public void setVariantForHullmodCheckOnly(ShipVariantAPI v) { if (v != null) variant = v; }
    @Override public ShipVariantAPI getVariant() { return variant; }
    @Override public ShipHullSpecAPI getHullSpec() { return variant.getHullSpec(); }
    @Override public ShipAPI.HullSize getHullSize() { return variant.getHullSize(); }
    @Override public boolean isFrigate() { return getHullSize() == ShipAPI.HullSize.FRIGATE; }
    @Override public boolean isDestroyer() { return getHullSize() == ShipAPI.HullSize.DESTROYER; }
    @Override public boolean isCruiser() { return getHullSize() == ShipAPI.HullSize.CRUISER; }
    @Override public boolean isCapital() { return getHullSize() == ShipAPI.HullSize.CAPITAL_SHIP; }
    @Override public boolean isFighter() { return getHullSize() == ShipAPI.HullSize.FIGHTER; }

    @Override
    public ShieldAPI getShield() {
        ShipHullSpecAPI h = variant.getHullSpec();
        ShieldType t = h.getShieldType();
        if ((t != ShieldType.FRONT && t != ShieldType.OMNI) || h.getShieldSpec() == null || variant.hasHullMod(HullMods.SHIELD_SHUNT)) return null;
        return new Shield(t, h.getShieldSpec().getArc(), h.getShieldSpec().getUpkeepCost(), h.getShieldSpec().getFluxPerDamageAbsorbed());
    }

    @Override
    public ShipSystemAPI getPhaseCloak() {
        ShipHullSpecAPI h = variant.getHullSpec();
        return h.isPhase() || h.getShieldType() == ShieldType.PHASE ? new SystemStub(h.getShipDefenseId()) : null;
    }

    @Override
    public ShipSystemAPI getSystem() {
        String id = variant.getHullSpec().getShipSystemId();
        return id == null || id.isEmpty() ? null : new SystemStub(id);
    }

    @Override
    public MutableShipStatsAPI getMutableStats() {
        if (scratch == null) return null;
        scratch.setVariant(variant, false, true);
        return scratch.getStats();
    }

    @Override public FleetMemberAPI getFleetMember() { return member != null ? member : scratch; }
    @Override public String getFleetMemberId() { FleetMemberAPI m = getFleetMember(); return m == null ? null : m.getId(); }
    @Override public PersonAPI getCaptain() { return member == null ? null : member.getCaptain(); }
    @Override public String getId() { return getFleetMemberId(); }
    @Override public String getName() { return member == null ? variant.getHullSpec().getHullName() : member.getShipName(); }
    @Override public boolean isAlive() { return true; }
    @Override public float getHullLevel() { return 1f; }
    @Override public float getHitpoints() { return variant.getHullSpec().getHitpoints(); }
    @Override public float getMaxHitpoints() { return variant.getHullSpec().getHitpoints(); }
    @Override public float getCurrentCR() { return member == null ? 0.7f : member.getRepairTracker().getCR(); }
    @Override public boolean isShipWithModules() { return !variant.getStationModules().isEmpty(); }
    @Override public boolean isStation() { return variant.getHullSpec().getHints().contains(ShipHullSpecAPI.ShipTypeHints.STATION); }
    @Override public int getNumFighterBays() { return variant.getHullSpec().getFighterBays(); }
    @Override public List<ShipAPI> getChildModulesCopy() { return new ArrayList<>(); }
    @Override public List<WeaponAPI> getAllWeapons() { return new ArrayList<>(); }
    @Override public List<WeaponAPI> getUsableWeapons() { return new ArrayList<>(); }
    @Override public List<com.fs.starfarer.api.combat.FighterLaunchBayAPI> getLaunchBaysCopy() { return new ArrayList<>(); }
    @Override public List<com.fs.starfarer.api.combat.FighterWingAPI> getAllWings() { return new ArrayList<>(); }
    @Override public Set<String> getTags() { return new HashSet<>(); }
    @Override public Map<String, Object> getCustomData() { return new HashMap<>(); }
    @Override public <T> List<T> getListeners(Class<T> c) { return new ArrayList<>(); }
    @Override public Vector2f getLocation() { return new Vector2f(); }

    private static final class Shield extends JAOStubShield {
        final ShieldType type;
        final float arc, upkeep, eff;

        Shield(ShieldType type, float arc, float upkeep, float eff) { this.type = type; this.arc = arc; this.upkeep = upkeep; this.eff = eff; }

        @Override public ShieldType getType() { return type; }
        @Override public float getArc() { return arc; }
        @Override public float getActiveArc() { return arc; }
        @Override public float getUpkeep() { return upkeep; }
        @Override public float getFluxPerPointOfDamage() { return eff; }
        @Override public boolean isOff() { return true; }
        @Override public Vector2f getLocation() { return new Vector2f(); }
    }

    private static final class SystemStub extends JAOStubSystem {
        final String id;

        SystemStub(String id) { this.id = id; }

        @Override public String getId() { return id; }
        @Override public String getDisplayName() { return id; }
    }
}
