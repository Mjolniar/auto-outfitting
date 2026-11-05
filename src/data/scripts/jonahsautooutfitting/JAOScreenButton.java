package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Order weapons for fleet" button on the refit screen itself. LunaLib only lists mod refit buttons behind its small
 * icon by the OP readout, and the game has no API for buttons there, so the screen is read the way LunaLib does: the
 * campaign UI's getCore() (an open dialog's getCoreUI() when docked), its getCurrentTab(), and the panel in it with a
 * public syncWithCurrentVariant() (the refit panel) and getOpDisplay() (the OP readout), all through JAORefl.
 *
 * Nothing about the layout is assumed: every visible element's rectangle is read and the button goes in the first spot
 * on the screen that overlaps none of them: left of the Autofit button, above it, under it, under the OP readout, left
 * of it, above it, then along the refit panel's top and bottom edges. It's added to the refit panel, so it hides with
 * the refit screen. Checked four times a second while the refit tab is open; placed again only when it's gone. The
 * button opens the order (JAOOrderPanel) over the screen. Placements, and what was found while it can't be placed, are
 * logged a few times a session; any error turns it off for the session. Setting "Order weapons for fleet button".
 */
public class JAOScreenButton implements EveryFrameScript {
    static final float W = 200f, H = 24f, GAP = 8f;
    static final String OPEN = "jao_order_open", ANALYSE = "jao_fleet_analysis", OPTIMIZE = "jao_fleet_optimize";
    static final Logger LOG = Global.getLogger(JAOScreenButton.class);
    static final String TAG = "Jonah's Auto Outfitting: refit screen button ";

