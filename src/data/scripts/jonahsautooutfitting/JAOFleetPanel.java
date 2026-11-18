package data.scripts.jonahsautooutfitting;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * "Optimize fleet" (JAOFleetApply). 0.5.57 (user 11/16: the button should open a panel with every setting first): the
 * panel opens on its settings (sources, shops and credit limit, s-mods, officers, augments; per save, JAOFleetOptions).
 * Plan fleet plans the whole fleet a frame at a time, then shows what would change on every ship (weapons, wings,
 * hullmods off and on, s-mods, purchases, s-mod changes), the fleet before and after, and where the items come from.
 * Settings goes back to the settings. Nothing changes until Apply to all ships.
 */
final class JAOFleetPanel extends JAOOverlayPanel {
    static final float W = 900f, H = 600f;
    /** Makes a fresh plan from the settings. */
    final java.util.function.Supplier<JAOFleetApply> starter;
    /** The refit panel (the shops' trade rules), or null. */
    final Object refit;
    JAOFleetApply job;   // null on the settings view, or when planning couldn't start
    /** On the settings view (no plan yet). */
    boolean setup = true;
    /** Opens Fleet analysis (null: no button). */
    final Runnable analysis;
    boolean failed;
    ButtonAPI apply, storageBox, storedBox, legalBox, blackBox, smodsBox, changeBox, officersBox, augmentsBox;
    TooltipMakerAPI status;

    JAOFleetPanel(java.util.function.Supplier<JAOFleetApply> starter, Object refit, Runnable close, Runnable analysis, boolean overlay) {
        super(close, overlay);
        this.starter = starter;
        this.refit = refit;
        this.analysis = analysis;
    }

    static JAOFleetApply start(Object refit) {
        try {
            return new JAOFleetApply(refit);
        } catch (Throwable t) {
            Global.getLogger(JAOFleetPanel.class).error("Auto Outfitting: Optimize fleet couldn't start", t);
            return null;
        }
    }

    @Override String title() { return "Optimize fleet"; }
    @Override float width() { return W; }
    @Override float height() { return H; }
    @Override String progress() { return job == null ? "" : job.progress(); }

    @Override
    boolean work() {
        if (setup || job == null) return true;
        try {
            return job.step(25L);
        } catch (Throwable t) {
            Global.getLogger(JAOFleetPanel.class).error("Auto Outfitting: Optimize fleet planning failed", t);
            failed = true;
            return true;
        }
    }

    @Override
    void buildResult() {
        if (setup) buildSetup();
        else buildPlan();
    }

    // ------------------------------------------------------------------ settings

