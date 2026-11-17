package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import lunalib.lunaRefit.BaseRefitButton;

/**
 * The same order in LunaLib's refit menu (its small icon by the OP readout), next to the button JAOScreenButton puts
 * on the refit screen itself. Shows JAOOrderPanel inside LunaLib's panel. Only touched when LunaLib is enabled.
 */
public class JAORefitButton extends BaseRefitButton {
    @Override
    public String getButtonName(FleetMemberAPI member, ShipVariantAPI variant) { return "Order weapons for fleet"; }

    @Override
    public String getIconName(FleetMemberAPI member, ShipVariantAPI variant) { return "graphics/icons/intel/production_report.png"; }

    @Override
    public int getOrder(FleetMemberAPI member, ShipVariantAPI variant) { return 50; }

    @Override
    public boolean shouldShow(FleetMemberAPI member, ShipVariantAPI variant, MarketAPI market) {
        return JAOSettings.productionButton && Global.getSector() != null && Global.getSector().getPlayerFleet() != null;
    }

    @Override
    public boolean hasTooltip(FleetMemberAPI member, ShipVariantAPI variant, MarketAPI market) { return true; }

    @Override
    public void addTooltip(TooltipMakerAPI tooltip, FleetMemberAPI member, ShipVariantAPI variant, MarketAPI market) {
        tooltip.addPara("Plans the whole fleet's Optimal fits together like every weapon and fighter blueprint you know "
                + "is in stock, then lists what custom production would have to build. Nothing gets ordered until you hit "
                + "Queue all.", 0f);
        tooltip.addPara("Budget: %s of your credits (in the mod's settings).", 6f, Misc.getHighlightColor(),
                Math.round(JAOSettings.productionBudget * 100f) + "%");
    }

    @Override
    public float getPanelWidth(FleetMemberAPI member, ShipVariantAPI variant) { return JAOOrderPanel.W; }

    @Override
    public float getPanelHeight(FleetMemberAPI member, ShipVariantAPI variant) { return JAOOrderPanel.H; }

    @Override
    public boolean hasPanel(FleetMemberAPI member, ShipVariantAPI variant, MarketAPI market) { return true; }

    @Override
    public void initPanel(CustomPanelAPI background, FleetMemberAPI member, ShipVariantAPI variant, MarketAPI market) {
        JAOOrderPanel[] self = new JAOOrderPanel[1];
        // 0.5.60: closing stops the planning (LunaLib's own close isn't seen; its worker ends after 30 s idle)
        JAOOrderPanel p = new JAOOrderPanel(JAOOrderPanel.start(), () -> { if (self[0] != null) self[0].closing(); closePanel(); }, false);
        self[0] = p;
        CustomPanelAPI inner = Global.getSettings().createCustom(JAOOrderPanel.W, JAOOrderPanel.H, p);
        background.addComponent(inner).inTL(0f, 0f);
        p.build(inner);
    }
}
