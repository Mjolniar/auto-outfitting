package data.scripts.jonahsautooutfitting;

import java.util.Locale;

/**
 * What a ship is for in a fleet, so the Optimal fit builds for its job and not only for its numbers
 * (docs/design.md 7d). Each role sets who the ship mostly fights, how much firepower counts against staying alive,
 * how much speed is worth, the range it wants, how much point defense it needs, how much its fighters count, how much
 * outrunning its targets keeps it alive, and (with the Auto build style) which layer of a kill it concentrates on.
 *
 * Detected from the hull's designation ("Phase Frigate", "Battlecarrier", "Missile Cruiser" ... modders fill it in
 * too), then its numbers (bays, phase, ship system, missile share, speed against the loaded hulls of its size, size).
 * jao_rules "role" rows override it per hull.
 */
public enum JAORole {
    //            label            offense mobility  targets F / D / C / Cap      kill layers S / A / H  range pd   wings evasion
    SKIRMISHER("Skirmisher",      0.45f, 0.25f, new float[]{0.45f, 0.35f, 0.15f, 0.05f}, 1.2f, 0.9f, 0.9f, 0, 0.7f, 1f, 0.5f,
            "frigate work: grabs objectives, keeps enemies busy, runs down weak ships and stays alive until the line arrives"),
    FLANKER("Flanker",            0.65f, 0.12f, new float[]{0.3f, 0.35f, 0.25f, 0.1f}, 0.7f, 1.15f, 1.15f, 0, 0.8f, 1f, 0.3f,
            "exploits weakness: hits ships whose shields are down or that are already hurt"),
    LINE("Line",                  0.6f, 0.03f, new float[]{0.1f, 0.25f, 0.35f, 0.3f}, 1f, 1f, 1f, 1, 1f, 1f, 0f,
            "ship of the line: steady damage from range, holds the line"),
    ANCHOR("Anchor",              0.55f, 0f, new float[]{0.05f, 0.15f, 0.4f, 0.4f}, 1.1f, 1f, 0.9f, 1, 1.1f, 1f, 0f,
            "capital anchor: trades with the enemy's biggest ships and soaks the pressure"),
    CARRIER("Carrier",            0.55f, 0.05f, null, 1f, 1f, 1f, 1, 1.3f, 1.4f, 0f,
            "fighters do the work; stays back, guns protect the ship"),
    BATTLE_CARRIER("Battle carrier", 0.62f, 0.03f, new float[]{0.1f, 0.25f, 0.35f, 0.3f}, 1f, 1f, 1f, 0, 1.1f, 1.2f, 0f,
            "fighters plus a real gun line: carries and assaults"),
    SUPPORT("Support",            0.45f, 0.05f, new float[]{0.35f, 0.35f, 0.2f, 0.1f}, 1.2f, 0.9f, 0.9f, 1, 1.6f, 1.1f, 0.15f,
            "escort: point defense and covering fire for the rest of the fleet"),
    PHASE("Phase",                0.7f, 0.1f, new float[]{0.2f, 0.35f, 0.3f, 0.15f}, 0.5f, 1.2f, 1.3f, 2, 0.5f, 1f, 0.15f,
            "phase assassin: dives in, unloads burst damage, phases out"),
    MISSILE_BOAT("Missile boat",  0.6f, 0.05f, new float[]{0.1f, 0.3f, 0.35f, 0.25f}, 0.6f, 1.6f, 0.8f, 1, 1f, 1f, 0.05f,
            "missile platform: strips armor from a distance"),
    CIVILIAN("Civilian",          0.3f, 0.15f, new float[]{0.6f, 0.3f, 0.1f, 0f}, 1f, 1f, 1f, 0, 1.3f, 1f, 0.3f,
            "armed civilian: stays alive, defends itself"),
    // 0.5.59: slow, tough ships too slow to flank or run (Enforcer, Dominator, Monitor and their mod kin)
    BRAWLER("Brawler",            0.6f, 0.04f, null, 1f, 1.05f, 0.95f, 0, 1f, 1f, 0f,
            "close-range bruiser: trades blows behind its armor and shield");

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
    /**
     * How much outrunning the ships it fights keeps it alive (0.5.59): survival x (1 + evasion x speed edge), the edge
     * measured against the median speed of each target size (JAOModel.evasion). Ships that run, dodge and come back.
     */
    public final float evasion;

