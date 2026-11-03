package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.List;

/**
 * The fleet production order (JAOFleetPlanner): summary, the item list (scrolls), Queue all / Close. Nothing is ordered
 * without Queue all. Game colors only. As an overlay (the refit screen button) it draws its own background over a
 * dimmed screen and takes the input like LunaLib's refit panels: Escape or a click outside closes it. Inside LunaLib's
 * refit panel (JAORefitButton) LunaLib does both.
 */
final class JAOOrderPanel implements CustomUIPanelPlugin {
    static final float W = 900f, H = 580f;
    final JAOFleetPlanner.Plan plan;   // null when planning failed
    final Runnable close;
    final boolean overlay;
    CustomPanelAPI inner;
    ButtonAPI queue;
    TooltipMakerAPI status;
    PositionAPI pos;

    JAOOrderPanel(JAOFleetPlanner.Plan plan, Runnable close, boolean overlay) { this.plan = plan; this.close = close; this.overlay = overlay; }

    /** Plans the order now; null (logged) if that fails, which the panel shows as an error. */
    static JAOFleetPlanner.Plan planNow() {
        try {
            return JAOFleetPlanner.plan();
        } catch (Throwable t) {
            Global.getLogger(JAOOrderPanel.class).error("Jonah's Auto Outfitting: fleet order planning failed", t);
            return null;
        }
    }

    void build(CustomPanelAPI inner) {
        this.inner = inner;
        Color h = Misc.getHighlightColor(), g = Misc.getGrayColor(), bad = Misc.getNegativeHighlightColor();
        TooltipMakerAPI head = inner.createUIElement(W - 20f, 130f, false);
        head.addSectionHeading("Fleet production order", Alignment.MID, 0f);
        if (plan == null) {
            head.addPara("Couldn't plan the fleet order (see starsector.log).", 6f, bad);
        } else {
            if (plan.lines.isEmpty()) {
                head.addPara("Nothing new to build: your fleet's Optimal fits use only what you own or have on order, or you know no "
                        + "blueprint that would improve them" + (plan.ownedOnly > 0 ? " within the budget." : "."), 6f);
            } else {
                int units = 0;
                for (JAOFleetPlanner.Line l : plan.lines) units += l.count;
                head.addPara("%s items (%s kinds) for %s ships, %s in all. You have %s; budget %s.", 6f, h,
                        "" + units, "" + plan.lines.size(), "" + plan.planned, Misc.getDGSCredits(plan.totalCost),
                        Misc.getDGSCredits(plan.credits), Misc.getDGSCredits(plan.budget));
            }
            if (!plan.moved.isEmpty()) {
                int units = 0;
                for (JAOFleetPlanner.Line l : plan.moved) units += l.count;
                head.addPara("%s items from an earlier order (%s) are in the game's own production list, which AoTD's screen doesn't "
                        + "show. Queue all moves them into AoTD's production.", 4f, h, "" + units, Misc.getDGSCredits(plan.movedCost));
            }
            if (plan.aotd) {
                head.addPara("Production: Ashes of the Domain (Colonies, Custom production). Each unit is paid when it's finished, "
                        + "and AoTD needs its resources delivered.", 4f, g);
            } else if (plan.monthlyCapacity <= 0) {
                head.addPara("You have no custom production capacity: you need a colony with Heavy Industry or Orbital Works.", 4f, bad);
            } else if (plan.totalCost > 0) {
                head.addPara("Production capacity %s a month: about %s, paid each month as items are built.", 4f, h,
                        Misc.getDGSCredits(plan.monthlyCapacity), months(plan.totalCost, plan.monthlyCapacity));
            }
            if (plan.problem != null) head.addPara(plan.problem + ".", 4f, bad);
            head.addPara("Gathering point: %s.", 4f, plan.gatheringPoint == null ? bad : h,
                    plan.gatheringPoint == null ? "none set (Queue all picks your biggest production colony)" : plan.gatheringPoint);
            StringBuilder notes = new StringBuilder();
            if (plan.ownedOnly > 0) notes.append(plan.ownedOnly).append(" smaller ships planned with owned stock only, to stay within the budget. ");
            if (plan.skippedCivilian > 0) notes.append(plan.skippedCivilian).append(" civilian ships skipped (logistics fit). ");
            if (plan.failed > 0) notes.append(plan.failed).append(" ships couldn't be planned (see starsector.log). ");
            if (notes.length() > 0) head.addPara(notes.toString().trim(), 4f, g);
        }
        inner.addUIElement(head).inTL(10f, 10f);

        if (plan != null) {
            TooltipMakerAPI list = inner.createUIElement(W - 20f, H - 210f, true);
            Color text = Global.getSettings().getColor("standardTextColor");
            List<JAOFleetPlanner.Line> all = new java.util.ArrayList<>(plan.lines);
            all.addAll(plan.moved);
            for (JAOFleetPlanner.Line l : all) {
                String ships = l.ships.size() <= 3 ? String.join(", ", l.ships) : String.join(", ", l.ships.subList(0, 3)) + " +" + (l.ships.size() - 3);
                list.addPara("%s  %s  %s (%s each)   %s", 3f, new Color[]{h, text, h, g, g},
                        l.count + "x", l.name, Misc.getDGSCredits(l.total()), Misc.getDGSCredits(l.unitCost), ships);
            }
            inner.addUIElement(list).inTL(10f, 150f);
        }

        TooltipMakerAPI buttons = inner.createUIElement(W - 20f, 40f, false);
        queue = buttons.addButton("Queue all", "queue", 200f, 30f, 0f);
        // AoTD runs its own production; the game's needs Heavy Industry or Orbital Works capacity
        queue.setEnabled(plan != null && plan.anything() && (plan.aotd || plan.monthlyCapacity > 0));
        ButtonAPI closeButton = buttons.addButton("Close", "close", 140f, 30f, 0f);
        closeButton.getPosition().rightOfTop(queue, 10f);
        inner.addUIElement(buttons).inTL(10f, H - 50f);
    }