    /**
     * About how long Plan fleet takes with what's here now (0.5.66, JAOPlanTimes): the kinds of weapons and LPCs it can
     * pick from (fleet cargo, what's on the ships, storage here when it's used, the shops when they're ticked), each
     * unlocked warship's choosable slots, s-mod planning where the ship has room and story points are above the reserve,
     * civilian fits and officer placement. Null if it can't be worked out.
     */
    private String planTime(MarketAPI market, List<JAOShops.Shop> shops, boolean hasStorage) {
        try {
            com.fs.starfarer.api.campaign.CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
            java.util.Map<String, Integer> w = new java.util.TreeMap<>(), f = new java.util.TreeMap<>();
            JAOFleetPlan.addCargo(pf.getCargo(), w, f);
            if (hasStorage && JAOFleetOptions.useStorage()) JAOFleetPlan.addCargo(Misc.getStorageCargo(market), w, f);
            List<com.fs.starfarer.api.fleet.FleetMemberAPI> members = pf.getFleetData().getMembersListCopy();
            for (com.fs.starfarer.api.fleet.FleetMemberAPI m : members) if (m.getVariant() != null) JAOFleetPlan.addMountedWithModules(m.getVariant(), w, f);
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (String k : w.keySet()) ids.add("w:" + k);
            for (String k : f.keySet()) ids.add("f:" + k);
            if (shops != null && (JAOFleetOptions.buyLegal() || JAOFleetOptions.buyBlack())) {
                List<com.fs.starfarer.api.plugins.AutofitPlugin.AvailableWeapon> sw = new ArrayList<>();
                List<com.fs.starfarer.api.plugins.AutofitPlugin.AvailableFighter> sf = new ArrayList<>();
                JAOShops.stock(shops, JAOFleetOptions.buyLegal(), JAOFleetOptions.buyBlack(), sw, sf);
                for (com.fs.starfarer.api.plugins.AutofitPlugin.AvailableWeapon a : sw) ids.add("w:" + a.getId());
                for (com.fs.starfarer.api.plugins.AutofitPlugin.AvailableFighter a : sf) ids.add("f:" + a.getId());
            }
            int kinds = ids.size();
            com.fs.starfarer.api.characters.MutableCharacterStatsAPI ps = Global.getSector().getPlayerStats();
            boolean smods = JAOFleetOptions.smods() && ps.getStoryPoints() > JAOSettings.smodReserve;
            List<com.fs.starfarer.api.fleet.FleetMemberAPI> ships = JAOFleetPlan.combatShips(members, new int[1]);
            float pass = 0f;
            int planned = 0;
            for (com.fs.starfarer.api.fleet.FleetMemberAPI m : ships) {
                if (JAOLocks.locked(m) || m.getVariant() == null) continue;
                planned++;
                boolean room = Math.min(Misc.getMaxPermanentMods(m, ps), JAOOptimizer.sizeCap(m.getHullSpec().getHullSize())) > m.getVariant().getSMods().size();
                pass += JAOPlanTimes.passSeconds(smods && room, JAOPlanTimes.slots(m), kinds);
            }
            int civilians = JAOSettings.civilianLogistics ? JAOFleetPlan.civilianShips(members).size() : 0;
            int cells = JAOFleetApply.placeOfficers() ? planned * (pf.getFleetData().getOfficersCopy().size() + 1) : 0;
            return JAOPlanTimes.text(JAOPlanTimes.estimate(pass, civilians, cells));
        } catch (Throwable t) {
            Global.getLogger(JAOFleetPanel.class).warn("Auto Outfitting: couldn't estimate the planning time", t);
            return null;
        }
    }

