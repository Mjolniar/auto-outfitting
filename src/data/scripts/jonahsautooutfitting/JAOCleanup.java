package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;

/**
 * Removes the temporary Optimal goals once the core UI (fleet, refit, cargo ...) is closed, so they never stay in
 * the goal row, the save, or the NPC fleet inflaters' view. Waits for the whole core UI rather than the refit tab
 * because the fleet tab's "Mass autofit" opens the same dialog. Transient; runs while paused.
 */
public class JAOCleanup implements EveryFrameScript {
    static boolean injected;

    @Override
    public boolean isDone() { return false; }

    @Override
    public boolean runWhilePaused() { return true; }

    @Override
    public void advance(float amount) {
        if (!injected) return;
        if (Global.getSector().getCampaignUI().getCurrentCoreTab() != null) return;
        JAOGoals.removeAll();
        injected = false;
    }
}
