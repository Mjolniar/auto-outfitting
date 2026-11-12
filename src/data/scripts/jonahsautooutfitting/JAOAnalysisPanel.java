package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * "Fleet analysis" (user 11/03: "analyze what a fleet composition currently has ... and recommend a build"): what the
 * fleet carries, from the same numbers the fleet plan balances (JAOFleetMix), and what to do about it.
 *  - anti-shield (how fast the fleet fills enemy flux bars) and armor-cracking against their aims, the damage types;
 *  - fighter bays by role against JAOModel.WING_MIX, and the most common wings;
 *  - ships short of point defense, with guns spread over two range bands, or with empty mounts;
 *  - one line per combat ship.
 * Read-only; its Optimize fleet button opens the fleet plan (JAOFleetPanel), which is what balances the fleet.
 */
final class JAOAnalysisPanel extends JAOOverlayPanel {
    static final float W = 900f, H = 600f;
    static final String[] ROLE = {"fighters", "bombers", "interceptors", "support"};
    final JAOFleetContext fleet;
    /** Opens Optimize fleet (null: no button). */
    final Runnable optimize;

    JAOAnalysisPanel(JAOFleetContext fleet, Runnable close, Runnable optimize, boolean overlay) { super(close, overlay); this.fleet = fleet; this.optimize = optimize; }

    static JAOFleetContext analyseNow() {
        try {
            return JAOFleetContext.of(null);
        } catch (Throwable t) {
            Global.getLogger(JAOAnalysisPanel.class).error("Auto Outfitting: fleet analysis failed", t);
            return null;
        }
    }

    @Override String title() { return "Fleet analysis"; }
    @Override float width() { return W; }
    @Override float height() { return H; }

    @Override
    void buildResult() {
        Color h = Misc.getHighlightColor(), g = Misc.getGrayColor(), bad = Misc.getNegativeHighlightColor(), good = Misc.getPositiveHighlightColor();
        TooltipMakerAPI head = element(W - 20f, 210f, false, 10f, 10f);
        head.addSectionHeading(title(), Alignment.MID, 0f);
        if (fleet == null || fleet.ships == 0) {
            head.addPara(fleet == null ? "Couldn't read the fleet (see starsector.log)." : "No combat ships in the fleet.", 6f, fleet == null ? bad : g);
            buttons();
            return;
        }
        JAOFleetMix mix = fleet.mix;
        int carriers = 0, bays = 0;
        for (JAOFleetContext.Ship s : fleet.perShip) if (s.bays > 0) { carriers++; bays += s.bays; }
        head.addPara("%s combat ships, %s of them carriers (%s fighter bays).", 6f, h, "" + fleet.ships, "" + carriers, "" + bays);
        float t = Math.max(1f, mix.total);
        head.addPara("Firepower: kinetic %s, high explosive %s, energy %s, fragmentation %s.", 4f, h,
                pct(mix.dmg[JAOModel.KIN] / t), pct(mix.dmg[JAOModel.HE] / t), pct(mix.dmg[JAOModel.EN] / t), pct(mix.dmg[JAOModel.FRAG] / t));
        boolean shieldShort = shieldShort(mix), armorShort = armorShort(mix);
        head.addPara("Anti-shield (how fast it fills enemy flux bars): %s, aim %s. Armor-cracking (hits of 300+ after armor): %s, aim %s.", 2f,
                new Color[]{shieldShort ? bad : good, h, armorShort ? bad : good, h},
                pct(mix.shieldShare()), pct(JAOFleetMix.SHIELD_TARGET), pct(mix.armorShare()), pct(JAOFleetMix.ARMOR_TARGET));
        if (bays > 0) {
            float filled = 0f;
            for (float b : mix.wingBays) filled += b;
            StringBuilder m = new StringBuilder();
            for (int r = 0; r < 4; r++) m.append(r == 0 ? "" : ", ").append((int) mix.wingBays[r]).append(' ').append(ROLE[r]);
            head.addPara("Fighter bays: " + esc(m.toString()) + " (aim about " + esc(aim()) + "). Most common: %s.", 4f, h, topWings(mix.wingIds, filled));
        }

        TooltipMakerAPI list = element(W - 20f, H - 290f, true, 10f, 230f);
        list.addSectionHeading("Recommendations", Alignment.MID, 0f);
        List<String> recs = recommendations(fleet, shieldShort, armorShort);
        if (recs.isEmpty()) list.addPara("The fleet is balanced; nothing to re-fit.", 4f, good);
        for (String r : recs) list.addPara(esc(BULLET + r), 4f);
        list.addSectionHeading("Ships", Alignment.MID, 10f);
        List<JAOFleetContext.Ship> ships = new ArrayList<>(fleet.perShip);
        ships.sort(Comparator.comparingInt((JAOFleetContext.Ship s) -> -s.member.getHullSpec().getHullSize().ordinal()).thenComparing(s -> name(s)));
        for (JAOFleetContext.Ship s : ships) {
            String range = s.maxRange <= 0f ? "no guns" : Math.round(s.minRange) == Math.round(s.maxRange) ? "" + Math.round(s.maxRange) : Math.round(s.minRange) + "-" + Math.round(s.maxRange);
            String[] vals = s.emptyMounts > 0
                    ? new String[]{name(s), s.member.getVariant().getDisplayName(), range, pct(s.own.shieldShare()), pct(s.own.armorShare()), pdShort(s) ? "short" : "ok", "" + s.emptyMounts}
                    : new String[]{name(s), s.member.getVariant().getDisplayName(), range, pct(s.own.shieldShare()), pct(s.own.armorShare()), pdShort(s) ? "short" : "ok"};
            list.addPara("%s  %s   range %s   anti-shield %s, armor %s   PD %s" + (s.bays > 0 ? "   bays " + esc(wingMix(s)) : "") + (s.emptyMounts > 0 ? "   %s empty" : ""), 2f,
                    new Color[]{h, g, h, h, h, pdShort(s) ? bad : h, bad}, vals);
        }
        buttons();
    }

