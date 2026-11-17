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

    static final String SMODS = "jao_build_smods";

    @Override
    public void doFit(ShipVariantAPI current, ShipVariantAPI target, int maxSMods, AutofitPluginDelegate delegate) {
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

    // CoreAutofitPlugin keeps these protected; the optimizer records what it bought through them.
    void recordWeapon(String key, AvailableWeapon w) { fittedWeapons.put(key, w); }
    void recordFighter(String key, AvailableFighter f) { fittedFighters.put(key, f); }
    MutableCharacterStatsAPI stats() { return stats; }
    List<AvailableWeapon> weapons(AutofitPluginDelegate delegate) { return getWeapons(delegate); }
    List<AvailableFighter> fighters(AutofitPluginDelegate delegate) { return getFighters(delegate); }
    boolean option(String id) { return isChecked(id); }
}
