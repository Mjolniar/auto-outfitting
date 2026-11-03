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
    static final String OPEN = "jao_order_open";
    static final Logger LOG = Global.getLogger(JAOScreenButton.class);
    static final String TAG = "Jonah's Auto Outfitting: refit screen button ";

    private boolean broken;
    private float timer;
    private UIPanelAPI core, refit, overlayHost;
    private CustomPanelAPI button, overlay;
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
        float[] at = spot(rect(refit), leaves, op, autofit, w, h);
        if (at == null) { report("no free spot", leaves, op, autofit); return; }
        button = make(w, h);
        PositionAPI rp = refit.getPosition();
        refit.addComponent(button).inBL(at[0] - rp.getX(), at[1] - rp.getY());
        if (placements < 4) {
            placements++;
            LOG.info(TAG + "placed at " + box(new float[]{at[0], at[1], w, h}) + " in " + refit.getClass().getName() + " " + box(rect(refit))
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
        if (c == button && button != null) return;
        if (depth > 0 && c.getOpacity() <= 0f) return;
        boolean any = false;
        if (depth <= 16) for (Object o : JAORefl.children(c)) if (o instanceof UIComponentAPI) { any = true; collect((UIComponentAPI) o, leaves, depth + 1); }
        if (!any) leaves.add(c);
    }

    /** Bottom-left corner (screen coordinates) of the first free candidate spot for a w x h button, or null. */
    static float[] spot(float[] panel, List<UIComponentAPI> leaves, float[] op, float[] autofit, float w, float h) {
        List<float[]> taken = new ArrayList<>();
        float big = 0.15f * panel[2] * panel[3];   // backgrounds and the ship display don't count, what's drawn on them does
        for (UIComponentAPI c : leaves) {
            float[] r = rect(c);
            if (r[2] < 2f || r[3] < 2f || r[2] * r[3] > big) continue;
            taken.add(r);
        }
        List<float[]> cands = new ArrayList<>();
        if (autofit != null) {
            // the game's button grid (Weapon groups, Autofit / Undo, Strip / Restore, Run simulation, Next w/ free OP,
            // 3 apart): the free cell right of Autofit, then right of the button under it
            cands.add(new float[]{autofit[0] + autofit[2] + 3f, autofit[1]});
            cands.add(new float[]{autofit[0] + autofit[2] + 3f, autofit[1] - autofit[3] - 3f});
            cands.add(new float[]{autofit[0] - GAP - w, autofit[1] + (autofit[3] - h) / 2f});
            cands.add(new float[]{autofit[0] + autofit[2] - w, autofit[1] + autofit[3] + GAP});
            cands.add(new float[]{autofit[0] + autofit[2] - w, autofit[1] - GAP - h});
        }
        if (op != null) {
            cands.add(new float[]{op[0] + op[2] - w, op[1] - GAP - h});
            cands.add(new float[]{op[0] - GAP - w, op[1] + op[3] - h});
            cands.add(new float[]{op[0] + op[2] - w, op[1] + op[3] + GAP});
        }
        for (float x = panel[0] + panel[2] - w - 10f; x >= panel[0] + 10f; x -= w / 2f) {
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

    CustomPanelAPI make(float w, float h) {
        CustomPanelAPI p = Global.getSettings().createCustom(w, h, new BaseCustomUIPanelPlugin() {
            @Override public void buttonPressed(Object id) { if (OPEN.equals(id)) openOrder(); }
        });
        TooltipMakerAPI t = p.createUIElement(w, h, false);
        ButtonAPI b = t.addButton("Order fleet weapons", OPEN, Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Alignment.MID, CutStyle.ALL, w, h, 0f);
        b.getPosition().inTL(0f, 0f);
        t.addTooltipToPrevious(new TooltipMakerAPI.TooltipCreator() {
            public boolean isTooltipExpandable(Object o) { return false; }
            public float getTooltipWidth(Object o) { return 420f; }
            public void createTooltip(TooltipMakerAPI tt, boolean expanded, Object o) {
                tt.addPara("Plans the Optimal fit of every ship in your fleet as if every weapon and fighter blueprint you know "
                        + "were in stock, and lists what custom production would have to build. Nothing is ordered until you press "
                        + "Queue all.", 0f);
                tt.addPara("Budget: %s of your credits (mod settings).", 6f, Misc.getHighlightColor(),
                        Math.round(JAOSettings.productionBudget * 100f) + "%");
            }
        }, TooltipMakerAPI.TooltipLocation.BELOW);
        p.addUIElement(t).inTL(0f, 0f);
        return p;
    }

    /** Plans the order and shows it centred over the screen, in the core UI so it sits above the refit screen. */
    void openOrder() {
        if (overlay != null) return;
        UIPanelAPI host = core != null ? core : refit;
        if (host == null) return;
        JAOOrderPanel content = new JAOOrderPanel(JAOOrderPanel.planNow(), this::closeOrder, true);
        overlay = Global.getSettings().createCustom(JAOOrderPanel.W, JAOOrderPanel.H, content);
        PositionAPI hp = host.getPosition();
        float sw = screen(true) > 0f ? screen(true) : hp.getWidth(), sh = screen(false) > 0f ? screen(false) : hp.getHeight();
        host.addComponent(overlay).inBL((sw - JAOOrderPanel.W) / 2f - hp.getX(), (sh - JAOOrderPanel.H) / 2f - hp.getY());
        overlayHost = host;
        content.build(overlay);
    }

    void closeOrder() {
        try { if (overlay != null && overlayHost != null) overlayHost.removeComponent(overlay); } catch (Throwable ignored) { }
        overlay = null;
        overlayHost = null;
    }

    void remove() {
        try { if (button != null && refit != null) refit.removeComponent(button); } catch (Throwable ignored) { }
        button = null;
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
