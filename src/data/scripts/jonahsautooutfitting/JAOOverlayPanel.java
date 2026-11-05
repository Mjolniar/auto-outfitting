package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared base of the refit screen's pop-up panels (fleet order, fleet analysis, optimize fleet). Panels that plan
 * first do it a slice per frame (work) behind a progress line with a Close button, then build their result. As an
 * overlay it draws its own background over a dimmed screen and takes the input like LunaLib's refit panels: Escape or
 * a click outside closes it. Inside LunaLib's refit panel LunaLib does both, so overlay is false there. Game colors only.
 */
abstract class JAOOverlayPanel implements CustomUIPanelPlugin {
    final Runnable close;
    final boolean overlay;
    PositionAPI pos;
    CustomPanelAPI inner;
    private final List<UIComponentAPI> parts = new ArrayList<>();
    private boolean built;
    private float sinceProgress;

    JAOOverlayPanel(Runnable close, boolean overlay) { this.close = close; this.overlay = overlay; }

    abstract String title();
    abstract float width();
    abstract float height();
    /** One slice of work; true when there's nothing left to do (the default: nothing to do). */
    boolean work() { return true; }
    /** The progress line while work() runs. */
    String progress() { return ""; }
    abstract void buildResult();

    /** Called once the panel exists: the result straight away, or the progress view until work() is done. */
    void build(CustomPanelAPI inner) {
        this.inner = inner;
        if (work()) finishBuild(); else showProgress();
    }

    private void finishBuild() {
        clear();
        built = true;
        buildResult();
    }

    private void showProgress() {
        clear();
        TooltipMakerAPI t = element(width() - 20f, 90f, false, 10f, 10f);
        t.addSectionHeading(title(), Alignment.MID, 0f);
        t.addPara(esc(progress()) + " ...", 8f, Misc.getHighlightColor());
        t.addPara("Every ship is planned with the rest of the fleet in view; a big fleet takes a few seconds.", 4f, Misc.getGrayColor());
        TooltipMakerAPI b = element(width() - 20f, 40f, false, 10f, height() - 50f);
        b.addButton("Close", "close", 140f, 30f, 0f);
    }

    @Override
    public void advance(float amount) {
        if (inner == null || built) return;
        if (work()) { finishBuild(); return; }
        sinceProgress += amount;
        if (sinceProgress >= 0.2f) { sinceProgress = 0f; showProgress(); }
    }

    /** A text element at (x, y) from the panel's top left, removed by clear(). */
    TooltipMakerAPI element(float w, float h, boolean scroll, float x, float y) {
        TooltipMakerAPI t = inner.createUIElement(w, h, scroll);
        inner.addUIElement(t).inTL(x, y);
        parts.add(t);
        return t;
    }

    void clear() {
        for (UIComponentAPI c : parts) { try { inner.removeComponent(c); } catch (Throwable ignored) { } }
        parts.clear();
    }

    /** A Close button in the bottom left (panels without other buttons). */
    void closeButton() {
        TooltipMakerAPI buttons = element(width() - 20f, 40f, false, 10f, height() - 50f);
        ButtonAPI b = buttons.addButton("Close", "close", 140f, 30f, 0f);
        b.getPosition().inTL(0f, 0f);
    }

    @Override
    public void buttonPressed(Object id) {
        if ("close".equals(id)) close.run();
    }

    /** addPara treats its text as a format string (vanilla writes %% for a literal percent sign). */
    static String esc(String s) { return s.replace("%", "%%"); }

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
