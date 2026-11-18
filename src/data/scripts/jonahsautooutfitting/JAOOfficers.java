package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Officer placement for Optimize fleet (0.5.39, user 11/14: every officer goes to the ship where they do the most for
 * the fleet; the player and AI cores stay where they are; "Place officers" in the Optimize fleet panel, off by default).
 *  - Officers that move: active human officers, not the player, not an AI core, not unremovable (Misc.isUnremovable),
 *    not on a locked ship.
 *  - Ships that take one: warships in the plan that aren't the flagship, automated (AI cores only), mothballed or
 *    locked, and aren't commanded by the player, an AI core or an unremovable officer.
 *  - Each officer on each ship is worth the share the ship's build score rises with that officer (the ship as it is
 *    now, JAOFleetPlan.scoreWithCaptain) times the hull's deployment points (worth). 0.5.65, user 11/17: officers went
 *    to frigates over capitals. Each ship is scored against enemies its own size, so a capital's score is not much
 *    bigger than a frigate's, and Wolfpack Tactics adds damage and peak time on officered frigates; the raw score gain
 *    put a +20% frigate ahead of a +10% capital. The best total is found greedily, then improved by swapping and
 *    moving officers until nothing gains (assign). An officer stays where they are unless a move gains more than STAY
 *    of their value there.
 *  - The fleet plan then fits every ship under its new captain; Apply moves the officers before refitting.
 * Runs a few scores per frame (step), like the plan.
 */
final class JAOOfficers {
    /** A move must gain more than this share over staying put. */
    static final float STAY = 0.03f;

    final JAOFleetPlan fleet;
    final List<FleetMemberAPI> ships = new ArrayList<>();
    final List<PersonAPI> officers = new ArrayList<>();
    /** Where each officer is now: the member they command (any ship in the fleet), or null. */
    final List<FleetMemberAPI> from = new ArrayList<>();
    float[] base;
    /** Each ship's deployment points (dp). */
    float[] weight;
    float[][] value;
    private int next;
    boolean done;
    /** Ship index per officer after placement (-1: none). */
    int[] placed;
    /** Member id -> new captain (null: none), only where the captain changes. */
    final Map<String, PersonAPI> captains = new LinkedHashMap<>();
    /** "Name: Ship A -> Ship B" lines for the panel and log. */
    final List<String> moves = new ArrayList<>();

    JAOOfficers(JAOFleetPlan fleet) {
        this.fleet = fleet;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        Map<PersonAPI, FleetMemberAPI> commands = new IdentityHashMap<>();
        for (FleetMemberAPI m : pf.getFleetData().getMembersListCopy()) if (m.getCaptain() != null) commands.put(m.getCaptain(), m);
        for (JAOFleetPlan.ShipPlan s : fleet.ships) if (!s.civilian && takesOfficer(s.member, s.locked)) ships.add(s.member);
        for (OfficerDataAPI od : pf.getFleetData().getOfficersCopy()) {
            PersonAPI p = od.getPerson();
            FleetMemberAPI at = commands.get(p);
            if (!movable(p) || (at != null && JAOLocks.locked(at))) continue;
            officers.add(p);
            from.add(at);
        }
        base = new float[ships.size()];
        weight = new float[ships.size()];
        for (int s = 0; s < weight.length; s++) weight[s] = dp(ships.get(s));
        value = new float[officers.size()][ships.size()];
        done = ships.isEmpty() || officers.isEmpty();
        if (done) finish();
    }

    /** A ship an officer can be placed on (and taken off). */
    static boolean takesOfficer(FleetMemberAPI m, boolean locked) {
        if (m == null || locked || m.isFighterWing() || m.isMothballed() || m.isFlagship() || Misc.isAutomated(m)) return false;
        PersonAPI c = m.getCaptain();
        return c == null || c.isDefault() || movable(c);
    }