    JAORole(String label, float offense, float mobility, float[] targets, float shield, float armor, float hull,
            int range, float pd, float wings, float evasion, String purpose) {
        this.label = label; this.offense = offense; this.mobility = mobility; this.targets = targets;
        this.shield = shield; this.armor = armor; this.hull = hull; this.range = range; this.pd = pd; this.wings = wings;
        this.evasion = evasion; this.purpose = purpose;
    }

    /** Median max speed of the loaded combat hulls per size when the game can't be read (offline checks): all mods, 11/16. */
    static final float[] DEFAULT_SPEED = {110f, 80f, 60f, 40f};
    /** Speed against the median of its size below which a ship can't flank or run (0.5.59). */
    static final float SLOW = 0.92f;
    /** Speed against the median of its size at which a cruiser fights like a flanker. */
    static final float FAST = 1.3f;

    /** Ship systems that dash in close (Plasma Jets, Microburn, Displacer, Phase Teleporter, Temporal Shell, mod boosts). */
    static boolean assaultSystem(String system) {
        if (system == null) return false;
        String s = system.toLowerCase(Locale.ROOT);
        return s.equals("plasmajets") || s.equals("microburn") || s.equals("displacer") || s.equals("phaseteleporter")
                || s.equals("temporalshell") || s.contains("quickboost") || s.contains("punisherjets") || blinkSystem(system)
                // 0.5.102: copies of Plasma Burn and Temporal Shell under other ids (Valkyrian Burst Drive, Threat Tech
                // microburn, the mods' temporal shells)
                || s.contains("microburn") || s.endsWith("temporalshell") || copyOf(system, BURN_COPIES) || copyOf(system, SHELL_COPIES);
    }

    static final String[] BURN_COPIES = {"MicroBurnStats", "MicroBurnOmegaStats"}, SHELL_COPIES = {"TemporalShellStats"};

    /** 0.5.102: system id -> its stats script class name ("" unknown), cached. */
    private static final java.util.Map<String, String> STATS_CLASS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 0.5.102: the system's stats script is one of these classes (by simple name). False when the game can't be read. */
    static boolean copyOf(String system, String[] classes) {
        if (system == null || system.isEmpty()) return false;
        String c = STATS_CLASS.get(system);
        if (c == null) {
            if (com.fs.starfarer.api.Global.getSettings() == null) return false;
            c = "";
            try {
                com.fs.starfarer.api.combat.ShipSystemSpecAPI spec = com.fs.starfarer.api.Global.getSettings().getShipSystemSpec(system);
                if (spec != null && spec.getStatsScriptClassName() != null) c = spec.getStatsScriptClassName();
            } catch (Throwable ignored) { }
            STATS_CLASS.put(system, c);
        }
        for (String k : classes) if (c.equals(k) || c.endsWith("." + k)) return true;
        return false;
    }

    /** 0.5.102: system id -> {burst movement potential, average speed increase} from its AI hints, cached. */
    private static final java.util.Map<String, float[]> MOVE = new java.util.concurrent.ConcurrentHashMap<>();

    static float[] movement(String system) {
        float[] m = MOVE.get(system);
        if (m != null) return m;
        if (com.fs.starfarer.api.Global.getSettings() == null) return new float[2];
        m = new float[2];
        try {
            com.fs.starfarer.api.combat.ShipSystemSpecAPI spec = com.fs.starfarer.api.Global.getSettings().getShipSystemSpec(system);
            if (spec != null && spec.getAIHints2() != null) {
                m[0] = spec.getAIHints2().getBurstMovementPotential();
                m[1] = spec.getAIHints2().getAverageSpeedIncrease();
            }
        } catch (Throwable ignored) { }
        MOVE.put(system, m);
        return m;
    }

