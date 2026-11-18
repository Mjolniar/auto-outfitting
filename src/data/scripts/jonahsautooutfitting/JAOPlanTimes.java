package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import org.json.JSONObject;

/**
 * How long Optimize fleet takes to plan (0.5.66, user 11/18: "7 giant battleships that needed completely new mods took 5
 * minutes"; the panel said a big fleet takes a few seconds). 0.5.68 (user: the estimate was far off and the time left
 * kept growing): a ship's pass takes rate x slots^1.6 x kinds seconds (choosable mounts and bays; kinds of weapons and
 * LPCs it can pick from). The 1.6 is from 218 logged ship searches (time ~ slots^1.65); s-mod planning was no
 * predictor (capitals with all their s-mods took 12-34 s a pass in the user's 11/18 run). The rate is learned per whole
 * run (seconds over work, half old and half new), so the order ships are recorded in doesn't matter (0.5.67 learned
 * ship by ship, and the last, smallest ships set the rate). While a plan runs, ships not planned yet are scaled by how
 * the ships already planned compared with their estimate, so the time left settles after the first ships. Kept in the
 * game's common folder (jao_plan_times.json), shared by every save.
 */
final class JAOPlanTimes {
    static final String FILE = "jao_plan_times.json";
    /** Seconds per pass per slots^EXP per kind of weapon or LPC (the user's 11/18 run: capitals of about 25 slots, 20 s a pass). */
    static final float DEFAULT_RATE = 1.5e-4f, EXP = 1.6f;
    static final float DEFAULT_CELL = 0.006f, DEFAULT_PASSES = 2.5f;
    /** A civilian ship's logistics fit, planned once. */
    static final float CIVILIAN_SECONDS = 0.3f;
    /** Weight of a new measurement. */
    static final float LEARN = 0.5f;

    static boolean loaded;
    static float rate, cell, passes;

    private JAOPlanTimes() { }

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        rate = DEFAULT_RATE;
        cell = DEFAULT_CELL;
        passes = DEFAULT_PASSES;
        try {
            if (!Global.getSettings().fileExistsInCommon(FILE)) return;
            String text = Global.getSettings().readTextFileFromCommon(FILE);
            if (text == null || text.trim().isEmpty()) return;
            JSONObject o = new JSONObject(text);
            // 0.5.68: "rate" (slots^1.6 work); 0.5.67's rate0 / rate1 are left out
            rate = sane((float) o.optDouble("rate", rate), rate);
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
            o.put("rate", rate);
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

    /** Work of one pass of one ship: slots^EXP x kinds. */
    static float work(int slots, int kinds) {
        return (float) Math.pow(Math.max(1, slots), EXP) * Math.max(1, kinds);
    }

    /** Seconds one pass of one ship takes. */
    static float passSeconds(int slots, int kinds) {
        load();
        return rate * work(slots, kinds);
    }

    /**
     * An estimate scaled by how the ships planned so far compared with theirs (measured over estimated seconds, held to
     * 0.02-50); unchanged before any ship ran.
     */
    static float corrected(float estimate, float measuredSum, float estimatedSum) {
        if (!(measuredSum > 0f) || !(estimatedSum > 0f)) return estimate;
        return estimate * Math.max(0.02f, Math.min(50f, measuredSum / estimatedSum));
    }

    /** A finished plan: warships' seconds over their work (every pass), passes run. */
    static synchronized void recordRun(float seconds, float work, int passesRun) {
        load();
        if (work > 0f) rate = learn(rate, seconds / work);
        if (passesRun > 0) passes = Math.max(1f, Math.min(3f, learn(passes, passesRun)));
    }

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