    /** An officer the placement may move. */
    static boolean movable(PersonAPI p) {
        if (p == null || p.isDefault() || p.isPlayer() || p.isAICore() || Misc.isUnremovable(p)) return false;
        return JAOShip.activeOfficer(p);
    }

    /**
     * An officer's value on a ship: the share the build score rises (with over without) times the ship's deployment
     * points. +10% on a 40 DP capital is 4, +20% on an 8 DP frigate 1.6. 0 when the ship has no score to raise.
     */
    static float worth(float with, float without, float dp) {
        if (!(without > 0f) || Float.isNaN(with) || Float.isInfinite(with) || Float.isInfinite(without)) return 0f;
        return (with - without) / without * Math.max(1f, dp);
    }

    /** The hull's deployment points (its base cost, the same with or without an officer), at least 1. */
    static float dp(FleetMemberAPI m) {
        float d = 0f;
        try { d = m.getHullSpec().getSuppliesToRecover(); } catch (Throwable ignored) { }
        if (!(d > 0f)) { try { d = m.getDeploymentPointsCost(); } catch (Throwable ignored) { } }
        return d > 0f ? d : 1f;
    }

    /** Scores pairs for about budgetMs; true when placement is done. */
    boolean step(long budgetMs) {
        if (done) return true;
        long end = System.nanoTime() + budgetMs * 1_000_000L;
        int n = ships.size(), cells = n * (officers.size() + 1);
        while (next < cells && System.nanoTime() < end) {
            int s = next % n, o = next / n - 1;
            try {
                if (o < 0) base[s] = fleet.scoreWithCaptain(ships.get(s), null);
                else value[o][s] = worth(fleet.scoreWithCaptain(ships.get(s), officers.get(o)), base[s], weight[s]);
            } catch (Throwable t) {
                Global.getLogger(JAOOfficers.class).warn("Auto Outfitting: officer placement couldn't score " + ships.get(s).getShipName(), t);
                if (o >= 0) value[o][s] = 0f;
            }
            next++;
        }
        if (next < cells) return false;
        finish();
        return true;
    }

    String progress() {
        int n = Math.max(1, ships.size()), cells = n * (officers.size() + 1);
        return "Placing officers (" + Math.min(next, cells) + " of " + cells + ")";
    }

    private void finish() {
        done = true;
        int[] now = new int[officers.size()];
        for (int o = 0; o < now.length; o++) now[o] = ships.indexOf(from.get(o));
        placed = assign(value, now, STAY);
        // the new captain of every ship placement touches: ships a placed officer leaves, then the placed ships; an
        // officer placed nowhere keeps their ship unless another officer takes it
        Map<FleetMemberAPI, PersonAPI> after = new IdentityHashMap<>();
        for (int o = 0; o < officers.size(); o++) {
            if (placed[o] >= 0 && from.get(o) != null && from.get(o) != ships.get(placed[o])) after.put(from.get(o), null);
        }
        for (int o = 0; o < officers.size(); o++) if (placed[o] >= 0) after.put(ships.get(placed[o]), officers.get(o));
        for (Map.Entry<FleetMemberAPI, PersonAPI> e : after.entrySet()) {
            FleetMemberAPI m = e.getKey();
            PersonAPI was = m.getCaptain() != null && !m.getCaptain().isDefault() ? m.getCaptain() : null;
            if (was != e.getValue()) captains.put(m.getId(), e.getValue());
        }
        for (int o = 0; o < officers.size(); o++) {
            PersonAPI p = officers.get(o);
            FleetMemberAPI was = from.get(o), to = null;
            for (Map.Entry<FleetMemberAPI, PersonAPI> e : after.entrySet()) if (e.getValue() == p) to = e.getKey();
            if (to == null && was != null && !after.containsKey(was)) to = was;
            if (to == was) continue;
            moves.add(name(p) + ": " + (was == null ? "no ship" : shipName(was)) + " -> " + (to == null ? "no ship" : shipName(to)));
        }
        fleet.captains.putAll(captains);
        if (!moves.isEmpty()) Global.getLogger(JAOOfficers.class).info("Auto Outfitting: officer placement: " + String.join("; ", moves));
    }

