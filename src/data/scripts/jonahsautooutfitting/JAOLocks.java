package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Ships the player locked on the refit screen (0.5.21, user 11/11: "It needs to be possible to lock ships from being
 * auto outfitted, somewhere on the main outfitting screen"). Optimize fleet and Order fleet weapons leave a locked ship
 * as it is and don't use what it carries; it still counts toward the fleet's balance. The Autofit button's Optimal fit
 * still works on it (the player picks it for that one ship).
 *
 * Stored as fleet member ids in a plain HashSet in the sector's persistent data, so a save never needs this mod.
 */
final class JAOLocks {
    private JAOLocks() { }

    static final String KEY = "jao_locked";

    @SuppressWarnings("unchecked")
    private static Set<String> ids(boolean create) {
        if (Global.getSector() == null) return null;
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object o = data.get(KEY);
        if (o instanceof Set) return (Set<String>) o;
        if (!create) return null;
        Set<String> s = new HashSet<>();
        data.put(KEY, s);
        return s;
    }

    static boolean locked(FleetMemberAPI m) {
        if (m == null || m.getId() == null) return false;
        Set<String> s = ids(false);
        return s != null && s.contains(m.getId());
    }

    /** Locks an unlocked ship or unlocks a locked one; returns the new state. */
    static boolean toggle(FleetMemberAPI m) {
        if (m == null || m.getId() == null) return false;
        Set<String> s = ids(true);
        if (s == null) return false;
        boolean now = !s.remove(m.getId());
        if (now) s.add(m.getId());
        Global.getLogger(JAOLocks.class).info("Auto Outfitting: " + name(m) + (now ? " locked" : " unlocked") + " (" + s.size() + " locked)");
        return now;
    }

    static String name(FleetMemberAPI m) {
        String n = m.getShipName();
        return (n == null || n.isEmpty() ? m.getHullSpec().getHullName() : n) + " (" + m.getHullSpec().getNameWithDesignationWithDashClass() + ")";
    }
}