    private void buildSetup() {
        Color h = Misc.getHighlightColor(), g = Misc.getGrayColor();
        MarketAPI market = JAOFleetApply.dockedMarket();
        CampaignUIAPI.CoreUITradeMode guess = market == null ? CampaignUIAPI.CoreUITradeMode.NONE
                : market.getFaction().isHostileTo(Factions.PLAYER) ? CampaignUIAPI.CoreUITradeMode.SNEAK : CampaignUIAPI.CoreUITradeMode.OPEN;
        com.fs.starfarer.api.campaign.CoreUIAPI ui = JAOShops.ui(refit, guess);
        CampaignUIAPI.CoreUITradeMode mode = market == null ? guess : JAOShops.mode(ui);
        List<JAOShops.Shop> shops = market == null ? new ArrayList<>() : JAOShops.at(market, ui, false);
        boolean hasStorage = market != null && Misc.playerHasStorageAccess(market);
        float credits = 0f;
        try { credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get(); } catch (Throwable ignored) { }

        TooltipMakerAPI t = scroller(W - 20f, H - 70f);
        t.addSectionHeading(title(), Alignment.MID, 0f);
        t.addPara("These settings are just for this save. Plan fleet shows you every change before anything happens, and nothing actually changes until you hit Apply to all ships.", 6f, g);
        String eta = planTime(market, shops, hasStorage);
        if (eta != null) t.addPara("With what you've got here right now, planning should take %s. It learns how fast your PC is from every run, so this gets more accurate over time.", 4f, g, h, eta);

        t.addSectionHeading("Weapons and LPCs", Alignment.MID, 10f);
        t.addPara("It always uses whatever's on the ships and in your fleet cargo.", 4f, g);
        storageBox = box(t, "Use storage here", "storage", JAOFleetOptions.useStorage());
        t.addPara(esc(hasStorage ? "This is your storage at " + market.getName() + ", and anything that comes off and doesn't get used goes there."
                : "You don't have storage here."), 0f, g);
        storedBox = box(t, "Strip stored ships", "stored", JAOFleetApply.stripStored());
        t.addPara("Weapons and LPCs on the ships you've got stored here get added to the stock (locked ships keep theirs). This needs Use storage here on.", 0f, g);
        String legal = JAOShops.names(shops, false), black = JAOShops.names(shops, true);
        legalBox = box(t, "Buy from legal shops", "legal", JAOFleetOptions.buyLegal());
        t.addPara(esc(legal.isEmpty() ? "None of the legal shops here will sell to you." : "Here: " + legal + "."), 0f, g);
        blackBox = box(t, "Buy from black markets", "black", JAOFleetOptions.buyBlack());
        t.addPara(esc(black.isEmpty() ? "There's no black market here." : "Here: " + black + "."), 0f, g);
        float share = JAOFleetOptions.budget();
        t.addPara("Credit limit: %s of your credits (%s). This covers both shop items and s-mod changes.", 6f, h,
                Math.round(share * 100f) + "%", Misc.getDGSCredits((float) Math.floor(credits * share)));
        // 0.5.63: Less and More sit in their own small panel. Placed side by side in the text element, everything after
        // them followed More's x and was cut off at the right edge.
        com.fs.starfarer.api.ui.CustomPanelAPI row = Global.getSettings().createCustom(166f, 22f, new com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin() {
            @Override public void buttonPressed(Object id) { JAOFleetPanel.this.buttonPressed(id); }
        });
        TooltipMakerAPI rb = row.createUIElement(166f, 22f, false);
        ButtonAPI less = rb.addButton("Less", "budgetDown", 80f, 22f, 0f);
        ButtonAPI more = rb.addButton("More", "budgetUp", 80f, 22f, 0f);
        less.getPosition().inTL(0f, 0f);
        more.getPosition().rightOfTop(less, 6f);
        row.addUIElement(rb).inTL(0f, 0f);
        t.addCustom(row, 4f);
        less.setEnabled(share > JAOFleetOptions.BUDGETS[0] + 0.001f);
        more.setEnabled(share < JAOFleetOptions.BUDGETS[JAOFleetOptions.BUDGETS.length - 1] - 0.001f);
        t.addPara("Anything it buys has to add %s score per 10,000 credits it costs (that's Minimum gain to buy in the settings). Owned-only weapons never get bought.", 4f, g, h,
                JAOSMods.pct(JAOSettings.buyGain));

        t.addSectionHeading("S-mods", Alignment.MID, 10f);
        int sp = 0;
        try { sp = Global.getSector().getPlayerStats().getStoryPoints(); } catch (Throwable ignored) { }
        smodsBox = box(t, "Build in s-mods with story points", "smods", JAOFleetOptions.smods());
        t.addPara(esc("You've got " + sp + " story points and it always keeps " + JAOSettings.smodReserve + " of them (Story points to keep in the settings). Frigates get at most "
                + JAOSettings.smodMaxFrigate + " s-mods and destroyers " + JAOSettings.smodMaxDestroyer + (JAOSettings.smodBySize ? ", and smaller ships need a bigger gain before they get one" : "") + "."), 0f, g);
        boolean here = JAOFleetOptions.changeSmodsHere(market, mode);
        changeBox = box(t, "Change s-mods with credits", "change", JAOFleetOptions.changeSmods());
        t.addPara(esc(here ? "This uses the game's own Change s-mods at your colony, which runs " + Misc.getDGSCredits(JAOFleetOptions.changePrice(com.fs.starfarer.api.combat.ShipAPI.HullSize.FRIGATE))
                + " to " + Misc.getDGSCredits(JAOFleetOptions.changePrice(com.fs.starfarer.api.combat.ShipAPI.HullSize.CAPITAL_SHIP))
                + " per s-mod removed or added depending on hull size. A change has to make the ship at least " + JAOSMods.pct(JAOSettings.smodChangeMinGain) + " stronger (more on small ships), so it gets used pretty sparingly."
                : "This only works at your own colony with heavy industry."), 0f, g);

        t.addSectionHeading("Officers and augments", Alignment.MID, 10f);
        officersBox = box(t, "Place officers", "officers", JAOFleetApply.placeOfficers());
        t.addPara("Officers move to wherever they help the fleet the most, and bigger ships count for more. You and any AI cores stay put.", 0f, g);
        augmentsBox = null;
        if (JAOEsr.present()) {
            augmentsBox = box(t, "Place augments", "augments", JAOAugments.place());
            t.addPara("Extra Systems augments go to whichever ship they help the most. This only works at a friendly market with a spaceport.", 0f, g);
        }
        place(t, 10f, 10f);

        TooltipMakerAPI buttons = element(W - 20f, 40f, false, 10f, H - 50f);
        ButtonAPI plan = buttons.addButton("Plan fleet", "plan", 220f, 30f, 0f);
        ButtonAPI c = buttons.addButton("Close", "close", 140f, 30f, 0f);
        c.getPosition().rightOfTop(plan, 10f);
        if (analysis != null) {
            ButtonAPI a = buttons.addButton("Fleet analysis", "analysis", 160f, 30f, 0f);
            a.getPosition().rightOfTop(c, 10f);
        }
    }

