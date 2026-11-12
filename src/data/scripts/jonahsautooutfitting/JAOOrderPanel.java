package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
import java.util.List;

/**
 * The fleet production order (JAOFleetPlanner): planned a frame at a time with a progress line, then the summary, the
 * fleet before and after the plan, the item list (scrolls), Queue all / Close. Nothing is ordered without Queue all.
 * Drawn as an overlay from the refit screen button, or inside LunaLib's refit panel (JAORefitButton).
 */
final class JAOOrderPanel extends JAOOverlayPanel {
    static final float W = 900f, H = 640f, HEAD = 230f;
    final JAOFleetPlanner.Job job;   // null when planning couldn't start
    JAOFleetPlanner.Plan plan;
    ButtonAPI queue;
    TooltipMakerAPI status;

    JAOOrderPanel(JAOFleetPlanner.Job job, Runnable close, boolean overlay) { super(close, overlay); this.job = job; }

    /** Starts planning the order; null (logged) if that fails, which the panel shows as an error. */
    static JAOFleetPlanner.Job start() {
        try {
            return new JAOFleetPlanner.Job();
        } catch (Throwable t) {
            Global.getLogger(JAOOrderPanel.class).error("Jonah's Auto Outfitting: fleet order planning failed", t);
            return null;
        }
    }

    @Override String title() { return "Fleet production order"; }
    @Override float width() { return W; }
    @Override float height() { return H; }

    /** One planning slice a frame; the result once the plan is done. */
    @Override
    boolean work() {
        if (job == null) return true;
        try {
            if (!job.step(25L)) return false;
            plan = job.plan;
        } catch (Throwable t) {
            Global.getLogger(JAOOrderPanel.class).error("Jonah's Auto Outfitting: fleet order planning failed", t);
            plan = null;
        }
        return true;
    }

    @Override String progress() { return job == null ? "" : job.progress(); }

