package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import org.json.JSONObject;

/**
 * How long Optimize fleet takes to plan (0.5.66, user 11/18: "7 giant battleships that needed completely new mods took 5
 * minutes"; the panel said a big fleet takes a few seconds). A ship's search grows with its choosable mounts and bays
 * times the kinds of weapons and LPCs it can pick from, and much more when it plans s-mods (every candidate s-mod is a
 * fit of its own). Per pass a ship takes rate x slots x kinds seconds, with one rate for ships that plan s-mods and one
 * for ships that don't; officer placement takes a fixed time per officer and ship pair.
 *
 * The rates start from the user's 11/18 run (7 capitals with new s-mods in about 5 minutes: about 20 slots, a few hundred
 * kinds, 2-3 passes) and the logged ship searches (median 0.1 s a pass), and are learned
 * from every finished plan (half old, half new), so they follow the player's PC. They're kept in the game's common
 * folder (jao_plan_times.json), shared by every save.
 */
final class JAOPlanTimes {
    static final String FILE = "jao_plan_times.json";
    /** Seconds per pass per choosable slot per kind of weapon or LPC: [0] no s-mods, [1] with s-mod planning. */
    static final float[] DEFAULT_RATE = {0.0002f, 0.004f};
    static final float DEFAULT_CELL = 0.006f, DEFAULT_PASSES = 2.5f;
    /** A civilian ship's logistics fit, planned once. */
    static final float CIVILIAN_SECONDS = 0.3f;
    /** Weight of a new measurement. */
    static final float LEARN = 0.5f;

    static float[] rate;
    static float cell, passes;

    private JAOPlanTimes() { }

    static synchronized void load() {
        if (rate != null) return;
        rate = DEFAULT_RATE.clone();
        cell = DEFAULT_CELL;
        passes = DEFAULT_PASSES;
        try {
            if (!Global.getSettings().fileExistsInCommon(FILE)) return;
            String text = Global.getSettings().readTextFileFromCommon(FILE);
            if (text == null || text.trim().isEmpty()) return;
            JSONObject o = new JSONObject(text);
            rate[0] = sane((float) o.optDouble("rate0", rate[0]), rate[0]);
            rate[1] = sane((float) o.optDouble("rate1", rate[1]), rate[1]);
            cell = sane((float) o.optDouble("cell", cell), cell);
            passes = Math.max(1f, Math.min(3f, sane((float) o.optDouble("passes", passes), passes)));
        } catch (Throwable t) {
            Global.getLogger(JAOPlanTimes.class).warn("Auto Outfitting: couldn't read " + FILE + "; using the default planning times", t);
        }
    }

    static synchronized void save() {
        load();
        try {
            JSONObject o = new JSONObject();
            o.put("rate0", rate[0]);
            o.put("rate1", rate[1]);
            o.put("cell", cell);
            o.put("passes", passes);
            Global.getSettings().writeTextFileToCommon(FILE, o.toString());
        } catch (Throwable t) {
            Global.getLogger(JAOPlanTimes.class).warn("Auto Outfitting: couldn't save " + FILE, t);
        }
    }

    static float sane(float v, float def) {
        return Float.isNaN(v) || Float.isInfinite(v) || v <= 0f ? def : v;
    }

    /** Half the old value, half the new one (a bad value is ignored). */
    static float learn(float old, float measured) {
        if (Float.isNaN(measured) || Float.isInfinite(measured) || measured <= 0f) return old;
        return old * (1f - LEARN) + measured * LEARN;
    }

    /** Mounts the search fills and bays: no built-in, decorative or system slots. */
    static int slots(FleetMemberAPI m) {
        int n = 0;
        try {
            for (WeaponSlotAPI s : m.getHullSpec().getAllWeaponSlotsCopy()) {
                if (s.isBuiltIn() || s.isDecorative() || s.isSystemSlot() || s.isStationModule()) continue;
                if (s.getWeaponType() == WeaponType.BUILT_IN || s.getWeaponType() == WeaponType.DECORATIVE || s.getWeaponType() == WeaponType.SYSTEM) continue;
                n++;
            }
            n += m.getNumFlightDecks();
        } catch (Throwable ignored) { }
        return Math.max(1, n);
    }

    /** Seconds one pass of one ship takes. */
    static float passSeconds(boolean smods, int slots, int kinds) {
        load();
        return rate[smods ? 1 : 0] * Math.max(1, slots) * Math.max(1, kinds);
    }

    /** A finished ship: its seconds per pass, slots, kinds, and whether it planned s-mods. */
    static synchronized void recordShip(boolean smods, int slots, int kinds, float secondsPerPass) {
        load();
        int i = smods ? 1 : 0;
        rate[i] = learn(rate[i], secondsPerPass / (Math.max(1, slots) * (float) Math.max(1, kinds)));
    }

    static synchronized void recordPasses(int n) { load(); passes = Math.max(1f, Math.min(3f, learn(passes, n))); }

    static synchronized void recordCells(int cells, float seconds) {
        if (cells <= 0) return;
        load();
        cell = learn(cell, seconds / cells);
    }

    /** Seconds this many officer and ship pairs take. */
    static float cellSeconds(int cells) {
        load();
        return Math.max(0, cells) * cell;
    }

    /** Seconds the whole plan takes: warships for the expected passes, civilians once, officer and ship pairs. */
    static float estimate(float warshipPassSeconds, int civilians, int officerCells) {
        load();
        return warshipPassSeconds * passes + civilians * CIVILIAN_SECONDS + officerCells * cell;
    }

    /** "about 40 seconds", "about a minute", "about 4 minutes" (under 10 seconds: "a few seconds"). */
    static String text(float seconds) {
        if (Float.isNaN(seconds) || seconds < 10f) return "a few seconds";
        if (seconds < 50f) return "about " + Math.round(seconds / 10f) * 10 + " seconds";
        int min = Math.round(seconds / 60f);
        return min <= 1 ? "about a minute" : "about " + min + " minutes";
    }
}
