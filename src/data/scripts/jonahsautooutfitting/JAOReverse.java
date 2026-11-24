package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Reverse Engineered Private Arsenal (re_private_arsenal 1.5.0), 0.5.81 (user 11/23: "strip off uniques that haven't
 * been put through reverse engineering storage and put them in there"). Read from the mod's own code:
 *  - The Reverse Engineering Hub (RePA_ReverseEngPhase1Ind / 2 / 3, player colonies) adds the Reverse Engineering
 *    Storage (RePA_ReversEngSub, free transfer). Tier 1 takes weapons, tier 2 and 3 weapons and fighter wings (ships
 *    only at an improved tier 3; not handled here).
 *  - Each batch takes one of each picked item out of that storage; when its days are up the id goes into the sector
 *    memory set $RePA_ProducedWeapons / $RePA_ProducedFighters, and the Private Arsenal stocks 50 of every id there.
 *  - Items in the storage whose id is already done are never picked again.
 * Optimize fleet docked at a hub sets one of every weapon and wing the hub hasn't done aside before it plans (spares in
 * cargo and storage first, a ship's only copy when there's no spare), and Apply puts them in the storage. Items sent are
 * kept in a per-save record, since the batch being worked on isn't in the storage any more.
 */
final class JAOReverse {
    static final String MOD = "re_private_arsenal", STORAGE = "RePA_ReversEngSub";
    static final String HUB1 = "RePA_ReverseEngPhase1Ind", HUB2 = "RePA_ReverseEngPhase2Ind", HUB3 = "RePA_ReverseEngPhase3Ind";
    static final String DONE_WEAPONS = "$RePA_ProducedWeapons", DONE_WINGS = "$RePA_ProducedFighters";
    /** Per save: "w:id" / "f:id" -> sector clock timestamp when Optimize fleet put one in the storage. */
    static final String SENT = "jao_repaSent";
    /** RePA's research days with no AI core (its LunaLib default); an item sent counts as being worked on for that plus this many days. */
    static final int DEFAULT_DAYS = 30, GRACE_DAYS = 30;

    private JAOReverse() { }

    static boolean present() {
        try { return Global.getSettings().getModManager().isModEnabled(MOD); } catch (Throwable t) { return false; }
    }

    /** The hub's tier at this market (0: none). */
    static int tier(MarketAPI m) {
        if (m == null) return 0;
        if (m.hasIndustry(HUB3)) return 3;
        if (m.hasIndustry(HUB2)) return 2;
        return m.hasIndustry(HUB1) ? 1 : 0;
    }