    private static ButtonAPI box(TooltipMakerAPI t, String label, String id, boolean on) {
        ButtonAPI b = t.addCheckbox(420f, 22f, label, id, ButtonAPI.UICheckboxSize.SMALL, 4f);
        b.setChecked(on);
        return b;
    }

    // ------------------------------------------------------------------ the plan

    private void buildPlan() {
        Color h = Misc.getHighlightColor(), g = Misc.getGrayColor(), bad = Misc.getNegativeHighlightColor();
        TooltipMakerAPI head = element(W - 20f, 60f, false, 10f, 10f);
        head.addSectionHeading(title(), Alignment.MID, 0f);
        if (job == null || failed) {
            head.addPara("Couldn't plan the fleet (see starsector.log).", 6f, bad);
            planButtons(false);
            return;
        }
        int planned = 0;
        for (JAOFleetPlan.ShipPlan s : job.fleet.ships) if (s.plan != null) planned++;
        head.addPara("Planned every combat ship together: %s ships, and %s of them change.", 6f, h, "" + planned, "" + job.changedShips);

        TooltipMakerAPI list = scroller(W - 20f, H - 170f);
        Color text = Global.getSettings().getColor("standardTextColor");
        list.addPara("Summary", 0f, h);
        list.addPara(esc(JAOOrderPanel.mixLine(job.before, job.fleet.mix())), 2f, g);
        String from = job.storage != null ? "fleet cargo and your storage at " + job.market.getName() + " (" + job.fromStorage + " items from storage)"
                : "fleet cargo" + (job.storageOff ? " (Use storage here is off)" : job.market == null ? "" : " (you don't have storage here)");
        list.addPara("Weapons and LPCs: what the ships carry, plus " + esc(from) + ". Anything that comes off and doesn't get used again goes to "
                + (job.storage != null ? "that storage." : "cargo."), 2f, g);
        if (job.buyLegal || job.buyBlack) {
            if (job.buyItems > 0) list.addPara("Shops: %s weapons and LPCs for %s.", 2f, h, "" + job.buyItems, Misc.getDGSCredits((float) job.buyTotal));
            else list.addPara(job.shops.isEmpty() ? "Shops: none here will sell to you." : "Shops: nothing worth its price.", 2f, g);
        }
        if (job.creditLimit > 0f) list.addPara("Credit limit: %s, and the plan spends %s.", 2f, h,
                Misc.getDGSCredits(job.creditLimit), Misc.getDGSCredits((float) (job.buyTotal + job.changeTotal)));
        int sp = Global.getSector().getPlayerStats().getStoryPoints();
        if (!job.smods) list.addPara("S-mods: none built (Build in s-mods is off).", 2f, g);
        else if (job.storyPoints > 0) list.addPara("S-mods: %s to build in, one story point each (you have %s, keeping %s" + (JAOSettings.smodFleetMax > 0 ? ", at most " + JAOSettings.smodFleetMax + " per Optimize fleet" : "") + ").", 2f, h,
                "" + job.storyPoints, "" + sp, "" + JAOSettings.smodReserve);
        else list.addPara("S-mods: none to build (ships full, no story points above the %s kept" + (JAOSettings.smodFleetMax > 0 ? ", Story points per Optimize fleet at " + JAOSettings.smodFleetMax : "") + ", or nothing worth one).", 2f, g, h, "" + JAOSettings.smodReserve);
        if (job.changeOn) {
            if (!job.changeHere) list.addPara("S-mod changes: only at your own colony with heavy industry.", 2f, g);
            else if (job.smodChanges > 0) list.addPara("S-mod changes: %s for %s.", 2f, h, "" + job.smodChanges, Misc.getDGSCredits((float) job.changeTotal));
            else list.addPara("S-mod changes: none worth the price.", 2f, g);
        }
        StringBuilder notes = new StringBuilder();
        int dmodShips = 0;
        for (JAOFleetPlan.ShipPlan s : job.fleet.ships) if (!s.civilian && dmods(s.member) > 0) dmodShips++;
        if (dmodShips > 0) notes.append(dmodShips).append(dmodShips == 1 ? " ship has" : " ships have")
                .append(" d-mods, so each one gets fitted around its own armor, flux, speed, max CR, flight deck and turrets. ");
        if (job.civilian > 0) notes.append(job.civilian).append(" civilian ships get the logistics fit (campaign hullmods, no weapons, vents and capacitors only with OP left over")
                .append(job.smods && JAOSettings.civilianSmods ? "; cruisers, capitals and salvage rigs build in fleet-wide ones). " : "). ");
        if (job.fleet.locked > 0) notes.append(job.fleet.locked).append(job.fleet.locked == 1
                ? " locked ship stays as it is, and what it carries doesn't get used on other ships"
                : " locked ships stay as they are, and what they carry doesn't get used on other ships").append(" (you can lock or unlock on the refit screen). ");
        if (job.fleet.failed > 0) {
            // 0.5.60: a ship that failed on a later pass keeps the plan of an earlier one
            int stay = 0, earlier = 0;
            for (JAOFleetPlan.ShipPlan s : job.fleet.ships) if (s.failed) { if (s.plan == null) stay++; else earlier++; }
            if (stay > 0) notes.append(stay).append(stay == 1 ? " ship couldn't be planned and stays as it is" : " ships couldn't be planned and stay as they are").append(" (see starsector.log). ");
            if (earlier > 0) notes.append(earlier).append(earlier == 1 ? " ship keeps" : " ships keep").append(" the plan of an earlier pass (see starsector.log). ");
        }
        for (JAOFleetPlan.ShipPlan s : job.fleet.ships) if (s.keep) notes.append(s.name).append(" stays as it is, since it's the ship on the refit screen and it still has its stock loadout (show another ship first, or change something on it by hand once). ");
        if (job.crShips > 0) notes.append("Refitting in space costs CR, same as on the refit screen, so ").append(job.crShips)
                .append(job.crShips == 1 ? " ship loses " : " ships lose ").append("up to ").append(Math.round(job.crMax * 100f))
                .append("% (shown per ship). Refits at a market don't cost CR. ");
        if (notes.length() > 0) list.addPara(esc(notes.toString().trim()), 2f, g);
        if (job.officers != null) {
            int moves = job.officerMoves();
            if (moves > 0) list.addPara("Officers: %s move to wherever they help the fleet the most. You and any AI cores stay put.", 2f, h, "" + moves);
            else list.addPara("Officers: every officer's already where they help the fleet the most.", 2f, g);
        }
        if (job.augments != null && job.augments.any()) {
            int moves = job.augmentMoves();
            if (moves > 0) list.addPara("Augments: %s ships change augments, and each one goes to whichever ship it helps the most.", 2f, h, "" + moves);
            else list.addPara("Augments: every augment's already on the ship it helps the most.", 2f, g);
            if (!job.augments.unused.isEmpty()) list.addPara(esc("Augments not placed: " + String.join(", ", job.augments.unused) + ". They'll stay in cargo or storage."), 2f, g);
        } else if (job.augmentsAway) {
            list.addPara("Augments: they only move at a friendly market with a spaceport (Extra Systems bay).", 2f, g);
        }
        if (!job.stored.isEmpty()) {
            list.addPara("Stored ships: %s ships in storage here give up %s weapons and LPCs, and whatever the fleet doesn't use goes to storage.", 2f, h, "" + job.stored.size(), "" + job.storedItems);
        } else if (job.storedAway) {
            list.addPara("Stored ships: they only get stripped at a market where you have storage.", 2f, g);
        }

        if (job.officers != null && !job.officers.moves.isEmpty()) {
            list.addPara("Officers", 8f, h);
            for (String m : job.officers.moves) list.addPara("    " + esc(m), 0f, text);
        }
        if (job.augments != null && !job.augments.moves.isEmpty()) {
            list.addPara("Augments", 8f, h);
            for (String m : job.augments.moves) list.addPara("    " + esc(m), 0f, text);
        }
        list.addPara("Ships", 8f, h);
        int same = 0;
        for (JAOFleetApply.Change c : job.changes) {
            if (!c.any()) { if (!c.ship.locked) same++; continue; }
            List<String> parts = new ArrayList<>();
            if (c.weapons > 0) parts.add(c.weapons + " weapon" + (c.weapons > 1 ? "s" : ""));
            if (c.wings > 0) parts.add(c.wings + " wing" + (c.wings > 1 ? "s" : ""));
            if (c.bought > 0) parts.add(c.bought + " bought");
            for (String m : c.added) parts.add("+" + m);
            for (String m : c.removed) parts.add("-" + m);
            if (!c.smods.isEmpty()) parts.add("s-mod " + String.join(", ", c.smods));
            if (!c.smodsRemoved.isEmpty()) parts.add("s-mod off " + String.join(", ", c.smodsRemoved));
            if (!c.smodsBought.isEmpty()) parts.add("s-mod on " + String.join(", ", c.smodsBought) + " (credits)");
            if (parts.isEmpty() && c.flux > 0) parts.add("vents/capacitors");
            if (c.cr >= 0.005f) parts.add("CR -" + Math.round(c.cr * 100f) + "%");
            // highlight arguments go in as they are (String.format doesn't read them): "CR -12%" needs no escaping
            list.addPara("%s  %s", 3f, new Color[]{h, text}, c.ship.name, String.join(", ", parts));
            for (String n : c.ship.changeNotes) list.addPara("    " + esc(n) + ".", 0f, g);
            for (String n : c.ship.notes) list.addPara("    " + esc(n) + ".", 0f, g);
        }
        if (same > 0) list.addPara(same + " ships already match the plan.", 6f, g);
        // notes on ships the plan doesn't change (a Guru with no plague LPCs to fly)
        for (JAOFleetApply.Change c : job.changes) {
            if (c.any()) continue;
            if (c.ship.locked) list.addPara("%s  %s", 3f, new Color[]{h, g}, c.ship.name, "locked: left as it is.");
            for (String n : c.ship.notes) list.addPara("%s  %s", 3f, new Color[]{h, g}, c.ship.name, n + ".");
        }
        place(list, 10f, 75f);
        planButtons(job.changedShips > 0 || job.officerMoves() > 0 || job.augmentMoves() > 0 || !job.stored.isEmpty());
    }