    /** 0.5.101: system id -> teleporter or displacer read from its spec, cached (the planner thread reads it too). */
    private static final java.util.Map<String, Boolean> BLINK = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 0.5.101: a TELEPORTER or DISPLACER system under any id (A_S-F Light Phase Teleporter, IX and Vice teleporters,
     * mod skimmers): the spec renders a copy during the jump and the AI hints give it burst movement. Energy Conversion
     * (a DISPLACER with no movement) is left out by the movement check. False when the game can't be read.
     */
    static boolean blinkSystem(String system) {
        if (system == null || system.isEmpty()) return false;
        Boolean known = BLINK.get(system);
        if (known != null) return known;
        boolean blink = false;
        if (com.fs.starfarer.api.Global.getSettings() == null) return false;
        try {
            com.fs.starfarer.api.combat.ShipSystemSpecAPI spec = com.fs.starfarer.api.Global.getSettings().getShipSystemSpec(system);
            blink = spec != null && spec.isRenderCopyDuringTeleport() && spec.getAIHints2() != null
                    && spec.getAIHints2().getBurstMovementPotential() >= BLINK_MIN_BURST;
        } catch (Throwable t) {
            return false;
        }
        // 0.5.102: the .system file's type with a jump range (Diable Flicker cores, Valkyrian and JaydeePiracy teleporters:
        // no copy render or no burst hint); Energy Conversion has range 0
        if (!blink) {
            try {
                org.json.JSONObject j = com.fs.starfarer.api.Global.getSettings().loadJSON("data/shipsystems/" + system + ".system");
                String type = j.optString("type", "");
                blink = ("TELEPORTER".equals(type) || "DISPLACER".equals(type)) && j.optDouble("range", 0) > 0;
            } catch (Throwable ignored) { }
        }
        BLINK.put(system, blink);
        return blink;
    }

    /** Burst movement at which a jump system counts (the shortest mod displacers list 100; vanilla Displacer 300). */
    static final float BLINK_MIN_BURST = 100f;

    /**
     * How far the ship system moves the ship out of trouble (0.5.59): 2 for skimmers and teleporters (Phase Skimmer,
     * Phase Teleporter and copies), 1 for dashes and boosts (Burn Drive, Plasma Jets, Orion Device, Temporal Shell,
     * Maneuvering Jets and mod systems named like them), 0 for Plasma Burn (microburn) and the rest.
     */
    static int dashLevel(String system) {
        if (system == null || system.isEmpty()) return 0;
        String s = system.toLowerCase(Locale.ROOT);
        if (s.contains("displacer") || s.contains("teleport") || s.contains("skimmer") || s.contains("blink") || s.contains("warp")
                || blinkSystem(system)) return 2;
        // the AI fires Plasma Burn only to close in, never to break off (roles_1116: Shrike, Fury, Odyssey, Brilliant);
        // 0.5.102: its copies too (Threat Tech microburn, Valkyrian Burst Drive)
        if (s.contains("microburn") || copyOf(system, BURN_COPIES)) return 0;
        if (s.contains("burn") || s.contains("jets") || s.contains("boost") || s.contains("dash") || s.contains("orion")
                || s.endsWith("temporalshell") || s.contains("lunge") || s.contains("thruster") || s.contains("afterburn")
                || copyOf(system, SHELL_COPIES)) return 1;
        // 0.5.102: movement systems named without a keyword (Tahlan drives, Seeker Alcubierre, UAF evasive jets): the AI hints
        float[] m = movement(system);
        if (m[0] >= 300f || m[1] >= 100f) return 1;
        return 0;
    }

    static JAORole named(String s) {
        if (s == null) return null;
        for (JAORole r : values()) if (r.label.equalsIgnoreCase(s.trim()) || r.name().equalsIgnoreCase(s.trim())) return r;
        return null;
    }

    /** Before 0.5.59 (no speed): every hull at the median speed of its size. */
    static JAORole detect(String designation, int size, boolean phase, int bays, float gunShare, float missileShare, boolean civilian, String system) {
        return detect(designation, size, phase, bays, gunShare, missileShare, civilian, system, 1f);
    }

    /**
     * size 0-3; bays = fighter bays; gunShare = share of slot OP potential in weapon slots against hull OP;
     * missileShare = share of weapon slot potential that is missile slots; civilian = hull is a civilian non-carrier;
     * system = ship system id (close-in assault systems make a plain cruiser or destroyer a flanker); speedRatio = the
     * hull's max speed against the median of the loaded combat hulls of its size (0.5.59: slow frigates and destroyers
     * brawl, very fast cruisers flank).
     */
    static JAORole detect(String designation, int size, boolean phase, int bays, float gunShare, float missileShare, boolean civilian,
                          String system, float speedRatio) {
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
        boolean slow = speedRatio > 0f && speedRatio < SLOW && dashLevel(system) < 2;
        switch (size) {
            case 0: return slow ? BRAWLER : SKIRMISHER;
            case 1: return slow ? BRAWLER : FLANKER;
            case 2: return speedRatio >= FAST ? FLANKER : speedRatio > 0f && speedRatio < 0.7f ? BRAWLER : LINE;
            default: return ANCHOR;
        }
    }
}