    /** The Reverse Engineering Storage at this market, or null (no hub, or not your colony). */
    static SubmarketAPI storage(MarketAPI m) {
        try {
            if (m == null || !m.isPlayerOwned() || tier(m) <= 0 || !m.hasSubmarket(STORAGE)) return null;
            SubmarketAPI sub = m.getSubmarket(STORAGE);
            return sub != null && sub.getCargo() != null ? sub : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ids the hub has finished (RePA's sector memory set). */
    static Set<String> done(boolean wing) {
        Set<String> out = new HashSet<>();
        try {
            Object o = Global.getSector().getMemoryWithoutUpdate().get(wing ? DONE_WINGS : DONE_WEAPONS);
            if (o instanceof Collection) for (Object x : (Collection<?>) o) if (x != null) out.add(x.toString());
        } catch (Throwable ignored) { }
        return out;
    }

    /** Ids in the Reverse Engineering Storage now. */
    static Set<String> held(CargoAPI re, boolean wing) {
        Set<String> out = new HashSet<>();
        if (re == null) return out;
        for (CargoAPI.CargoItemQuantity<String> q : wing ? re.getFighters() : re.getWeapons()) if (q.getCount() > 0) out.add(q.getItem());
        return out;
    }

    /** RePA's research days with no AI core (repa_re_days_no_core), from LunaLib when it's there. */
    static int days() {
        try {
            if (Global.getSettings().getModManager().isModEnabled(JAOSettings.LUNA)) {
                Integer v = JAOLuna.otherInt(MOD, "repa_re_days_no_core");
                if (v != null && v > 0) return v;
            }
        } catch (Throwable ignored) { }
        return DEFAULT_DAYS;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sentRecord() {
        try {
            Map<String, Object> data = Global.getSector().getPersistentData();
            Object o = data.get(SENT);
            if (o instanceof Map) return (Map<String, Object>) o;
            Map<String, Object> m = new HashMap<>();
            data.put(SENT, m);
            return m;
        } catch (Throwable t) {
            return new HashMap<>();
        }
    }

    /** Items sent less than days() + GRACE_DAYS ago that the hub hasn't finished: the batch it's working on. */
    static Set<String> pending(boolean wing, Set<String> done) {
        Set<String> out = new HashSet<>();
        try {
            int limit = days() + GRACE_DAYS;
            String prefix = wing ? "f:" : "w:";
            for (Map.Entry<String, Object> e : sentRecord().entrySet()) {
                if (!e.getKey().startsWith(prefix) || !(e.getValue() instanceof Number)) continue;
                String id = e.getKey().substring(2);
                if (done.contains(id)) continue;
                if (Global.getSector().getClock().getElapsedDaysSince(((Number) e.getValue()).longValue()) < limit) out.add(id);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** What the fleet could give up: the stock minus what stays on locked ships (and the refit screen's stock-variant ship). */
    static Map<String, Integer> movable(Map<String, Integer> stock, Map<String, Integer> kept) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : stock.entrySet()) {
            int n = e.getValue() - kept.getOrDefault(e.getKey(), 0);
            if (n > 0) out.put(e.getKey(), n);
        }
        return out;
    }

    /** The ids to send, sorted: one each of what the fleet can give up, not done, not in the storage, not being worked on. */
    static List<String> pick(Map<String, Integer> movable, Set<String> done, Set<String> held, Set<String> pending, Predicate<String> eligible) {
        List<String> out = new ArrayList<>();
        for (String id : new TreeSet<>(movable.keySet())) {
            if (movable.get(id) <= 0 || done.contains(id) || held.contains(id) || pending.contains(id)) continue;
            if (eligible != null && !eligible.test(id)) continue;
            out.add(id);
        }
        return out;
    }

    /** Weapons a ship can mount and the storage takes (no built-in, system or decorative ones, no console-only gear). */
    static boolean weaponOk(String id) {
        WeaponSpecAPI s = JAOFleetPlan.weaponSpec(id);
        if (s == null || s.getAIHints().contains(AIHints.SYSTEM)) return false;
        if (s.getType() == WeaponType.DECORATIVE || s.getType() == WeaponType.BUILT_IN || s.getType() == WeaponType.SYSTEM) return false;
        return JAOSettings.cheatItems || !JAOCompat.cheatOnly(s);
    }

    static boolean wingOk(String id) {
        FighterWingSpecAPI s = JAOFleetPlan.wingSpec(id);
        if (s == null || JAOCompat.boundWing(id)) return false;
        return JAOSettings.cheatItems || !JAOCompat.cheatOnly(s);
    }

    /** Moves one into the storage, from storage here first, then fleet cargo. False when neither has one. */
    static boolean send(String id, boolean wing, CargoAPI storage, CargoAPI cargo, CargoAPI re) {
        CargoAPI from = null;
        if (storage != null && (wing ? storage.getNumFighters(id) : storage.getNumWeapons(id)) > 0) from = storage;
        else if (cargo != null && (wing ? cargo.getNumFighters(id) : cargo.getNumWeapons(id)) > 0) from = cargo;
        if (from == null) return false;
        if (wing) { from.removeFighters(id, 1); re.addFighters(id, 1); }
        else { from.removeWeapons(id, 1); re.addWeapons(id, 1); }
        try { sentRecord().put((wing ? "f:" : "w:") + id, Global.getSector().getClock().getTimestamp()); } catch (Throwable ignored) { }
        return true;
    }

    /** Items in the storage the hub has already done (never picked again) go back to storage here, or cargo. Returns how many. */
    static int takeBack(CargoAPI re, CargoAPI to) {
        if (re == null || to == null) return 0;
        int n = 0;
        Set<String> dw = done(false), df = done(true);
        for (CargoAPI.CargoItemQuantity<String> q : new ArrayList<>(re.getWeapons())) {
            if (q.getCount() <= 0 || !dw.contains(q.getItem())) continue;
            re.removeWeapons(q.getItem(), q.getCount());
            to.addWeapons(q.getItem(), q.getCount());
            n += q.getCount();
        }
        for (CargoAPI.CargoItemQuantity<String> q : new ArrayList<>(re.getFighters())) {
            if (q.getCount() <= 0 || !df.contains(q.getItem())) continue;
            re.removeFighters(q.getItem(), q.getCount());
            to.addFighters(q.getItem(), q.getCount());
            n += q.getCount();
        }
        return n;
    }

    /** Display names, at most cap of them, then "and N more". */
    static String names(List<String> weapons, List<String> wings, int cap) {
        List<String> all = new ArrayList<>();
        for (String id : weapons) {
            WeaponSpecAPI s = JAOFleetPlan.weaponSpec(id);
            all.add(s == null ? id : s.getWeaponName());
        }
        for (String id : wings) {
            FighterWingSpecAPI s = JAOFleetPlan.wingSpec(id);
            all.add(s == null ? id : JAOFleetPlanner.wingName(s));
        }
        if (all.size() <= cap) return String.join(", ", all);
        return String.join(", ", all.subList(0, cap)) + " and " + (all.size() - cap) + " more";
    }
}
