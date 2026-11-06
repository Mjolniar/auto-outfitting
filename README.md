# Jonah's Auto Outfitting

> [!WARNING]
> **AI-generated.** This mod was written with an AI assistant. Work in progress: it fits ships, but its scoring is still being
> tuned, and not every change has been tested in game. Report problems as [issues](https://github.com/Mjolniar/jonahs-auto-outfitting/issues),
> with `starsector-core/starsector.log` if you can.

Adds an **Optimal** goal to the refit screen's autofit list. Picking it builds the strongest loadout the ship can have from what you actually own (cargo, storage, and the market if you tick those boxes in the autofit panel):

- Weapons are scored on real combat numbers: damage against shields, armor and hull, flux cost, range, hit strength against armor, ammo, and scripted special effects. OP cost and the game's autofit tiers aren't used to rank weapons.
- The loadout is balanced to finish a kill: break the shield, strip the armor, kill the hull. Build styles can lean on one layer instead.
- Guns past the ship's sustained flux still count: they fire in burst windows (overloads, flanks) paid from flux capacity, so mounts get real weapons, not 1-OP fillers or small guns in big mounts.
- Loadouts stay coherent: matched sets of the same weapon, one range band, within the game's 7 weapon groups. Side and rear mounts that can't reach the main target get point defense or cheap flank cover.
- Hullmods are chosen for the ship and its weapons (range mods only with guns that use them, missile mods only with missiles, and so on), then the remaining OP goes into vents and capacitors.
- Fighters are rated against the community's top pick for their role: Broadsword (fighters), Dagger (bombers), Spark or Wasp (interceptors), Xyphos or Sarissa (support). Each wing is compared on:
  - firepower per OP (for bombers, armor damage or shield breaking, times how reliably the payload hits);
  - toughness per OP;
  - point defense;
  - sortie time (refit plus the trip out and back).

  A wing can match the reference on each but not beat it, so no cheap wing takes every bay. Wings that stay by their carrier (Sarissa, ARMAA Gunhed) count as support.
- Carriers that carry bombers don't get interceptors: the game never sends a bomber carrier's fighters to escort, so the interceptors couldn't screen it.
- Line ships on the Auto or Balanced style carry both kinetic and an armor-cracker.
- The fleet is treated as one team. Fighter roles the fleet already has plenty of, and wings already flying elsewhere, count for less. With the Auto or Balanced style, each ship leans toward the damage the fleet is short of (anti-shield or anti-armor).
- Weapon OP costs are read from the ship's live stats, so hullmod discounts count (Valkyrian Bryncore, for example). The Bryncore's +100 range on Valkyrian weapons is counted too.
- After Confirm, any spare OP goes into vents and capacitors: capacitors first on carriers, phase ships, support ships and missile boats.
- Optional: tick **Build in s-mods** in the autofit panel and the Optimal fit also builds in the hullmods worth a story point most, judged by re-optimising the whole ship around each one. Story points are only spent when you press Confirm, never by the preview.
- Every other goal keeps the game's own autofit.

## The fleet plan

Three buttons on the refit screen work on the whole fleet at once: **Order fleet weapons** and **Optimize fleet**, stacked next to Autofit, and **Fleet analysis** in the next free spot (also linked from the Optimize fleet panel).

The first two buttons share one fleet plan, which works like this:
- Every combat ship is planned together, from one shared stock, biggest ships first.
- Each ship leans toward what the rest of the plan lacks:
  - **anti-shield**: how fast the fleet fills enemy flux bars; kinetic counts fully, energy half, HE a quarter, fragmentation an eighth;
  - **armor-cracking**: hits strong enough to get through armor;
  - **fighter roles**: aiming for about 35% fighters, 35% bombers, 10% interceptors and 20% support.
- Then each ship is planned again with all the others in view, at most two more passes. A ship only changes if the new plan is clearly better.
- The plan doesn't depend on what your ships carry now. Every ship is planned from bare, so the same fleet, stock and blueprints always give the same plan.

The plan runs a little each frame with a progress line. A big fleet takes a few seconds.

### Optimize fleet

Plans every combat ship from what you have here: what the ships carry, your fleet cargo, and your storage at the market you're docked at. Nothing is bought.

It lists what changes on each ship: weapons, wings, hullmods on and off, and s-mods with their story points. It also shows the fleet's anti-shield, armor-cracking and fighter mix before and after. **Apply to all ships** then refits every ship:
- Weapons and LPCs come off into cargo, and each ship takes its plan from cargo, then storage.
- What isn't used again goes to that storage.
- Hullmods change only where the refit rules allow (spaceport hullmods only at a spaceport).
- S-mods only while **Build in s-mods** is ticked in the Autofit panel, the same box as a single Optimal fit (off by default). Then each new s-mod costs a story point, never below the story points you keep in reserve, and the panel lists them before you apply.
- Civilian ships with the logistics fit are left alone.

Fleet analysis also has an Optimize fleet button.

### Order fleet weapons

The same fleet plan, but with every weapon and fighter blueprint you know in unlimited supply. It lists what custom production would have to build, with costs and the ships using each item, and adds it when you press **Queue all**.

Owned items count as stock and are used first: cargo, every storage, what's on your ships, and what's already on order. So pressing it again only orders what's still missing. If the order is over the budget, the biggest ships' items are ordered first and the rest is ordered the next time you press it.

With Ashes of the Domain: Vaults of Knowledge, the order goes into AoTD's production (its Custom production screen). Each unit is paid when it's finished, and AoTD takes resources from your colonies every month: Heavy Armaments for every item, Ships for LPCs, Electronic Components for most. The order panel lists them, says which colony makes each, and flags anything no colony makes, since those items wait for it. With Jonah's Colony Manager 2.39 or later, its Special project supply plans a producer for those goods. It only does that for goods no colony makes: a good your colonies make little of just slows the order. Without AoTD, the order goes into the game's custom production: paid each month as items are built, and it needs Heavy Industry or Orbital Works. Nothing is charged when you press Queue all.

### Fleet analysis

Shows:
- your fleet's firepower by damage type, with anti-shield and armor-cracking against their aims;
- fighter bays by role, with the most common wings;
- recommendations: anti-shield or anti-armor shortfalls, fighter roles leaning one way, carriers mixing interceptors with bombers, ships short of point defense, mixed range bands and empty mounts;
- one line per ship.

It changes nothing; its **Optimize fleet** button opens the fleet plan.

## Other mods' rules

Some mods change a ship's loadout from their own scripts. The Optimal fit and the fleet plan follow them:
- **Seeker's plague hulls** (the Guru and the other Cultist ships, the Plague Bearers) only fly Seeker's plague LPCs: Fire Ant, Pillbug and Beetle. Any other wing gets thrown back into cargo as soon as the ship is shown, so none is planned there. Without plague LPCs those bays stay empty, and the plan says so.
- **Plague weapons and LPCs** cost 3%, 6% or 12% max CR each on any other hull (a third less with Hardened Subsystems), and they're scored that way. On plague hulls they're free.
- **Trinity Worlds** ships only fly their Nimbus drones. The carriers with an Enhanced Control Node (the Maquech) fly anything, but an empty bay gets Nimbus drones at 10 OP each, and the plan counts that.
- **Wings you can't buy or drop** (Nimbus drones, Emergent Threats kites and Terminators, Arma bits) stay in the bay they're in. They're never taken off, moved to another ship or picked from cargo.
- **Hullmods that convert a ship** are never added: Yunru's "Install:" hullmods turn into permanent hullmods or use up AI cores and lobsters once the ship is shown. A free hullmod only goes on when it helps.
- **Joke and built-in parts** stay out: Yunru's "Let's Bayblade!" (turn rate x50 on any ship) is never added, and weapons priced at 0 OP (some hull's built-in gun or a fighter's) never go in ordinary mounts. Extra speed and handling only count up to half again the speed and twice the handling.
- **Missiles**: a launcher counts for what it can fire within the fight length. Unguided rockets land less often than guided missiles (slow ones less again), and rockets that can hit your own ships, such as Yunru's gyrojets, Annihilators, Hammers and Reapers, count for less the harder each one hits. That holds against shields as well as armor.
- **Inverted Reverse Thrusters** (Yunru) roughly doubles speed but the ship can't fly backwards, so it counts as a hullmod with a drawback: only with "Allow hullmods with drawbacks" on.
- Before any planned ship is refitted, its hullmods' scripts run once on a copy. Drones they add go into the plan, and the OP they take comes out of vents and capacitors.

The rules live in `data/config/jao_rules.csv`.

## Settings

LunaLib's settings menu, or `data/config/jonahs_auto_outfitting.json` without LunaLib.

| Setting | Default | What it does |
|---|---|---|
| Add the Optimal goal | on | The Optimal entry in every ship's autofit list. |
| Fit for each ship's fleet role | on | Builds each ship for its job: skirmisher frigates, flankers, line cruisers, capital anchors, carriers, battle carriers, support, phase, missile boats. |
| Civilian ships: fit for the campaign | on | Freighters, tankers, liners, salvage and survey ships get no weapons and campaign hullmods (cargo/fuel/berths, burn, sensors, profile, survey, salvage, treasure hunting, supplies); built in on cruisers and capitals with Build in s-mods. |
| Balance with the fleet | on | Fighter roles and wings the fleet already has count for less, and with Auto or Balanced each ship leans toward the damage the fleet is short of: the rest of the fleet plan in Optimize fleet and Order fleet weapons, the fleet as it stands for one Optimal fit. |
| Build style | Auto | Auto, Balanced, Anti-shield, Anti-armor, Hull killer. |
| One goal per style | off | Adds an Optimal goal for each style, so ships can differ. |
| Range | Auto | Auto, Long, Close. |
| Fight length | 90 s | Short fights favour burst and missiles; long ones favour flux efficiency. A launcher counts for at most what it can fire in this time, so more ammo only helps launchers that empty sooner. Raise it if your battles run long. |
| Value special weapons | on | Counts scripted effects on top of the plain numbers. |
| Prefer rare weapons | off | Rarer weapons get a bonus (Rare weapon bonus, 15%) on top of their measured strength. |
| Use items you can only get with cheats | off | Off: weapons and LPCs that have no blueprint, that no NPC faction knows and no ship in any mod carries (what console `allweapons` adds, like Yunru's gyrojets) are left out of fits, Optimize fleet and fleet orders. Omega, Threat and Dweller gear always counts. |
| Respect priority weapons | on | Your faction doctrine's priority marks win ties. |
| Point defense floor | 1 | Minimum point defense for the ship's size. |
| Never use these weapons | (none) | Weapon ids or patterns the Optimal fit skips, e.g. `stardust_*`. |
| Allow hullmods with drawbacks | off | Off: only hullmods that make nothing worse, including supply, recovery and deployment costs. On: drawbacks are weighed against the gains. |
| Safety Overrides | Frigates and destroyers | Never / Frigates and destroyers / When it scores best (needs drawbacks allowed). Counted with its costs: range past 450, peak time, no venting. |
| Allow logistics hullmods | off | Cargo, fuel, sensor and burn hullmods. |
| S-mods per ship | 3 | With the autofit box **Build in s-mods** ticked: most hullmods built in per ship (within its limit). |
| Minimum gain per s-mod | 2% | Each s-mod must make the ship at least this much stronger. |
| Wait for the best hullmods | on | Keep open only the slots a clearly better, not-yet-known hullmod would take; the rest get the best you have. |
| Most days to hold a slot | 30 | After this, the ship builds the best you have in the held slots. |
| How much better to wait for | 10% | |
| Story points to keep | 0 | Never spends below this. |
| Order weapons for fleet button | on | The refit screen's fleet buttons (Order fleet weapons, Fleet analysis, Optimize fleet). |
| Production budget | 50% | Share of your credits one fleet order may cost; the rest of the plan is ordered next time. |
| Log every fit | off | Scores and reasons in starsector.log. |

## Requirements

Starsector 0.98a. LunaLib optional.
