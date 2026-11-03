package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.BaseCampaignPlugin;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.plugins.AutofitPlugin;

/**
 * Gives the refit screen our autofit plugin. Transient: registered again on every load, so saves never depend on it.
 * The game asks this only for the refit screen's autofit (NPC fleets use DefaultFleetInflater).
 */
public class JAOCampaignPlugin extends BaseCampaignPlugin {
    @Override
    public String getId() { return "jonahs_auto_outfitting"; }

    @Override
    public boolean isTransient() { return true; }

    @Override
    public PluginPick<AutofitPlugin> pickAutofitPlugin(FleetMemberAPI member) {
        PersonAPI commander = null;
        if (member != null) {
            commander = member.getFleetCommanderForStats();
            if (commander == null) commander = member.getFleetCommander();
            // the dialog builds its goal row right after this call (null member = fleet tab officer auto-assign)
            if (JAOSettings.enabled) JAOGoals.inject(member);
        }
        return new PluginPick<AutofitPlugin>(new JAOAutofitPlugin(commander), PickPriority.MOD_GENERAL);
    }
}
