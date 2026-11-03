package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * "Fleet analysis" (user 11/03: "analyze what a fleet composition currently has ... and recommend a build"): what the
 * fleet carries, from the same numbers the Optimal fit balances against (JAOFleetContext), and which ships to re-fit.
 *  - firepower by damage type and the armor-cracking share, against the targets (about 45% kinetic, 30% armor);
 *  - fighter bays by role against JAOModel.WING_MIX, and the most common wings;
 *  - ships short of point defense, with guns spread over two range bands, or with empty mounts;
 *  - one line per combat ship.
 * Read-only: nothing is changed; the recommendations say which ships an Optimal re-fit would move toward balance.
 */
final class JAOAnalysisPanel extends JAOOverlayPanel {
    static final float W = 900f, H = 600f;
    static final String[] ROLE = {"fighters", "bombers", "interceptors", "support"};
    final JAOFleetContext fleet;

    JAOAnalysisPanel(JAOFleetContext fleet, Runnable close, boolean overlay) { super(close, overlay); this.fleet = fleet; }

    static JAOFleetContext analyseNow() {
        try {
            return JAOFleetContext.of(null);
        } catch (Throwable t) {
            Global.getLogger(JAOAnalysisPanel.class).error("Jonah's Auto Outfitting: fleet analysis failed", t);
            return null;
        }
    }

    void build(CustomPanelAPI inner) {
        Color h = Misc.getHighlightColor(), g = Misc.getGrayColor(), bad = Misc.getNegativeHighlightColor(), good = Misc.getPositiveHighlightColor();
        TooltipMakerAPI head = inner.createUIElement(W - 20f, 210f, false);
        head.addSectionHeading("Fleet analysis", Alignment.MID, 0f);
        if (fleet == null || fleet.ships == 0) {
            head.addPara(fleet == null ? "Couldn't read the fleet (see starsector.log)." : "No combat ships in the fleet.", 6f, fleet == null ? bad : g);
            inner.addUIElement(head).inTL(10f, 10f);
            closeButton(inner);
            return;
        }
        int carriers = 0, bays = 0;
        for (JAOFleetContext.Ship s : fleet.perShip) if (s.bays > 0) { carriers++; bays += s.bays; }
        head.addPara("%s combat ships, %s of them carriers (%s fighter bays).", 6f, h, "" + fleet.ships, "" + carriers, "" + bays);
        float t = Math.max(1f, fleet.total);
        String kin = pct(fleet.dmg[JAOModel.KIN] / t), he = pct(fleet.dmg[JAOModel.HE] / t), en = pct(fleet.dmg[JAOModel.EN] / t), fr = pct(fleet.dmg[JAOModel.FRAG] / t);
        head.addPara("Firepower: kinetic %s, high explosive %s, energy %s, fragmentation %s. Armor-cracking (hit 300+): %s.", 4f, h, kin, he, en, fr, pct(fleet.armorShare()));
        boolean shieldShort = fleet.kineticShare() < JAOFleetContext.KINETIC_TARGET - 0.08f, armorShort = fleet.armorShare() < JAOFleetContext.ARMOR_TARGET - 0.08f;
        head.addPara("Anti-shield: %s. Anti-armor: %s. (Aim: about " + esc(pct(JAOFleetContext.KINETIC_TARGET)) + " kinetic and " + esc(pct(JAOFleetContext.ARMOR_TARGET))
                + " armor-cracking.)", 2f, new Color[]{shieldShort ? bad : good, armorShort ? bad : good},
                shieldShort ? "short" : "fine", armorShort ? "short" : "fine");
        if (bays > 0) {
            float filled = 0f;
            for (float b : fleet.wingBays) filled += b;
            StringBuilder mix = new StringBuilder();
            for (int r = 0; r < 4; r++) mix.append(r == 0 ? "" : ", ").append((int) fleet.wingBays[r]).append(' ').append(ROLE[r]);
            head.addPara("Fighter bays: " + esc(mix.toString()) + " (aim about 40%% fighters, 25%% bombers, 25%% interceptors, 10%% support). Most common: %s.", 4f, h, topWings(fleet.wingIds, filled));
        }
        inner.addUIElement(head).inTL(10f, 10f);

        TooltipMakerAPI list = inner.createUIElement(W - 20f, H - 290f, true);
        list.addSectionHeading("Recommendations", Alignment.MID, 0f);
        List<String> recs = recommendations(fleet, shieldShort, armorShort);
        if (recs.isEmpty()) list.addPara("The fleet is balanced; nothing to re-fit.", 4f, good);
        for (String r : recs) list.addPara(esc(BULLET + r), 4f);
        list.addSectionHeading("Ships", Alignment.MID, 10f);
        List<JAOFleetContext.Ship> ships = new ArrayList<>(fleet.perShip);
        ships.sort(Comparator.comparingInt((JAOFleetContext.Ship s) -> -s.member.getHullSpec().getHullSize().ordinal()).thenComparing(s -> name(s)));
        for (JAOFleetContext.Ship s : ships) {
            float st = Math.max(1f, s.total);
            String range = s.maxRange <= 0f ? "no guns" : Math.round(s.minRange) == Math.round(s.maxRange) ? "" + Math.round(s.maxRange) : Math.round(s.minRange) + "-" + Math.round(s.maxRange);
            String[] vals = s.emptyMounts > 0
                    ? new String[]{name(s), s.member.getVariant().getDisplayName(), range, pct(s.dmg[JAOModel.KIN] / st), pct(s.armorDps / st), pdShort(s) ? "short" : "ok", "" + s.emptyMounts}
                    : new String[]{name(s), s.member.getVariant().getDisplayName(), range, pct(s.dmg[JAOModel.KIN] / st), pct(s.armorDps / st), pdShort(s) ? "short" : "ok"};
            list.addPara("%s  %s   range %s   kinetic %s, armor %s   PD %s" + (s.bays > 0 ? "   bays " + esc(wingMix(s)) : "") + (s.emptyMounts > 0 ? "   %s empty" : ""), 2f,
                    new Color[]{h, g, h, h, h, pdShort(s) ? bad : h, bad}, vals);
        }
        inner.addUIElement(list).inTL(10f, 230f);
        closeButton(inner);
    }

