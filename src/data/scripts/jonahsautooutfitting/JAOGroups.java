package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import java.util.ArrayList;
import java.util.List;

/**
 * Weapon groups after the game's own autoGenerateWeaponGroups (0.5.12, research builds_update_1106 2.3 and
 * tournaments_1106 rule 3). The game already gives each distinct weapon its own group (up to 7), so only two things
 * change:
 *  - broadside hulls (side mounts carry 40%+ of the guns, like the Conquest): a group holding the same weapon on both
 *    sides is split into a left and a right group while there is room under 7, so the AI fires the side that bears;
 *  - missile boats (missiles 30%+ of weapon OP): a group of two or more conserved missile launchers (not deep-magazine
 *    rockets) is LINKED, so they leave as one volley the target's point defense can't pick apart.
 */
final class JAOGroups {
    private JAOGroups() { }

    static final int MAX_GROUPS = 7;

    static void arrange(ShipVariantAPI v) {
        try {
            splitBroadsides(v);
            linkMissileVolleys(v);
        } catch (Throwable t) {
            Global.getLogger(JAOGroups.class).warn("Auto Outfitting: couldn't arrange weapon groups on " + v.getHullVariantId(), t);
        }
    }

    /** -1 left, 1 right, 0 front or rear (more than 30 degrees off the bow and stern counts as a side). */
    static int side(WeaponSlotAPI s) {
        float a = s.getAngle();
        while (a > 180f) a -= 360f;
        while (a < -180f) a += 360f;
        if (a >= 30f && a <= 150f) return -1;
        if (a <= -30f && a >= -150f) return 1;
        return 0;
    }

    static float weight(WeaponSize s) { return s == WeaponSize.LARGE ? 4f : s == WeaponSize.MEDIUM ? 2f : 1f; }

    static void splitBroadsides(ShipVariantAPI v) {
        float all = 0f, sides = 0f;
        for (String id : v.getFittedWeaponSlots()) {
            WeaponSlotAPI s = v.getSlot(id);
            WeaponSpecAPI w = v.getWeaponSpec(id);
            if (s == null || w == null || s.isBuiltIn() || w.getType() == WeaponType.MISSILE) continue;
            float wt = weight(w.getSize());
            all += wt;
            if (side(s) != 0) sides += wt;
        }
        if (all <= 0f || sides / all < 0.4f) return;
        List<WeaponGroupSpec> groups = v.getWeaponGroups();
        int split = 0;
        for (int g = 0; g < groups.size() && groups.size() < MAX_GROUPS; g++) {
            WeaponGroupSpec group = groups.get(g);
            List<String> right = new ArrayList<>();
            boolean left = false;
            for (String id : group.getSlots()) {
                WeaponSlotAPI s = v.getSlot(id);
                if (s == null) continue;
                int sd = side(s);
                if (sd < 0) left = true;
                else if (sd > 0) right.add(id);
            }
            if (!left || right.isEmpty()) continue;
            WeaponGroupSpec r = new WeaponGroupSpec(group.getType());
            r.setAutofireOnByDefault(group.isAutofireOnByDefault());
            for (String id : right) { group.removeSlot(id); r.addSlot(id); }
            groups.add(g + 1, r);
            g++;
            split++;
        }
        if (split > 0) Global.getLogger(JAOGroups.class).info("Auto Outfitting: " + v.getHullVariantId() + ": " + split + " broadside group(s) split left/right");
    }

    static void linkMissileVolleys(ShipVariantAPI v) {
        float all = 0f, missiles = 0f;
        for (String id : v.getFittedWeaponSlots()) {
            WeaponSpecAPI w = v.getWeaponSpec(id);
            WeaponSlotAPI s = v.getSlot(id);
            if (w == null || s == null || s.isBuiltIn()) continue;
            float op = w.getOrdnancePointCost(null);
            all += op;
            if (w.getType() == WeaponType.MISSILE) missiles += op;
        }
        if (all <= 0f || missiles / all < 0.3f) return;
        int linked = 0;
        for (WeaponGroupSpec group : v.getWeaponGroups()) {
            if (group.getType() == WeaponGroupType.LINKED || group.getSlots().size() < 2) continue;
            boolean ok = true;
            for (String id : group.getSlots()) {
                WeaponSpecAPI w = v.getWeaponSpec(id);
                // conserved launchers only: deep magazines (rockets, Swarmers, Pilums) already fire freely
                if (w == null || w.getType() != WeaponType.MISSILE || w.getMaxAmmo() <= 0 || w.getMaxAmmo() >= 20) { ok = false; break; }
            }
            if (!ok) continue;
            group.setType(WeaponGroupType.LINKED);
            linked++;
        }
        if (linked > 0) Global.getLogger(JAOGroups.class).info("Auto Outfitting: " + v.getHullVariantId() + ": " + linked + " missile group(s) linked for volleys");
    }
}
