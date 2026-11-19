package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import org.json.JSONObject;

/**
 * How long Optimize fleet takes to plan (0.5.66, user 11/18). 0.5.72 (user 11/19: "it seems to overestimate at first, then
 * the time runs down rapid fire"): the planner gets a slice of each frame (25 ms on the game's thread, JAOCoop), so the
 * waiting time is the planner's own work divided by its share of the time (duty). A laggy campaign gives it a smaller
 * share: the user's 221-kind run went at 2 evaluations per ms against 15-40 in their other runs, took 64 s against 7,
 * and set the learned rate 5x too high for the next runs, whose estimate then fell fast once ships came in.
 *  - Work of one ship pass: slots^1.55 summed over the ship and its modules (each module is searched on its own), times
 *    kinds^0.9 (575 logged ship searches: time ~ slots^1.53 x kinds^0.83).
 *  - rate: the planner's own seconds per unit of work, learned per run from the time it ran (JAOFleetPlan.nanos), half
 *    old and half new, moving at most x3 a run. duty: its share of the waiting time, learned the same way.
 *  - While it plans: the ships done so far correct the rate (weighted against a prior of a third of the estimate) and
 *    the duty (weighted against 10 s at the learned duty), and the time shown counts down at most 1.5x real time.
 * Kept in the game's common folder (jao_plan_times.json), shared by every save; 0.5.67/0.5.68 values are left out.
 */
final class JAOPlanTimes {
    static final String FILE = "jao_plan_times.json";
    static final float EXP_SLOTS = 1.55f, EXP_KINDS = 0.9f;
    /** The planner's seconds per unit of work, and its share of the waiting time (the user's runs of 11/18-19). */
    static final float DEFAULT_RATE = 2e-5f, DEFAULT_DUTY = 0.7f;
    static final float DEFAULT_CELL = 0.006f, DEFAULT_PASSES = 2.3f;
    /** A civilian ship's logistics fit, planned once. */
    static final float CIVILIAN_SECONDS = 0.3f;
    /** Weight of a new run, and the most one run moves the rate. */
    static final float LEARN = 0.5f, MAX_STEP = 3f;

    static boolean loaded;
    static float rate, duty, cell, passes;

    private JAOPlanTimes() { }

    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        rate = DEFAULT_RATE;
        duty = DEFAULT_DUTY;
        cell = DEFAULT_CELL;
        passes = DEFAULT_PASSES;
        try {
            if (!Global.getSettings().fileExistsInCommon(FILE)) return;
            String text = Global.getSettings().readTextFileFromCommon(FILE);
            if (text == null || text.trim().isEmpty()) return;
            JSONObject o = new JSONObject(text);
            // 0.5.72: "cpuRate" (the planner's own time); 0.5.67's rate0/rate1 and 0.5.68's rate (waiting time) are left out
            rate = sane((float) o.optDouble("cpuRate", rate), rate);
            duty = Math.max(0.05f, Math.min(1f, sane((float) o.optDouble("duty", duty), duty)));
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
            o.put("cpuRate", rate);
            o.put("duty", duty);
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

    /** learn(), moving at most x MAX_STEP either way. */
    static float learnCapped(float old, float measured) {
        float v = learn(old, measured);
        return Math.max(old / MAX_STEP, Math.min(old * MAX_STEP, v));
    }

    /** Mounts the search fills and bays of a hull: no built-in, decorative, system or module slots. */
    static int slots(ShipHullSpecAPI hull) {
        int n = 0;
        try {
            for (WeaponSlotAPI s : hull.getAllWeaponSlotsCopy()) {
                if (s.isBuiltIn() || s.isDecorative() || s.isSystemSlot() || s.isStationModule()) continue;
                if (s.getWeaponType() == WeaponType.BUILT_IN || s.getWeaponType() == WeaponType.DECORATIVE || s.getWeaponType() == WeaponType.SYSTEM) continue;
                n++;
            }
            n += hull.getFighterBays();
        } catch (Throwable ignored) { }
        return Math.max(1, n);
    }

    static float slotWork(int slots) {
        return (float) Math.pow(Math.max(1, slots), EXP_SLOTS);
    }

    /** A ship's work before kinds: slots^1.55 for the hull and for each module (fitted on its own). */
    static float shipWork(FleetMemberAPI m) {
        float w = 0f;
        try {
            w = slotWork(slots(m.getHullSpec()));
            ShipVariantAPI v = m.getVariant();
            if (v != null) {
                for (String slot : v.getStationModules().keySet()) {
                    ShipVariantAPI mv = v.getModuleVariant(slot);
                    if (mv != null) w += slotWork(slots(mv.getHullSpec()));
                }
            }
        } catch (Throwable ignored) { }
        return Math.max(1f, w);
    }

    static float kindsFactor(int kinds) {
        return (float) Math.pow(Math.max(1, kinds), EXP_KINDS);
    }

    /**
     * The planner's rate scaled by the ships done so far: their own time over their estimate, weighted against a prior of
     * prior seconds (so the first ship doesn't swing it).
     */
    static float liveRate(float cpuSoFar, float workDone, float prior) {
        load();
        if (!(workDone > 0f) || !(cpuSoFar >= 0f)) return rate;
        float p = Math.max(0.5f, prior);
        return rate * (cpuSoFar + p) / (workDone * rate + p);
    }

    /** The planner's share of the waiting time so far, weighted against 10 s at the learned share. */
    static float liveDuty(float cpuSoFar, float wallSoFar) {
        load();
        float w0 = 10f;
        float d = (Math.max(0f, cpuSoFar) + duty * w0) / (Math.max(0f, wallSoFar) + w0);
        return Math.max(0.05f, Math.min(1f, d));
    }

    /**
     * The time shown: counts down with the clock, follows a lower estimate at most 1.5x real time and a higher one over
     * about 5 s.
     */
    static float smooth(float shown, float estimate, float dt) {
        if (shown < 0f || Float.isNaN(shown)) return estimate;
        float t = Math.max(0f, dt);
        float s = shown - t;
        if (estimate < s) return Math.max(estimate, s - 0.5f * t);
        return s + (estimate - s) * Math.min(1f, t / 5f);
    }

    /** A finished plan: the planner's own seconds over the work done (warship passes), its waiting time, passes run. */
    static synchronized void recordRun(float cpuSeconds, float wallSeconds, float work, int passesRun) {
        load();
        if (work > 0f && cpuSeconds > 0f) rate = learnCapped(rate, cpuSeconds / work);
        if (wallSeconds > 0f && cpuSeconds > 0f) duty = Math.max(0.05f, Math.min(1f, learn(duty, cpuSeconds / wallSeconds)));
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

    /** Waiting seconds for the whole plan: one pass of work for the expected passes at the learned share, civilians, officers. */
    static float estimate(float passWork, int civilians, int officerCells) {
        load();
        return passWork * rate * passes / duty + civilians * CIVILIAN_SECONDS + officerCells * cell;
    }

    /** "about 40 seconds", "about a minute", "about 4 minutes" (under 10 seconds: "a few seconds"). */
    static String text(float seconds) {
        if (Float.isNaN(seconds) || seconds < 10f) return "a few seconds";
        if (seconds < 50f) return "about " + Math.round(seconds / 10f) * 10 + " seconds";
        int min = Math.round(seconds / 60f);
        return min <= 1 ? "about a minute" : "about " + min + " minutes";
    }
}
