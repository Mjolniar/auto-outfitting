package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Optimal entries in the refit screen's autofit goal row (4 slots per base hull; most hulls use 1-3 for stock goals).
 *
 * The public AutofitVariantsAPI only returns a copy, so entries go in through the engine's SavedVariantData
 * (com.fs.starfarer.coreui.refit.auto, DoNotObfuscate: stable names that Fast Rendering leaves alone), reached with
 * method handles (the script loader refuses java.lang.reflect) and feature-detected: if anything doesn't match, the
 * "Optimal fit for every goal" checkbox still works (research engine_report.md 1.5, 8).
 *
 * Entries are temporary: added when the autofit dialog asks for its plugin (just before it draws the goal row) and
 * removed once the core UI is closed, before every save and on load. Player-faction NPC fleets build half their
 * ships from goal variants (DefaultFleetInflater), so an entry is never empty: it carries the hull's first stock goal
 * loadout (or the ship's current one) plus the marker tag.
 */
public class JAOGoals {
    public static final String PREFIX = "Optimal", TAG = "jao_optimal_";
    /** Autofit checkbox used when all 4 slots are taken (or SavedVariantData isn't reachable). */
    public static final String OPTION = "jao_optimal_any_goal";
    static final String SVD = "com.fs.starfarer.coreui.refit.auto.SavedVariantData";

    private static Handles handles;
    private static boolean handlesFailed;
    private static final Set<String> injected = new HashSet<>();

    /**
     * An Autofit panel checkbox as the player last left it: the game keeps every box's state in SavedVariantData's option
     * map (it goes into the save). False when it was never ticked or can't be read. Optimize fleet builds s-mods only
     * while "Build in s-mods" is ticked (user 11/05: story points are too valuable for one button to spend them all).
     */
    static boolean optionChecked(String id) {
        try {
            Object svd = Global.getSector().getAutofitVariants();
            MethodHandle get = MethodHandles.lookup().findVirtual(svd.getClass(), "getOptionState", MethodType.methodType(Map.class));
            return Boolean.TRUE.equals(((Map<?, ?>) get.invoke(svd)).get(id));
        } catch (Throwable t) {
            if (!optionWarned) Global.getLogger(JAOGoals.class).warn("Jonah's Auto Outfitting: can't read the Autofit panel's \"" + id + "\" box; treated as unticked", t);
            optionWarned = true;
            return false;
        }
    }

    private static boolean optionWarned;

    /** Method handles to SavedVariantData and its inner classes, looked up once. */
    private static final class Handles {
        MethodHandle getVisible, arrayVariants, getRecent, setVariants, getVariantMap, resolverVariant, addToVisible;
        Class<?> variantClass;
    }

    private static Handles handles(Object svd, ShipVariantAPI sample) {
        if (handles != null && (sample == null || handles.variantClass == sample.getClass())) return handles;
        if (handlesFailed && sample == null) return null;
        try {
            Class<?> c = svd.getClass();
            if (!SVD.equals(c.getName())) throw new IllegalStateException("autofit variants are " + c.getName());
            ClassLoader cl = c.getClassLoader();
            Class<?> array = Class.forName(SVD + "$VariantArray", false, cl);
            Class<?> set = Class.forName(SVD + "$VariantSet", false, cl);
            Class<?> resolver = Class.forName(SVD + "$VariantResolver", false, cl);
            MethodHandles.Lookup l = MethodHandles.lookup();
            Handles h = new Handles();
            h.getVisible = l.findVirtual(c, "getVisible", MethodType.methodType(array, String.class));
            h.arrayVariants = l.findVirtual(array, "getVariants", MethodType.methodType(List.class));
            h.getRecent = l.findVirtual(c, "getRecent", MethodType.methodType(set, String.class));
            h.setVariants = l.findVirtual(set, "getVariants", MethodType.methodType(List.class));
            h.getVariantMap = l.findVirtual(c, "getVariantMap", MethodType.methodType(Map.class));
            Class<?> spec = sample != null ? sample.getClass() : null;
            h.resolverVariant = null;
            for (Class<?> k = spec; k != null && h.resolverVariant == null; k = k.getSuperclass()) {
                try { h.resolverVariant = l.findVirtual(resolver, "getVariant", MethodType.methodType(k)); } catch (NoSuchMethodException ignored) { }
            }
            if (spec != null) {
                h.addToVisible = l.findVirtual(c, "addToVisible", MethodType.methodType(void.class, int.class, spec));
                h.variantClass = spec;
            }
            handles = h;
            handlesFailed = false;
            return h;
        } catch (Throwable t) {
            if (!handlesFailed) Global.getLogger(JAOGoals.class).warn("Jonah's Auto Outfitting: can't reach the autofit goal list ("
                    + t + "); use the \"Optimal fit for every goal\" box instead");
            handlesFailed = true;
            return null;
        }
    }

    /** Adds the Optimal entry (and one per style when that setting is on) to free slots of member's base hull. */
    static void inject(FleetMemberAPI member) {
        try {
            if (member == null || member.getVariant() == null || Global.getSector() == null) return;
            Object svd = Global.getSector().getAutofitVariants();
            Handles h = handles(svd, member.getVariant());
            if (h == null || h.addToVisible == null || h.resolverVariant == null) return;
            removeAll();
            String base = baseHullId(member.getHullSpec());
            @SuppressWarnings("unchecked")
            List<Object> row = (List<Object>) h.arrayVariants.invoke(h.getVisible.invoke(svd, base));
            ShipVariantAPI template = null;
            for (Object r : row) {
                ShipVariantAPI v = r == null ? null : (ShipVariantAPI) h.resolverVariant.invoke(r);
                if (v != null && styleOf(v) == null) { template = v; break; }
            }
            if (template == null) template = member.getVariant();
            for (JAOStyle style : styles()) {
                int free = row.indexOf(null);
                if (free < 0) break;
                ShipVariantAPI v = template.clone();
                v.setVariantDisplayName(style == JAOStyle.AUTO || style == JAOSettings.style && !JAOSettings.allStyles ? PREFIX : PREFIX + " (" + style.label + ")");
                v.addTag(TAG + style.name());
                h.addToVisible.invoke(svd, free, v);
                injected.add(base);
                JAOCleanup.injected = true;
            }
        } catch (Throwable t) {
            Global.getLogger(JAOGoals.class).warn("Jonah's Auto Outfitting: couldn't add the Optimal goal", t);
        }
    }

    /** The setting's style first, then (with "One goal per style") the others. */
    static List<JAOStyle> styles() {
        List<JAOStyle> out = new ArrayList<>();
        out.add(JAOSettings.style);
        if (JAOSettings.allStyles) {
            for (JAOStyle s : JAOStyle.values()) if (s != JAOStyle.AUTO && s != JAOSettings.style) out.add(s);
        }
        return out;
    }

    /** Takes every tagged entry back out: visible slots, recent designs and the saved variant map. */
    static void removeAll() {
        try {
            if (Global.getSector() == null) return;
            Object svd = Global.getSector().getAutofitVariants();
            Handles h = handles != null ? handles : handles(svd, null);
            if (h == null) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) h.getVariantMap.invoke(svd);
            Set<String> bases = new HashSet<>(injected);
            for (Object v : new ArrayList<>(map.values())) {
                if (v instanceof ShipVariantAPI && styleOf((ShipVariantAPI) v) != null) bases.add(baseHullId(((ShipVariantAPI) v).getHullSpec()));
            }
            for (String base : bases) {
                @SuppressWarnings("unchecked")
                List<Object> row = (List<Object>) h.arrayVariants.invoke(h.getVisible.invoke(svd, base));
                for (int i = 0; i < row.size(); i++) if (ours(h, row.get(i))) row.set(i, null);
                @SuppressWarnings("unchecked")
                List<Object> recent = (List<Object>) h.setVariants.invoke(h.getRecent.invoke(svd, base));
                for (int i = recent.size() - 1; i >= 0; i--) if (ours(h, recent.get(i))) recent.remove(i);
            }
            map.values().removeIf(v -> v instanceof ShipVariantAPI && styleOf((ShipVariantAPI) v) != null);
            injected.clear();
        } catch (Throwable t) {
            Global.getLogger(JAOGoals.class).warn("Jonah's Auto Outfitting: couldn't remove the Optimal goals", t);
        }
    }

    private static boolean ours(Handles h, Object resolver) throws Throwable {
        if (resolver == null || h.resolverVariant == null) return false;
        Object v = h.resolverVariant.invoke(resolver);
        return v instanceof ShipVariantAPI && styleOf((ShipVariantAPI) v) != null;
    }

    /** After a settings change: entries are rebuilt on the next dialog open, so only stale ones need to go. */
    static void refresh() {
        removeAll();
    }

    /** Same rule as SavedVariantData.getBaseHullId: D-hulls to their parent, compatible skins to their base. */
    static String baseHullId(ShipHullSpecAPI h) {
        for (int i = 0; i < 8 && h != null; i++) {
            if (h.isDefaultDHull() && h.getDParentHull() != null) h = h.getDParentHull();
            if (!h.isBaseHull() && h.isCompatibleWithBase()) {
                ShipHullSpecAPI b = Global.getSettings().getHullSpec(h.getBaseHullId());
                if (b == null || b == h) return h.getHullId();
                h = b;
            } else {
                return h.getHullId();
            }
        }
        return h == null ? "" : h.getHullId();
    }

    /** The style an Optimal goal stands for, or null for an ordinary goal (handled by the game's autofit). */
    static JAOStyle styleOf(ShipVariantAPI target) {
        if (target == null) return null;
        for (String tag : target.getTags()) {
            if (tag.startsWith(TAG)) return JAOStyle.named(tag.substring(TAG.length()));
        }
        return null;
    }

    /** First non-Optimal goal for the hull: the fallback when the optimizer throws. */
    static ShipVariantAPI stockGoal(String hullId) {
        List<ShipVariantAPI> goals = Global.getSector().getAutofitVariants().getTargetVariants(hullId);
        if (goals == null) return null;
        for (ShipVariantAPI v : goals) if (styleOf(v) == null) return v;
        return null;
    }
}
