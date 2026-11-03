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

## Fleet production

The refit screen has an **Order weapons for fleet** button (with LunaLib it's also in LunaLib's refit menu, the small icon by the OP readout). It plans the Optimal fit of every ship in your fleet as if every weapon and fighter blueprint you know were in stock (biggest ships first, what you own used first), lists what custom production would have to build with cost and the ships using each item, and adds it to custom production when you press **Queue all**. With Ashes of the Domain: Vaults of Knowledge the order goes into AoTD's production (its Custom production screen; each unit is paid when it's finished and AoTD needs its resources delivered); without it, into the game's custom production (paid each month as items are built; needs Heavy Industry or Orbital Works). Nothing is charged when you press Queue all.

## Fleet analysis

The **Fleet analysis** button sits under Order fleet weapons on the refit screen. It shows:
- your fleet's firepower by damage type, and how much of it can crack armor;
- fighter bays by role, with the most common wings;
- recommendations: which ships to refit for anti-shield or anti-armor, fighter roles leaning one way, ships short of point defense, mixed range bands and empty mounts;
- one line per ship.

## Settings

LunaLib's settings menu, or `data/config/jonahs_auto_outfitting.json` without LunaLib.

| Setting | Default | What it does |
|---|---|---|
| Add the Optimal goal | on | The Optimal entry in every ship's autofit list. |
| Fit for each ship's fleet role | on | Builds each ship for its job: skirmisher frigates, flankers, line cruisers, capital anchors, carriers, battle carriers, support, phase, missile boats. |
| Civilian ships: fit for the campaign | on | Freighters, tankers, liners, salvage and survey ships get no weapons and campaign hullmods (cargo/fuel/berths, burn, sensors, profile, survey, salvage, treasure hunting, supplies); built in on cruisers and capitals with Build in s-mods. |
| Balance with the fleet | on | Fighter roles and wings the fleet already has count for less, and with Auto or Balanced each ship leans toward the damage the fleet is short of. |
| Build style | Auto | Auto, Balanced, Anti-shield, Anti-armor, Hull killer. |
| One goal per style | off | Adds an Optimal goal for each style, so ships can differ. |
| Range | Auto | Auto, Long, Close. |
| Fight length | 90 s | Short fights favour burst and missiles; long ones favour flux efficiency. |
| Value special weapons | on | Counts scripted effects on top of the plain numbers. |
| Prefer rare weapons | off | Rarer weapons get a bonus (Rare weapon bonus, 15%) on top of their measured strength. |
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
| Order weapons for fleet button | on | The refit screen production button. |
| Production budget | 50% | Share of your credits a fleet order may cost. |
| Log every fit | off | Scores and reasons in starsector.log. |

## Requirements

Starsector 0.98a. LunaLib optional.
