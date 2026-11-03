package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.List;

/**
 * Shared base of the refit screen's pop-up panels (fleet order, fleet analysis). As an overlay it draws its own
 * background over a dimmed screen and takes the input like LunaLib's refit panels: Escape or a click outside closes
 * it. Inside LunaLib's refit panel LunaLib does both, so overlay is false there. Game colors only.
 */
abstract class JAOOverlayPanel implements CustomUIPanelPlugin {
    final Runnable close;
    final boolean overlay;
    PositionAPI pos;

    JAOOverlayPanel(Runnable close, boolean overlay) { this.close = close; this.overlay = overlay; }

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
