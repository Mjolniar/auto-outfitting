package data.scripts.jonahsautooutfitting;

import java.util.Locale;

/**
 * What a ship is for in a fleet, so the Optimal fit builds for its job and not only for its numbers
 * (docs/design.md 7d). Each role sets who the ship mostly fights, how much firepower counts against staying alive,
 * how much speed is worth, the range it wants, how much point defense it needs, how much its fighters count, and
 * (with the Auto build style) which layer of a kill it concentrates on.
 *
 * Detected from the hull's designation ("Phase Frigate", "Battlecarrier", "Missile Cruiser" ... modders fill it in
 * too), then its numbers (bays, phase, missile share, size). jao_rules "role" rows override it per hull.
 */
public enum JAORole {
    //            label            offense mobility  targets F / D / C / Cap      kill layers S / A / H  range pd   wings
    SKIRMISHER("Skirmisher",      0.45f, 0.25f, new float[]{0.45f, 0.35f, 0.15f, 0.05f}, 1.2f, 0.9f, 0.9f, 0, 0.7f, 1f,
            "frigate work: grab objectives fast and stay alive until the line arrives"),
    FLANKER("Flanker",            0.65f, 0.12f, new float[]{0.3f, 0.35f, 0.25f, 0.1f}, 0.7f, 1.15f, 1.15f, 0, 0.8f, 1f,
            "exploits weakness: hits ships whose shields are down or that are already hurt"),
    LINE("Line",                  0.6f, 0.03f, new float[]{0.1f, 0.25f, 0.35f, 0.3f}, 1f, 1f, 1f, 1, 1f, 1f,
            "ship of the line: steady damage from range, holds the line"),
    ANCHOR("Anchor",              0.55f, 0f, new float[]{0.05f, 0.15f, 0.4f, 0.4f}, 1.1f, 1f, 0.9f, 1, 1.1f, 1f,
            "capital anchor: trades with the enemy's biggest ships and soaks the pressure"),
    CARRIER("Carrier",            0.55f, 0.05f, null, 1f, 1f, 1f, 1, 1.3f, 1.4f,
            "fighters do the work; stays back, guns protect the ship"),
    BATTLE_CARRIER("Battle carrier", 0.62f, 0.03f, new float[]{0.1f, 0.25f, 0.35f, 0.3f}, 1f, 1f, 1f, 0, 1.1f, 1.2f,
            "fighters plus a real gun line: carries and assaults"),
    SUPPORT("Support",            0.45f, 0.05f, new float[]{0.35f, 0.35f, 0.2f, 0.1f}, 1.2f, 0.9f, 0.9f, 1, 1.6f, 1.1f,
            "escort: point defense and covering fire for the rest of the fleet"),
    PHASE("Phase",                0.7f, 0.1f, new float[]{0.2f, 0.35f, 0.3f, 0.15f}, 0.5f, 1.2f, 1.3f, 2, 0.5f, 1f,
            "phase assassin: dives in, unloads burst damage, phases out"),
    MISSILE_BOAT("Missile boat",  0.6f, 0.05f, new float[]{0.1f, 0.3f, 0.35f, 0.25f}, 0.6f, 1.6f, 0.8f, 1, 1f, 1f,
            "missile platform: strips armor from a distance"),
    CIVILIAN("Civilian",          0.3f, 0.15f, new float[]{0.6f, 0.3f, 0.1f, 0f}, 1f, 1f, 1f, 0, 1.3f, 1f,
            "armed civilian: stays alive, defends itself");

    public final String label, purpose;
    /** Weight of firepower against staying alive in the score (survival gets the rest). */
    public final float offense;
    /** Exponent on max speed against the hull's own speed (speed hullmods). */
    public final float mobility;
    /** Who it mostly fights (frigate, destroyer, cruiser, capital); null = by its own size. */
    public final float[] targets;
    /** Kill layers it concentrates on with the Auto style (shield, armor, hull; sums to 3). */
    public final float shield, armor, hull;
    /** 0 any, 1 long, 2 close (used when the Range setting is Auto). */
    public final int range;
    public final float pd, wings;

    JAORole(String label, float offense, float mobility, float[] targets, float shield, float armor, float hull,
            int range, float pd, float wings, String purpose) {
        this.label = label; this.offense = offense; this.mobility = mobility; this.targets = targets;
        this.shield = shield; this.armor = armor; this.hull = hull; this.range = range; this.pd = pd; this.wings = wings;
        this.purpose = purpose;
    }

    /** Ship systems that dash in close (Plasma Jets, Microburn, Displacer, Phase Teleporter, Temporal Shell, mod boosts). */
    static boolean assaultSystem(String system) {
        if (system == null) return false;
        String s = system.toLowerCase(Locale.ROOT);
        return s.equals("plasmajets") || s.equals("microburn") || s.equals("displacer") || s.equals("phaseteleporter")
                || s.equals("temporalshell") || s.contains("quickboost") || s.contains("punisherjets");
    }

    static JAORole named(String s) {
        if (s == null) return null;
        for (JAORole r : values()) if (r.label.equalsIgnoreCase(s.trim()) || r.name().equalsIgnoreCase(s.trim())) return r;
        return null;
    }

    /**
     * size 0-3; bays = fighter bays; gunShare = share of slot OP potential in weapon slots against hull OP;
     * missileShare = share of weapon slot potential that is missile slots; civilian = hull is a civilian non-carrier;
     * system = ship system id (close-in assault systems make a plain cruiser or destroyer a flanker).
     */
    static JAORole detect(String designation, int size, boolean phase, int bays, float gunShare, float missileShare, boolean civilian, String system) {
        String d = designation == null ? "" : designation.toLowerCase(Locale.ROOT);
        if (d.contains("phase")) return PHASE;
        if (d.contains("battlecarrier") || d.contains("battle carrier")) return BATTLE_CARRIER;
        if ((d.contains("carrier") || d.contains("mothership")) && bays > 0) return CARRIER;
        if (d.contains("escort") || d.contains("support") || d.contains("picket")) return SUPPORT;
        if (d.contains("missile")) return MISSILE_BOAT;
        if (civilian) return CIVILIAN;
        if (d.contains("battleship") || d.contains("dreadnought") || d.contains("battlewagon") || d.contains("juggernau")
                || d.contains("titan") || d.contains("doomwagon")) return ANCHOR;
        if (d.contains("battlecruiser") || d.contains("artillery")) return LINE;
        if (d.contains("fast") || d.contains("light cruiser") || d.contains("light destroyer") || d.contains("strike")
                || d.contains("raider") || d.contains("pursuit") || d.contains("interceptor")) return size == 0 ? SKIRMISHER : FLANKER;
        if (phase) return PHASE;
        if (bays >= 2 && gunShare < 0.5f) return CARRIER;
        if (size <= 2 && assaultSystem(system)) return size == 0 ? SKIRMISHER : FLANKER;
        if (bays >= 1 && size >= 2) return BATTLE_CARRIER;
        if (missileShare >= 0.45f) return MISSILE_BOAT;
        switch (size) {
            case 0: return SKIRMISHER;
            case 1: return FLANKER;
            case 2: return LINE;
            default: return ANCHOR;
        }
    }
}