    /**
     * The officers' ships for the best total value: value[o][s] is officer o on ship s, now[o] where they are (-1: no
     * ship). Greedy best pair first, then swaps between two officers and moves to a free ship while they gain. Staying
     * put counts STAY more. Officers with no ship worth anything get -1.
     */
    static int[] assign(float[][] value, int[] now, float stay) {
        int officers = value.length, ships = officers == 0 ? 0 : value[0].length;
        float[][] v = new float[officers][ships];
        for (int o = 0; o < officers; o++) for (int s = 0; s < ships; s++) {
            float x = Math.max(0f, value[o][s]);
            v[o][s] = s == now[o] ? x * (1f + stay) : x;
        }
        int[] at = new int[officers];
        java.util.Arrays.fill(at, -1);
        boolean[] taken = new boolean[ships];
        for (int round = 0; round < officers; round++) {
            int bo = -1, bs = -1;
            float best = 0f;
            for (int o = 0; o < officers; o++) {
                if (at[o] >= 0) continue;
                for (int s = 0; s < ships; s++) if (!taken[s] && v[o][s] > best) { best = v[o][s]; bo = o; bs = s; }
            }
            if (bo < 0) break;
            at[bo] = bs;
            taken[bs] = true;
        }
        for (int iter = 0; iter < 200; iter++) {
            boolean better = false;
            for (int a = 0; a < officers; a++) {
                for (int b = a + 1; b < officers; b++) {
                    float cur = val(v, a, at[a]) + val(v, b, at[b]), swap = val(v, a, at[b]) + val(v, b, at[a]);
                    if (swap > cur * 1.0001f + 1e-6f) { int t = at[a]; at[a] = at[b]; at[b] = t; better = true; }
                }
                for (int s = 0; s < ships; s++) {
                    if (taken[s] || v[a][s] <= val(v, a, at[a]) * 1.0001f + 1e-6f) continue;
                    if (at[a] >= 0) taken[at[a]] = false;
                    at[a] = s;
                    taken[s] = true;
                    better = true;
                }
            }
            if (!better) break;
        }
        for (int o = 0; o < officers; o++) if (at[o] >= 0 && v[o][at[o]] <= 0f) at[o] = -1;
        return at;
    }

    private static float val(float[][] v, int o, int s) { return s < 0 ? 0f : v[o][s]; }

    /** Moves the officers (before the refit): first off the ships they leave, then onto their new ships. Returns moves made. */
    int apply() {
        if (captains.isEmpty()) return 0;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        Map<String, FleetMemberAPI> byId = new LinkedHashMap<>();
        for (FleetMemberAPI m : pf.getFleetData().getMembersListCopy()) byId.put(m.getId(), m);
        for (Map.Entry<String, PersonAPI> e : captains.entrySet()) {
            FleetMemberAPI m = byId.get(e.getKey());
            if (m != null) m.setCaptain(null);
        }
        for (Map.Entry<String, PersonAPI> e : captains.entrySet()) {
            FleetMemberAPI m = byId.get(e.getKey());
            if (m != null && e.getValue() != null) m.setCaptain(e.getValue());
        }
        try { pf.getFleetData().setSyncNeeded(); pf.getFleetData().syncIfNeeded(); } catch (Throwable ignored) { }
        Global.getLogger(JAOOfficers.class).info("Auto Outfitting: officers placed: " + String.join("; ", moves));
        return moves.size();
    }

    static String name(PersonAPI p) {
        try { return p.getNameString(); } catch (Throwable t) { return "Officer"; }
    }

    static String shipName(FleetMemberAPI m) {
        return m.getShipName() == null || m.getShipName().isEmpty() ? m.getHullSpec().getHullName() : m.getShipName();
    }
}