    static final String BULLET = "- ";

    static boolean shieldShort(JAOFleetMix m) { return m.shieldShare() < JAOFleetMix.SHIELD_TARGET - 0.06f; }
    static boolean armorShort(JAOFleetMix m) { return m.armorShare() < JAOFleetMix.ARMOR_TARGET - 0.06f; }

    static String aim() {
        StringBuilder b = new StringBuilder();
        for (int r = 0; r < 4; r++) b.append(r == 0 ? "" : ", ").append(Math.round(100f * JAOModel.WING_MIX[r])).append("% ").append(ROLE[r]);
        return b.toString();
    }

    void buttons() {
        TooltipMakerAPI buttons = element(W - 20f, 40f, false, 10f, H - 50f);
        ButtonAPI c = buttons.addButton("Close", "close", 140f, 30f, 0f);
        c.getPosition().inTL(0f, 0f);
        if (optimize != null) {
            ButtonAPI o = buttons.addButton("Optimize fleet...", "optimize", 200f, 30f, 0f);
            o.getPosition().rightOfTop(c, 10f);
        }
    }

    @Override
    public void buttonPressed(Object id) {
        if ("close".equals(id)) close.run();
        else if ("optimize".equals(id) && optimize != null) optimize.run();
    }

    /** What to do, most important first. */
    static List<String> recommendations(JAOFleetContext f, boolean shieldShort, boolean armorShort) {
        List<String> out = new ArrayList<>();
        JAOFleetMix mix = f.mix;
        if (shieldShort || armorShort) out.add((shieldShort ? "Anti-shield is short (" + pct(mix.shieldShare()) + ")" : "Anti-armor is short (" + pct(mix.armorShare()) + " armor-cracking)")
                + ". Optimize fleet plans every ship together and leans each toward what the rest lacks; ships doing least of it now: " + lowest(f, shieldShort) + ".");
        float filled = 0f;
        for (float b : mix.wingBays) filled += b;
        if (filled >= 4f) {
            String worst = null;
            for (int r = 0; r < 4; r++) if (mix.wingBays[r] / filled > JAOModel.WING_MIX[r] + 0.2f) worst = ROLE[r];
            int top = 0;
            String topId = null;
            for (Map.Entry<String, Integer> e : mix.wingIds.entrySet()) if (e.getValue() > top) { top = e.getValue(); topId = e.getKey(); }
            if (worst != null || top >= 0.5f * filled) {
                List<String> carriers = new ArrayList<>();
                for (JAOFleetContext.Ship s : f.perShip) if (s.bays > 0) carriers.add(name(s));
                out.add("Fighters lean one way (" + (worst != null ? "too many " + worst : top + " of " + (int) filled + " bays are " + wingName(topId))
                        + "). Optimize fleet mixes roles across every bay: " + join(carriers, 6) + ".");
            }
        }
        List<String> pd = new ArrayList<>(), mixed = new ArrayList<>(), empty = new ArrayList<>(), noEscort = new ArrayList<>();
        for (JAOFleetContext.Ship s : f.perShip) {
            if (pdShort(s)) pd.add(name(s));
            if (s.own.wingBays[JAOModel.W_INTERCEPTOR] > 0f && s.own.wingBays[JAOModel.W_BOMBER] > 0f) noEscort.add(name(s));
            if (s.maxRange - s.minRange > 400f && s.minRange < Float.MAX_VALUE) mixed.add(name(s) + " (" + Math.round(s.minRange) + "-" + Math.round(s.maxRange) + ")");
            if (s.emptyMounts > 0) empty.add(name(s) + " (" + s.emptyMounts + ")");
        }
        if (!noEscort.isEmpty()) out.add("Interceptors next to bombers (a carrier with any bomber never sends fighters to escort, so the interceptors don't screen it): "
                + join(noEscort, 6) + ".");
        if (!pd.isEmpty()) out.add("Point defense below need: " + join(pd, 6) + ".");
        if (!mixed.isEmpty()) out.add("Guns spread over two range bands (the AI fights at the shortest, so long guns idle or the ship gets dragged in): " + join(mixed, 6) + ".");
        if (!empty.isEmpty()) out.add("Empty mounts: " + join(empty, 6) + ". Re-fit with Optimal, or the stock may be out of weapons that fit.");
        return out;
    }