    private boolean broken;
    private float timer;
    private UIPanelAPI core, refit, overlayHost;
    /** button: Order fleet weapons (with Optimize fleet under it); button2: Optimize fleet on its own; button3: Fleet analysis. */
    private CustomPanelAPI button, button2, button3, overlay;
    private int reports, placements;
    private String lastReport;

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return true; }   // the refit screen pauses the game

    public void advance(float amount) {
        if (broken) return;
        timer -= amount;
        if (timer > 0f) return;
        timer = 0.25f;
        try {
            update();
        } catch (Throwable t) {
            broken = true;
            remove();
            closeOrder();
            LOG.warn(TAG + "turned off for this session (LunaLib's refit menu still has it)", t);
        }
    }

    void update() {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (!JAOSettings.productionButton || ui.getCurrentCoreTab() != CoreUITabId.REFIT) {
            remove();
            closeOrder();
            refit = null;
            return;
        }
        UIPanelAPI found = refitPanel(ui);
        if (found == null) { remove(); report("refit panel not found", null, null, null); return; }
        if (button != null && found == refit && JAORefl.children(refit).contains(button)) return;   // still in place
        remove();
        refit = found;
        List<UIComponentAPI> leaves = new ArrayList<>();
        collect(refit, leaves, 0);
        float[] op = rectOf(JAORefl.call(refit, "getOpDisplay"));
        float[] autofit = null;
        for (UIComponentAPI c : leaves) {
            String t = text(c);
            if (t != null && t.toLowerCase(Locale.ROOT).contains("autofit")) { autofit = rect(c); break; }
        }
        // the same size as the game's own buttons when the Autofit button is there
        float w = autofit != null ? autofit[2] : W, h = autofit != null ? autofit[3] : H;
        // "Order fleet weapons" over "Optimize fleet" in the free cells right of Autofit and right of Strip (user 11/05:
        // Optimize fleet belongs on the refit screen itself, not only inside Fleet analysis), "Fleet analysis" in the next
        // free spot (beside them, else anywhere free on the refit panel; the Optimize fleet panel links to it too).
        // Without those cells, each button goes wherever there's room.
        boolean two = false;
        float[] at = null;
        if (autofit != null) {
            float[] stack = {autofit[0] + autofit[2] + 3f, autofit[1] - h - 3f};
            if (spot(rect(refit), leaves, null, null, w, 2f * h + 3f, stack) != null) { at = stack; two = true; }
        }
        if (at == null) at = spot(rect(refit), leaves, op, autofit, w, h);
        if (at == null) { report("no free spot", leaves, op, autofit); return; }
        button = make(w, h, two);
        PositionAPI rp = refit.getPosition();
        refit.addComponent(button).inBL(at[0] - rp.getX(), at[1] - rp.getY());
        List<float[]> ours = new ArrayList<>();
        ours.add(new float[]{at[0], at[1], w, two ? 2f * h + 3f : h});
        float[] atOpt = null;
        if (!two) {
            atOpt = spot(rect(refit), leaves, op, autofit, w, h, null, ours);
            if (atOpt != null) {
                button2 = single(w, h, OPTIMIZE, "Optimize fleet");
                refit.addComponent(button2).inBL(atOpt[0] - rp.getX(), atOpt[1] - rp.getY());
                ours.add(new float[]{atOpt[0], atOpt[1], w, h});
            }
        }
        float[] atAna = null;
        if (two) {
            float x2 = at[0] + w + 3f;
            for (float[] c : new float[][]{{x2, at[1] + h + 3f}, {x2, at[1]}, {x2, at[1] - h - 3f}}) {
                if (spot(rect(refit), leaves, null, null, w, h, c, ours) != null) { atAna = c; break; }
            }
        }
        if (atAna == null) atAna = spot(rect(refit), leaves, op, autofit, w, h, null, ours);
        if (atAna != null) {
            button3 = single(w, h, ANALYSE, "Fleet analysis");
            refit.addComponent(button3).inBL(atAna[0] - rp.getX(), atAna[1] - rp.getY());
        }
        if (placements < 4) {
            placements++;
            LOG.info(TAG + (two ? "(with Optimize fleet under it) " : "") + "placed at " + box(ours.get(0))
                    + (two ? "" : atOpt != null ? ", Optimize fleet at " + box(new float[]{atOpt[0], atOpt[1], w, h}) : ", Optimize fleet: no room")
                    + (atAna != null ? ", Fleet analysis at " + box(new float[]{atAna[0], atAna[1], w, h}) : ", Fleet analysis only in the Optimize fleet panel")
                    + " in " + refit.getClass().getName() + " " + box(rect(refit))
                    + "; OP readout " + box(op) + ", Autofit button " + box(autofit) + ", screen " + Math.round(screen(true)) + "x"
                    + Math.round(screen(false)) + "; buttons " + buttons(leaves));
        }
    }

    /** The refit panel: in the current tab of the dialog's core UI (docked) or the campaign's. Remembers that core. */
    UIPanelAPI refitPanel(CampaignUIAPI ui) {
        List<Object> cores = new ArrayList<>();
        InteractionDialogAPI dialog = ui.getCurrentInteractionDialog();
        if (dialog != null) cores.add(JAORefl.call(dialog, "getCoreUI"));
        cores.add(JAORefl.call(ui, "getCore"));
        for (Object c : cores) {
            if (!(c instanceof UIPanelAPI)) continue;
            Object tab = JAORefl.call(c, "getCurrentTab");
            UIPanelAPI found = find(tab instanceof UIPanelAPI ? (UIPanelAPI) tab : (UIPanelAPI) c, 0);
            if (found != null) { core = (UIPanelAPI) c; return found; }
        }
        return null;
    }

    static UIPanelAPI find(UIComponentAPI c, int depth) {
        if (c instanceof UIPanelAPI && JAORefl.isRefitPanel(c.getClass())) return (UIPanelAPI) c;
        if (depth >= 4) return null;
        for (Object o : JAORefl.children(c)) {
            if (!(o instanceof UIComponentAPI)) continue;
            UIPanelAPI f = find((UIComponentAPI) o, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    /** Every visible element under c with no children of its own; hidden ones (no opacity) and our button are skipped. */
    void collect(UIComponentAPI c, List<UIComponentAPI> leaves, int depth) {
        if ((c == button && button != null) || (c == button2 && button2 != null) || (c == button3 && button3 != null)) return;
        if (depth > 0 && c.getOpacity() <= 0f) return;
        boolean any = false;
        if (depth <= 16) for (Object o : JAORefl.children(c)) if (o instanceof UIComponentAPI) { any = true; collect((UIComponentAPI) o, leaves, depth + 1); }
        if (!any) leaves.add(c);
    }

    /** Bottom-left corner (screen coordinates) of the first free candidate spot for a w x h button, or null. */
    static float[] spot(float[] panel, List<UIComponentAPI> leaves, float[] op, float[] autofit, float w, float h) {
        return spot(panel, leaves, op, autofit, w, h, null);
    }

    /** As above; with only set, that spot or null. */
    static float[] spot(float[] panel, List<UIComponentAPI> leaves, float[] op, float[] autofit, float w, float h, float[] only) {
        return spot(panel, leaves, op, autofit, w, h, only, null);
    }

    /** As above, also keeping clear of extra rectangles (x, y, w, h: our own buttons already placed). */
    static float[] spot(float[] panel, List<UIComponentAPI> leaves, float[] op, float[] autofit, float w, float h, float[] only, List<float[]> extra) {
        List<float[]> taken = new ArrayList<>();
        if (extra != null) taken.addAll(extra);
        float big = 0.15f * panel[2] * panel[3];   // backgrounds and the ship display don't count, what's drawn on them does
        for (UIComponentAPI c : leaves) {
            float[] r = rect(c);
            if (r[2] < 2f || r[3] < 2f || r[2] * r[3] > big) continue;
            taken.add(r);
        }
        List<float[]> cands = new ArrayList<>();
        if (only != null) cands.add(only);
        else if (autofit != null) {
            // the game's button grid (Weapon groups, Autofit / Undo, Strip / Restore, Run simulation, Next w/ free OP,
            // 3 apart): the free cell right of Autofit, then right of the button under it
            cands.add(new float[]{autofit[0] + autofit[2] + 3f, autofit[1]});
            cands.add(new float[]{autofit[0] + autofit[2] + 3f, autofit[1] - autofit[3] - 3f});
            cands.add(new float[]{autofit[0] - GAP - w, autofit[1] + (autofit[3] - h) / 2f});
            cands.add(new float[]{autofit[0] + autofit[2] - w, autofit[1] + autofit[3] + GAP});
            cands.add(new float[]{autofit[0] + autofit[2] - w, autofit[1] - GAP - h});
        }
        if (only == null && op != null) {
            cands.add(new float[]{op[0] + op[2] - w, op[1] - GAP - h});
            cands.add(new float[]{op[0] - GAP - w, op[1] + op[3] - h});
            cands.add(new float[]{op[0] + op[2] - w, op[1] + op[3] + GAP});
        }
        for (float x = panel[0] + panel[2] - w - 10f; only == null && x >= panel[0] + 10f; x -= w / 2f) {
            cands.add(new float[]{x, panel[1] + panel[3] - h - 6f});
            cands.add(new float[]{x, panel[1] + 6f});
        }
        float sw = screen(true), sh = screen(false);
        for (float[] at : cands) {
            float[] r = {at[0], at[1], w, h};
            if (!inside(r, panel) || (sw > 0f && !inside(r, new float[]{0f, 0f, sw, sh}))) continue;
            boolean free = true;
            for (float[] o : taken) if (overlaps(r, o, 2f)) { free = false; break; }
            if (free) return at;
        }
        return null;
    }

    /** "Order fleet weapons", with "Optimize fleet" under it when two. */
    CustomPanelAPI make(float w, float h, boolean two) {
        float ph = two ? 2f * h + 3f : h;
        CustomPanelAPI p = Global.getSettings().createCustom(w, ph, new BaseCustomUIPanelPlugin() {
            @Override public void buttonPressed(Object id) { pressed(id); }
        });
        TooltipMakerAPI t = p.createUIElement(w, ph, false);
        addButton(t, OPEN, "Order fleet weapons", w, h, 0f);
        if (two) addButton(t, OPTIMIZE, "Optimize fleet", w, h, h + 3f);
        p.addUIElement(t).inTL(0f, 0f);
        return p;
    }

    /** One button on its own (Optimize fleet or Fleet analysis away from the stack). */
    CustomPanelAPI single(float w, float h, String id, String label) {
        CustomPanelAPI p = Global.getSettings().createCustom(w, h, new BaseCustomUIPanelPlugin() {
            @Override public void buttonPressed(Object pressedId) { pressed(pressedId); }
        });
        TooltipMakerAPI t = p.createUIElement(w, h, false);
        addButton(t, id, label, w, h, 0f);
        p.addUIElement(t).inTL(0f, 0f);
        return p;
    }

    void pressed(Object id) {
        if (OPEN.equals(id)) openOrder();
        else if (OPTIMIZE.equals(id)) openOptimize();
        else if (ANALYSE.equals(id)) openAnalysis();
    }

    /** A button in the game's colors at y from the element's top, with its tooltip. */
    static void addButton(TooltipMakerAPI t, String id, String label, float w, float h, float y) {
        ButtonAPI b = t.addButton(label, id, Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Alignment.MID, CutStyle.ALL, w, h, 0f);
        b.getPosition().inTL(0f, y);
        t.addTooltipToPrevious(new TooltipMakerAPI.TooltipCreator() {
            public boolean isTooltipExpandable(Object o) { return false; }
            public float getTooltipWidth(Object o) { return 440f; }
            public void createTooltip(TooltipMakerAPI tt, boolean expanded, Object o) {
                if (OPEN.equals(id)) {
                    tt.addPara("Plans the whole fleet's Optimal fits together as if every weapon and fighter blueprint you know "
                            + "were in stock, and lists everything custom production would have to build for it. Nothing is ordered "
                            + "until you press Queue all; pressing it again later only orders what's still missing.", 0f);
                    tt.addPara("Budget: %s of your credits (mod settings).", 6f, Misc.getHighlightColor(),
                            Math.round(JAOSettings.productionBudget * 100f) + "%");
                } else if (OPTIMIZE.equals(id)) {
                    tt.addPara("Plans every combat ship's Optimal fit together, from what the ships carry, your cargo and the storage "
                            + "where you're docked, balancing the whole fleet (anti-shield, armor-cracking, fighter roles). Shows what "
                            + "changes on each ship; nothing changes until you press Apply to all ships.", 0f);
                    tt.addPara("S-mods: %s (mod settings).", 6f, Misc.getHighlightColor(), JAOSettings.fleetSmods ? "built in, one story point each" : "off");
                } else {
                    tt.addPara("What your fleet carries: anti-shield and armor-cracking against their aims, damage types, fighter roles, "
                            + "point defense, range bands and empty mounts, with recommendations. Changes nothing.", 0f);
                }
            }
        }, TooltipMakerAPI.TooltipLocation.BELOW);
    }

    /** Plans the order and shows it centred over the screen, in the core UI so it sits above the refit screen. */
    void openOrder() {
        if (overlay != null) return;
        JAOOrderPanel content = new JAOOrderPanel(JAOOrderPanel.start(), this::closeOrder, true);
        if (show(content, JAOOrderPanel.W, JAOOrderPanel.H)) content.build(overlay);
    }

    /** The fleet analysis, centred over the screen like the order; its Optimize fleet button opens that panel instead. */
    void openAnalysis() {
        if (overlay != null) return;
        JAOAnalysisPanel content = new JAOAnalysisPanel(JAOAnalysisPanel.analyseNow(), this::closeOrder, () -> { closeOrder(); openOptimize(); }, true);
        if (show(content, JAOAnalysisPanel.W, JAOAnalysisPanel.H)) content.build(overlay);
    }

    /** Optimize fleet: plans the whole fleet, then shows what would change, over the screen; it links to Fleet analysis. */
    void openOptimize() {
        if (overlay != null) return;
        JAOFleetPanel content = new JAOFleetPanel(JAOFleetPanel.start(refit), this::closeOrder, () -> { closeOrder(); openAnalysis(); }, true);
        if (show(content, JAOFleetPanel.W, JAOFleetPanel.H)) content.build(overlay);
    }

    private boolean show(JAOOverlayPanel content, float w, float h) {
        UIPanelAPI host = core != null ? core : refit;
        if (host == null) return false;
        overlay = Global.getSettings().createCustom(w, h, content);
        PositionAPI hp = host.getPosition();
        float sw = screen(true) > 0f ? screen(true) : hp.getWidth(), sh = screen(false) > 0f ? screen(false) : hp.getHeight();
        host.addComponent(overlay).inBL((sw - w) / 2f - hp.getX(), (sh - h) / 2f - hp.getY());
        overlayHost = host;
        return true;
    }

    void closeOrder() {
        try { if (overlay != null && overlayHost != null) overlayHost.removeComponent(overlay); } catch (Throwable ignored) { }
        overlay = null;
        overlayHost = null;
    }

    void remove() {
        for (CustomPanelAPI b : new CustomPanelAPI[]{button, button2, button3}) {
            try { if (b != null && refit != null) refit.removeComponent(b); } catch (Throwable ignored) { }
        }
        button = null;
        button2 = null;
        button3 = null;
    }

    /** What was found while the button can't be placed, when it changes, a few times a session. */
    void report(String why, List<UIComponentAPI> leaves, float[] op, float[] autofit) {
        if (reports >= 6) return;
        String r = TAG + "not shown: " + why + (refit != null ? " in " + refit.getClass().getName() + " " + box(rect(refit)) : "")
                + "; OP readout " + box(op) + ", Autofit button " + box(autofit) + (leaves != null ? ", " + leaves.size() + " elements; buttons " + buttons(leaves) : "");
        if (r.equals(lastReport)) return;
        lastReport = r;
        reports++;
        LOG.info(r);
    }

    static String text(UIComponentAPI c) {
        if (!(c instanceof ButtonAPI)) return null;
        try { return ((ButtonAPI) c).getText(); } catch (Throwable t) { return null; }
    }

    static String buttons(List<UIComponentAPI> leaves) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (UIComponentAPI c : leaves) {
            if (!(c instanceof ButtonAPI)) continue;
            if (n++ >= 24) { b.append(" ..."); break; }
            String t = text(c);
            b.append(n > 1 ? ", " : "").append('"').append(t == null ? "" : t.replace('\n', ' ')).append("\" ").append(box(rect(c)));
        }
        return n == 0 ? "none" : b.toString();
    }

    static float screen(boolean width) {
        try { return width ? Global.getSettings().getScreenWidth() : Global.getSettings().getScreenHeight(); } catch (Throwable t) { return 0f; }
    }

    static float[] rectOf(Object o) { return o instanceof UIComponentAPI ? rect((UIComponentAPI) o) : null; }

    static float[] rect(UIComponentAPI c) {
        PositionAPI p = c.getPosition();
        return new float[]{p.getX(), p.getY(), p.getWidth(), p.getHeight()};
    }

    static boolean overlaps(float[] a, float[] b, float margin) {
        return a[0] < b[0] + b[2] + margin && b[0] < a[0] + a[2] + margin && a[1] < b[1] + b[3] + margin && b[1] < a[1] + a[3] + margin;
    }

    static boolean inside(float[] a, float[] outer) {
        return a[0] >= outer[0] && a[1] >= outer[1] && a[0] + a[2] <= outer[0] + outer[2] && a[1] + a[3] <= outer[1] + outer[3];
    }

    static String box(float[] r) {
        return r == null ? "none" : "(" + Math.round(r[0]) + ", " + Math.round(r[1]) + ", " + Math.round(r[2]) + "x" + Math.round(r[3]) + ")";
    }
}
