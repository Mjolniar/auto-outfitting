package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.plugins.impl.CoreAutofitPlugin;

import java.util.List;

/**
 * The game's own autofit for every ordinary goal; our optimizer when the goal is one of our Optimal entries, or for
 * any goal while the "Optimal fit for every goal" box is ticked (for hulls whose 4 goal slots are all taken).
 * Extends CoreAutofitPlugin so the panel keeps its checkboxes (cargo, storage, market, black market, strip ...),
 * the credit cost of bought ordnance (fittedWeapons/fittedFighters, polled every frame) and officer auto-assign.
 * The dialog runs doFit on every goal click and option toggle (preview) and again on Confirm with the same seed,
 * so the optimizer must be deterministic and fast.
 */
public class JAOAutofitPlugin extends CoreAutofitPlugin {
    public JAOAutofitPlugin(PersonAPI fleetCommander) {
        super(fleetCommander);
        options.add(new AutofitOption(JAOGoals.OPTION, "Optimal fit for every goal", false,
                "With this ticked, Auto Outfitting builds the strongest loadout it can from what you have, no matter which goal you click. "
                        + "It's mainly for ships whose four goal slots are all taken up, otherwise just pick the Optimal goal."));
        options.add(new AutofitOption(SMODS, "Build in s-mods (story points, permanent)", false,
                "With an Optimal fit, it'll also build in the hullmods that make the ship strongest, and it checks each one by re-fitting "
                        + "the whole ship around it. It spends one story point per s-mod, and only when you hit Confirm (the preview never spends any). "
                        + "Limits, minimum gain, waiting for better hullmods and the story point reserve are all in the mod's settings."));
    }

    /**
     * 0.5.71: for Auto Colonies' station fits (Global.getSettings().getInstanceOfScript; the game has no public plugin
     * picker). No fleet commander: vent and capacitor limits are the base ones, OP costs carry no skill bonuses, and
     * s-mods stay off unless jao_build_smods is ticked.
     */
    public JAOAutofitPlugin() {
        this(null);
    }

    static final String SMODS = "jao_build_smods";

    @Override
    public void doFit(ShipVariantAPI current, ShipVariantAPI target, int maxSMods, AutofitPluginDelegate delegate) {
        // 0.5.79: the game's own fit (below) also fits a shared module variant in place once it is marked as a refit
        JAOOptimizer.restoreModules(current);
        JAOStyle style = null;
        if (JAOSettings.enabled) {
            style = JAOGoals.styleOf(target);
            if (style == null && isChecked(JAOGoals.OPTION)) style = JAOSettings.style;
        }
        if (style == null) {
            super.doFit(current, target, maxSMods, delegate);
            return;
        }
        ShipVariantAPI before = null;
        try { before = current.clone(); } catch (Throwable ignored) { }
        try {
            fittedWeapons.clear();
            fittedFighters.clear();
            new JAOOptimizer(this, current, maxSMods, delegate, style).run();
        } catch (Throwable t) {
            // 0.5.93: a cancelled station fit (startFit) just ends
            if (t instanceof JAOCoop.Cancelled) throw (JAOCoop.Cancelled) t;
            // never leave the player with a half-stripped ship: log it and fall back to the game's fit of a stock goal,
            // or (0.5.60, a hull with no stock goal) of the loadout it had
            Global.getLogger(JAOAutofitPlugin.class).error("Auto Outfitting: Optimal fit failed for "
                    + current.getHullSpec().getHullId() + "; using the game's autofit", t);
            ShipVariantAPI fallback = JAOGoals.styleOf(target) == null ? target : JAOGoals.stockGoal(current.getHullSpec().getHullId());
            if (fallback == null) fallback = before;
            if (fallback != null) {
                fittedWeapons.clear();
                fittedFighters.clear();
                super.doFit(current, fallback, maxSMods, delegate);
            }
        }
    }

    /**
     * 0.5.93: Auto Colonies' station fits without freezing the game (called by name, game API and JDK types only).
     * startFit returns a job that runs doFit with no s-mods on its own thread, in turns with the game's thread
     * (JAOCoop). stepFit gives it about budgetMs on the game thread and returns true once it ended. fitError is null
     * after a fit that finished; else the error, or the reason it stopped (cancelFit, or no stepFit for 30 s).
     * The variant is fitted in place: pass a working copy and use it only after stepFit returned true and fitError is
     * null. The job holds a thread, so it must not go into a save. One job at a time per plugin.
     */
    public Object startFit(ShipVariantAPI variant, ShipVariantAPI target, AutofitPluginDelegate delegate) {
        return new JAOCoop.Job(() -> doFit(variant, target, 0, delegate), "Auto Outfitting station fit");
    }

    public boolean stepFit(Object job, long budgetMs) {
        return ((JAOCoop.Job) job).step(budgetMs);
    }

    public Throwable fitError(Object job) {
        return ((JAOCoop.Job) job).error();
    }

    public void cancelFit(Object job) {
        ((JAOCoop.Job) job).cancel();
    }

    // CoreAutofitPlugin keeps these protected; the optimizer records what it bought through them.
    void recordWeapon(String key, AvailableWeapon w) { fittedWeapons.put(key, w); }
    void recordFighter(String key, AvailableFighter f) { fittedFighters.put(key, f); }
    MutableCharacterStatsAPI stats() { return stats; }
    List<AvailableWeapon> weapons(AutofitPluginDelegate delegate) { return getWeapons(delegate); }
    List<AvailableFighter> fighters(AutofitPluginDelegate delegate) { return getFighters(delegate); }
    boolean option(String id) { return isChecked(id); }

    /** 0.5.95: every box of the panel as id=ticked (JAOOptimizer.Memo key). */
    String optionSig() {
        StringBuilder sb = new StringBuilder();
        for (AutofitOption o : options) sb.append(o.id).append('=').append(o.checked).append(' ');
        return sb.toString();
    }
}