    private void planButtons(boolean canApply) {
        TooltipMakerAPI buttons = element(W - 20f, 40f, false, 10f, H - 50f);
        apply = buttons.addButton("Apply to all ships", "apply", 220f, 30f, 0f);
        apply.setEnabled(canApply && job != null && !failed);
        ButtonAPI s = buttons.addButton("Settings", "settings", 140f, 30f, 0f);
        s.getPosition().rightOfTop(apply, 10f);
        ButtonAPI c = buttons.addButton("Close", "close", 140f, 30f, 0f);
        c.getPosition().rightOfTop(s, 10f);
        if (analysis != null) {
            ButtonAPI a = buttons.addButton("Fleet analysis", "analysis", 160f, 30f, 0f);
            a.getPosition().rightOfTop(c, 10f);
        }
    }

    /** D-mods on the ship (hullmods tagged dmod). */
    static int dmods(com.fs.starfarer.api.fleet.FleetMemberAPI m) {
        int n = 0;
        try {
            for (String id : m.getVariant().getHullMods()) {
                com.fs.starfarer.api.loading.HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
                if (spec != null && spec.hasTag("dmod")) n++;
            }
        } catch (Throwable ignored) { }
        return n;
    }

    @Override void closing() { cancelJob(); }

    private void cancelJob() {
        if (job != null) {
            try { job.fleet.cancel(); } catch (Throwable ignored) { }
        }
        job = null;
    }