    static String months(int cost, int perMonth) {
        int m = (int) Math.ceil(cost / (double) Math.max(1, perMonth));
        return m <= 1 ? "one month" : m + " months";
    }

    @Override
    public void buttonPressed(Object id) {
        if ("close".equals(id)) {
            close.run();
        } else if ("queue".equals(id) && queue != null && queue.isEnabled() && plan != null) {
            String msg = JAOFleetPlanner.queue(plan);
            queue.setEnabled(false);
            if (status == null) {
                status = inner.createUIElement(W - 400f, 40f, false);
                inner.addUIElement(status).inTL(370f, H - 48f);
            }
            boolean failed = msg.contains("nothing was ordered");
            status.addPara(msg + (failed ? "." : ". See Colonies, Custom production."), 0f, failed ? Misc.getNegativeHighlightColor() : Misc.getPositiveHighlightColor());
        }
    }

    @Override
    public void positionChanged(PositionAPI position) { pos = position; }

    /** Overlay only: the screen dimmed, the panel black with a border in the player's dark color. */
    @Override
    public void renderBelow(float alphaMult) {
        if (!overlay || pos == null) return;
        float x = pos.getX(), y = pos.getY(), w = pos.getWidth(), h = pos.getHeight();
        Color edge = Misc.getDarkPlayerColor();
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0f, 0f, 0f, 0.5f * alphaMult);
        GL11.glRectf(0f, 0f, Global.getSettings().getScreenWidth(), Global.getSettings().getScreenHeight());
        GL11.glColor4f(0f, 0f, 0f, 0.95f * alphaMult);
        GL11.glRectf(x, y, x + w, y + h);
        GL11.glColor4f(edge.getRed() / 255f, edge.getGreen() / 255f, edge.getBlue() / 255f, alphaMult);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
        GL11.glPopMatrix();
    }

    @Override
    public void render(float alphaMult) { }

    @Override
    public void advance(float amount) { }

    /** Overlay only (after its own buttons have had the events): Escape or a click outside closes it; nothing reaches the refit screen. */
    @Override
    public void processInput(List<InputEventAPI> events) {
        if (!overlay || pos == null) return;
        for (InputEventAPI e : events) {
            if (e.isConsumed()) continue;
            if (e.isKeyUpEvent() && e.getEventValue() == Keyboard.KEY_ESCAPE) {
                e.consume();
                close.run();
                continue;
            }
            if (e.isKeyboardEvent()) e.consume();
            if (e.isMouseDownEvent()) {
                boolean in = e.getX() >= pos.getX() && e.getX() <= pos.getX() + pos.getWidth()
                        && e.getY() >= pos.getY() && e.getY() <= pos.getY() + pos.getHeight();
                if (!in) { e.consume(); close.run(); continue; }
            }
            if (e.isMouseMoveEvent() || e.isMouseDownEvent() || e.isMouseScrollEvent()) e.consume();
        }
    }
}
