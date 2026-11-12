package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * Loads the options at startup and on every save load (LunaLib has loaded its settings by then, whatever order the
 * mods start in), and registers the campaign plugin that hands the refit screen our autofit plugin. The temporary
 * Optimal goals are purged on load and before every save, so a save never depends on this mod.
 */
public class JAOModPlugin extends BaseModPlugin {
    @Override
    public void onApplicationLoad() {
        try { if (JAOSettings.luna()) JAOLuna.register(); }
        catch (Throwable t) { Global.getLogger(JAOModPlugin.class).warn("Auto Outfitting: LunaLib link failed; using " + JAOSettings.FILE, t); }
        JAOSettings.load();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        if (!JAOSettings.load()) {
            Global.getLogger(JAOModPlugin.class).warn("Auto Outfitting: LunaLib settings not loaded; using " + JAOSettings.FILE);
            JAOSettings.load(false);
        }
        JAOGoals.removeAll();
        JAORatings.reset();
        JAOFleetApply.purgeBlankLpcs();
        JAOCompat.purgeLpcs();
        Global.getSector().registerPlugin(new JAOCampaignPlugin());
        Global.getSector().addTransientScript(new JAOCleanup());
        Global.getSector().addTransientScript(new JAOScreenButton());
        Global.getLogger(JAOModPlugin.class).info("Auto Outfitting: " + JAOSettings.summary());
        publishStatus();
    }

    /**
     * One status line for Auto Colonies' Diagnostics page, in sector memory under "$jonahs_status_" + mod id,
     * so neither mod needs the other's classes. Written on load and whenever the settings change.
     */
    static void publishStatus() {
        try { Global.getSector().getMemoryWithoutUpdate().set("$jonahs_status_" + JAOSettings.MOD, "on: " + JAOSettings.summary()); }
        catch (Throwable ignored) { }   // no sector yet (title screen); the next game load writes it
    }

    @Override
    public void beforeGameSave() {
        JAOGoals.removeAll();
        JAOFleetApply.purgeBlankLpcs();
        JAOCompat.purgeLpcs();
    }
}