    @Override
    void buildResult() {
        Color h = Misc.getHighlightColor(), g = Misc.getGrayColor(), bad = Misc.getNegativeHighlightColor();
        TooltipMakerAPI head = element(W - 20f, HEAD, false, 10f, 10f);
        head.addSectionHeading(title(), Alignment.MID, 0f);
        if (plan == null) {
            head.addPara("Couldn't plan the fleet order (see starsector.log).", 6f, bad);
        } else {
            if (plan.lines.isEmpty()) {
                head.addPara(plan.laterItems > 0 ? "Nothing fits the budget now." : "Nothing new to build: your fleet's plan uses only what you own or have on order, or you know no "
                        + "blueprint that would improve it.", 6f);
            } else {
                int units = 0;
                for (JAOFleetPlanner.Line l : plan.lines) units += l.count;
                head.addPara("%s items (%s kinds) for the fleet's plan, %s in all. You have %s; budget %s.", 6f, h,
                        "" + units, "" + plan.lines.size(), Misc.getDGSCredits(plan.totalCost), Misc.getDGSCredits(plan.credits), Misc.getDGSCredits(plan.budget));
            }
            if (plan.laterItems > 0) head.addPara("Over the budget: %s more items for %s ships (%s). Press Order fleet weapons again later to order them.",
                    4f, h, "" + plan.laterItems, "" + plan.laterShips, Misc.getDGSCredits(plan.laterCost));
            if (plan.before != null && plan.after != null) head.addPara(esc(mixLine(plan.before, plan.after)), 4f, g);
            if (!plan.moved.isEmpty()) {
                int units = 0;
                for (JAOFleetPlanner.Line l : plan.moved) units += l.count;
                head.addPara("%s items from an earlier order (%s) are in the game's own production list, which AoTD's screen doesn't "
                        + "show. Queue all moves them into AoTD's production.", 4f, h, "" + units, Misc.getDGSCredits(plan.movedCost));
            }
            if (plan.aotd) {
                head.addPara("Production: Ashes of the Domain (Colonies, Custom production). Each unit is paid when it's finished, "
                        + "and AoTD needs its resources delivered.", 4f, g);
                if (!plan.resources.isEmpty()) {
                    // AoTD's order takes these from your colonies' exports every month; a good no colony makes stalls it
                    List<String> none = JAOProduction.unmade(plan.resources);
                    head.addPara(esc("It takes from your colonies: " + JAOProduction.describe(plan.resources) + "."), 4f, none.isEmpty() ? g : h);
                    // Colony Manager 2.39 plans producers only for goods no colony makes; one made in small amounts isn't
                    // boosted, and the order just goes as fast as that output (its session, 11/05)
                    boolean cm = JAOProduction.colonyManagerSupplies();
                    List<String> names = new java.util.ArrayList<>();
                    for (String c : none) names.add(JAOProduction.commodityName(c));
                    if (cm) {
                        head.addPara(esc((none.isEmpty() ? "" : "Colony Manager plans a producer for " + Misc.getAndJoined(names)
                                + " (Special project supply, under Items and AI cores); those items wait until it runs. ")
                                + "It only plans for goods no colony makes: one your colonies make little of just slows the order."), 4f, g);
                    } else if (!none.isEmpty()) {
                        head.addPara(esc("Those items wait until a colony makes " + Misc.getAndJoined(names) + "."), 4f, bad);
                    }
                }
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
            if (plan.skippedCivilian > 0) notes.append(plan.skippedCivilian).append(" civilian ships skipped (logistics fit). ");
            if (plan.locked > 0) notes.append(plan.locked).append(plan.locked == 1 ? " locked ship gets" : " locked ships get").append(" nothing ordered and keeps what it carries. ");
            if (plan.failed > 0) notes.append(plan.failed).append(" ships couldn't be planned (see starsector.log). ");
            if (notes.length() > 0) head.addPara(notes.toString().trim(), 4f, g);
            for (int i = 0; i < plan.notes.size() && i < 2; i++) head.addPara(esc(plan.notes.get(i) + "."), 4f, g);
            if (plan.notes.size() > 2) head.addPara((plan.notes.size() - 2) + " more ship notes in starsector.log.", 4f, g);
        }

        if (plan != null) {
            TooltipMakerAPI list = element(W - 20f, H - HEAD - 80f, true, 10f, HEAD + 20f);
            Color text = Global.getSettings().getColor("standardTextColor");
            List<JAOFleetPlanner.Line> all = new java.util.ArrayList<>(plan.lines);
            all.addAll(plan.moved);
            for (JAOFleetPlanner.Line l : all) {
                String ships = l.ships.size() <= 3 ? String.join(", ", l.ships) : String.join(", ", l.ships.subList(0, 3)) + " +" + (l.ships.size() - 3);
                list.addPara("%s  %s  %s (%s each)   %s", 3f, new Color[]{h, text, h, g, g},
                        l.count + "x", l.name, Misc.getDGSCredits(l.total()), Misc.getDGSCredits(l.unitCost), ships);
            }
        }

        TooltipMakerAPI buttons = element(W - 20f, 40f, false, 10f, H - 50f);
        queue = buttons.addButton("Queue all", "queue", 200f, 30f, 0f);
        // AoTD runs its own production; the game's needs Heavy Industry or Orbital Works capacity
        queue.setEnabled(plan != null && plan.anything() && (plan.aotd || plan.monthlyCapacity > 0));
        ButtonAPI closeButton = buttons.addButton("Close", "close", 140f, 30f, 0f);
        closeButton.getPosition().rightOfTop(queue, 10f);
    }

    /** "The plan's fleet: anti-shield 33% -> 46%, armor-cracking 34% -> 41%, bays fighters/bombers/interceptors/support 1/24/0/8 -> 9/15/1/9." */
    static String mixLine(JAOFleetMix before, JAOFleetMix after) {
        return "Fleet now -> with the plan: anti-shield " + pct(before.shieldShare()) + " -> " + pct(after.shieldShare())
                + " (aim " + pct(JAOFleetMix.SHIELD_TARGET) + "), armor-cracking " + pct(before.armorShare()) + " -> " + pct(after.armorShare())
                + " (aim " + pct(JAOFleetMix.ARMOR_TARGET) + "), fighter bays (fighters/bombers/interceptors/support) " + bays(before) + " -> " + bays(after) + ".";
    }

    static String bays(JAOFleetMix m) { return (int) m.wingBays[0] + "/" + (int) m.wingBays[1] + "/" + (int) m.wingBays[2] + "/" + (int) m.wingBays[3]; }

    static String pct(float f) { return Math.round(100f * f) + "%"; }

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
            if (status == null) status = element(W - 400f, 40f, false, 370f, H - 48f);
            boolean failed = msg.contains("nothing was ordered");
            status.addPara(msg + (failed ? "." : ". See Colonies, Custom production."), 0f, failed ? Misc.getNegativeHighlightColor() : Misc.getPositiveHighlightColor());
        }
    }
}