    private void rebuild() {
        status = null;
        apply = null;
        restart();
    }

    @Override
    public void buttonPressed(Object id) {
        if ("close".equals(id)) {
            cancelJob();
            close.run();
        } else if ("analysis".equals(id) && analysis != null) {
            cancelJob();
            analysis.run();
        } else if (setup) {
            if ("plan".equals(id)) {
                setup = false;
                failed = false;
                job = starter.get();
                rebuild();
            } else if ("budgetDown".equals(id) || "budgetUp".equals(id)) {
                JAOFleetOptions.stepBudget("budgetUp".equals(id) ? 1 : -1);
                rebuild();
            } else if ("storage".equals(id) && storageBox != null) JAOFleetOptions.set(JAOFleetOptions.USE_STORAGE, storageBox.isChecked());
            else if ("stored".equals(id) && storedBox != null) JAOFleetApply.setStripStored(storedBox.isChecked());
            else if ("legal".equals(id) && legalBox != null) JAOFleetOptions.set(JAOFleetOptions.BUY_LEGAL, legalBox.isChecked());
            else if ("black".equals(id) && blackBox != null) JAOFleetOptions.set(JAOFleetOptions.BUY_BLACK, blackBox.isChecked());
            else if ("smods".equals(id) && smodsBox != null) JAOFleetOptions.set(JAOFleetOptions.SMODS, smodsBox.isChecked());
            else if ("change".equals(id) && changeBox != null) JAOFleetOptions.set(JAOFleetOptions.CHANGE_SMODS, changeBox.isChecked());
            else if ("officers".equals(id) && officersBox != null) JAOFleetApply.setPlaceOfficers(officersBox.isChecked());
            else if ("augments".equals(id) && augmentsBox != null) JAOAugments.setPlace(augmentsBox.isChecked());
        } else if ("settings".equals(id)) {
            // after Apply the plan is spent; either way the settings come back and Plan fleet starts over
            cancelJob();
            setup = true;
            failed = false;
            rebuild();
        } else if ("apply".equals(id) && apply != null && apply.isEnabled() && job != null) {
            apply.setEnabled(false);
            String msg;
            boolean ok = true;
            try {
                msg = job.apply();
            } catch (Throwable t) {
                Global.getLogger(JAOFleetPanel.class).error("Auto Outfitting: Optimize fleet failed while applying", t);
                msg = "Something went wrong part way (see starsector.log)";
                ok = false;
            }
            if (status == null) status = element(W - 20f, 34f, false, 10f, H - 90f);
            status.addPara(esc(msg) + ".", 0f, ok ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor());
        }
    }
}