    /** The three combat ships with the least anti-shield (or armor-cracking) share of their own firepower. */
    static String lowest(JAOFleetContext f, boolean shield) {
        List<JAOFleetContext.Ship> l = new ArrayList<>();
        for (JAOFleetContext.Ship s : f.perShip) if (s.own.total > 0f) l.add(s);
        l.sort(Comparator.comparingDouble((JAOFleetContext.Ship s) -> shield ? s.own.shieldShare() : s.own.armorShare()).thenComparing(s -> name(s)));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(3, l.size()); i++) names.add(name(l.get(i)));
        return names.isEmpty() ? "any ship" : String.join(", ", names);
    }

    static boolean pdShort(JAOFleetContext.Ship s) {
        int size = Math.max(0, JAORatings.sizeIndex(s.member.getHullSpec().getHullSize()));
        float need = JAOModel.PD_NEED[Math.min(3, size)] * JAOSettings.pdFloor;
        // rough PD units from PD weapons' damage (the fit's model also weighs reach and arc)
        return size >= 1 && s.pd * 0.4f < 0.5f * need && s.bays == 0;
    }

    static String wingMix(JAOFleetContext.Ship s) {
        StringBuilder b = new StringBuilder();
        for (int r = 0; r < 4; r++) if (s.own.wingBays[r] > 0f) b.append(b.length() == 0 ? "" : "/").append((int) s.own.wingBays[r]).append(ROLE[r].charAt(0));
        return b.length() == 0 ? "empty" : b.toString();
    }

    static String topWings(Map<String, Integer> ids, float filled) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(ids.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(3, l.size()); i++) out.add(wingName(l.get(i).getKey()) + " x" + l.get(i).getValue());
        return out.isEmpty() ? "none" : String.join(", ", out);
    }

    static String wingName(String id) {
        try {
            FighterWingSpecAPI spec = Global.getSettings().getFighterWingSpec(id);
            return spec.getVariant().getHullSpec().getHullName();
        } catch (Throwable t) {
            return id;
        }
    }

    static String name(JAOFleetContext.Ship s) {
        String n = s.member.getShipName();
        return n == null || n.isEmpty() ? s.member.getHullSpec().getHullName() : n;
    }

    static String join(List<String> l, int max) {
        if (l.size() <= max) return String.join(", ", l);
        return String.join(", ", l.subList(0, max)) + " and " + (l.size() - max) + " more";
    }

    static String pct(float f) { return Math.round(100f * f) + "%"; }
}