    static final String BULLET = "- ";

    void closeButton(CustomPanelAPI inner) {
        TooltipMakerAPI buttons = inner.createUIElement(W - 20f, 40f, false);
        ButtonAPI b = buttons.addButton("Close", "close", 140f, 30f, 0f);
        b.getPosition().inTL(0f, 0f);
        inner.addUIElement(buttons).inTL(10f, H - 50f);
    }

    @Override
    public void buttonPressed(Object id) {
        if ("close".equals(id)) close.run();
    }

    /** What an Optimal re-fit of which ships would fix, most important first. */
    static List<String> recommendations(JAOFleetContext f, boolean shieldShort, boolean armorShort) {
        List<String> out = new ArrayList<>();
        if (shieldShort) out.add("Anti-shield is short (" + pct(f.kineticShare()) + " kinetic). Re-fit with Optimal: " + lowest(f, true)
                + ". With fleet balance on, they lean kinetic.");
        if (armorShort) out.add("Anti-armor is short (" + pct(f.armorShare()) + " armor-cracking). Re-fit with Optimal: " + lowest(f, false)
                + ". With fleet balance on, they lean anti-armor.");
        float filled = 0f;
        for (float b : f.wingBays) filled += b;
        if (filled >= 4f) {
            String worst = null;
            for (int r = 0; r < 4; r++) if (f.wingBays[r] / filled > JAOModel.WING_MIX[r] + 0.2f) worst = ROLE[r];
            int top = 0;
            String topId = null;
            for (Map.Entry<String, Integer> e : f.wingIds.entrySet()) if (e.getValue() > top) { top = e.getValue(); topId = e.getKey(); }
            if (worst != null || top >= 0.5f * filled) {
                List<String> carriers = new ArrayList<>();
                for (JAOFleetContext.Ship s : f.perShip) if (s.bays > 0) carriers.add(name(s));
                out.add("Fighters lean one way (" + (worst != null ? "too many " + worst : top + " of " + (int) filled + " bays are " + wingName(topId))
                        + "). Re-fit the carriers with Optimal to mix roles: " + join(carriers, 6) + ".");
            }
        }
        List<String> pd = new ArrayList<>(), mixed = new ArrayList<>(), empty = new ArrayList<>();
        for (JAOFleetContext.Ship s : f.perShip) {
            if (pdShort(s)) pd.add(name(s));
            if (s.maxRange - s.minRange > 400f && s.minRange < Float.MAX_VALUE) mixed.add(name(s) + " (" + Math.round(s.minRange) + "-" + Math.round(s.maxRange) + ")");
            if (s.emptyMounts > 0) empty.add(name(s) + " (" + s.emptyMounts + ")");
        }
        if (!pd.isEmpty()) out.add("Point defense below need: " + join(pd, 6) + ".");
        if (!mixed.isEmpty()) out.add("Guns spread over two range bands (the AI fights at the shortest, so long guns idle or the ship gets dragged in): " + join(mixed, 6) + ".");
        if (!empty.isEmpty()) out.add("Empty mounts: " + join(empty, 6) + ". Re-fit with Optimal, or the stock may be out of weapons that fit.");
        return out;
    }

    /** The three combat ships with the least kinetic (or armor-cracking) share of their own firepower, carriers last. */
    static String lowest(JAOFleetContext f, boolean kinetic) {
        List<JAOFleetContext.Ship> l = new ArrayList<>();
        for (JAOFleetContext.Ship s : f.perShip) if (s.total > 0f) l.add(s);
        l.sort(Comparator.comparingDouble((JAOFleetContext.Ship s) -> (kinetic ? s.dmg[JAOModel.KIN] : s.armorDps) / Math.max(1f, s.total)).thenComparing(s -> name(s)));
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
        for (int r = 0; r < 4; r++) if (s.wingBays[r] > 0f) b.append(b.length() == 0 ? "" : "/").append((int) s.wingBays[r]).append(ROLE[r].charAt(0));
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

    /** addPara treats its text as a format string (vanilla writes %% for a literal percent sign). */
    static String esc(String s) { return s.replace("%", "%%"); }
}
