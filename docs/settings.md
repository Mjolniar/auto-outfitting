# Settings

With LunaLib, everything is in its settings menu under **Auto Outfitting**. Without it, the same settings are in `data/config/jonahs_auto_outfitting.json` in the mod folder, with a comment on each one. That file is read at startup and on every save load.

The defaults are meant to work as they are. The ones people usually change are **Build style**, **Story points to keep** and **Never use these weapons**.

## Optimal build

| Setting | Default | What it does |
|---|---|---|
| Add the Optimal goal | On | The Optimal entry in every ship's autofit list. Off turns the single-ship fit off. |
| Fit for each ship's fleet role | On | Builds each ship for its job: skirmisher frigates, flankers, line cruisers, capital anchors, carriers, battle carriers, support, phase ships, missile boats. Off: every ship is built as a generic duelist. |
| Civilian ships: fit for the campaign | On | Freighters, tankers, liners, salvage and survey ships get campaign hullmods instead of weapons, vents and capacitors. See [Civilian ships](civilian-ships.md). |
| Civilian ships: build in fleet-wide hullmods | On | With Build in s-mods ticked, civilian cruisers, capitals and salvage rigs build in Treasure Hunt Package, Surveying Equipment, High Resolution Sensors, then burn speed. |
| Balance with the fleet | On | Fighter roles and wings the fleet already has count for less, and on Auto or Balanced each ship leans toward the damage the fleet is short of. |
| Build style | Auto | Auto, Balanced, Anti-shield, Anti-armor or Hull killer. See [How a ship gets fitted](how-it-fits.md#the-idea). |
| One goal per style | Off | Adds an Optimal goal for each style (Optimal (Anti-armor) and so on), so ships can differ. |
| Range | Auto | Auto follows the ship. Long keeps every gun near the longest range. Close favours damage over range. |
| Fight length | 90 s | Short fights favour burst and missiles, long ones favour flux efficiency. A launcher counts for at most what it can fire in this time. Raise it if your battles run long. |

## Weapon and hullmod choices

| Setting | Default | What it does |
|---|---|---|
| Value special weapons | On | Counts scripted effects (EMP arcs, shield piercing, damage over time) on top of the plain numbers. |
| Prefer rare weapons | Off | Rarer weapons get a bonus on top of their measured strength. |
| Rare weapon bonus | 15% | How much the rarest weapon gains with Prefer rare weapons on. |
| Use items you can only get with cheats | Off | Off: weapons and LPCs that only the console gives you are left out. Omega, Threat and Dweller gear always counts. |
| Lean on community weapon ratings | On | Near-ties go to the weapon the community rates higher for AI ships. Moves a weapon's value by about 5% at most. |
| Respect priority weapons | On | Your faction doctrine's priority marks win ties. |
| Point defense floor | 1 | Minimum point defense for the ship's size. 0 means none required. |
| Never use these weapons | (none) | Weapon ids or patterns, separated by commas. `stardust_*` skips every Stardust Ventures weapon. |
| Allow hullmods with drawbacks | Off | Off: only hullmods that make nothing worse, supply, recovery, deployment and crew salary costs included. On: drawbacks are weighed against the gains. |
| Safety Overrides | Frigates and destroyers | Never, Frigates and destroyers, or When it scores best (any hull, needs drawbacks allowed). |
| Allow logistics hullmods | Off | Lets combat ships take cargo, fuel, sensor and burn hullmods. Civilian ships get theirs either way. |

## S-mods (story points)

All of these only matter while **Build in s-mods** is ticked in the Autofit panel. See [S-mods and story points](s-mods.md).

| Setting | Default | What it does |
|---|---|---|
| S-mods per ship | 3 | Most hullmods built in on one ship, within its own s-mod limit. |
| Minimum gain per s-mod | 2% | Each s-mod has to make the ship at least this much stronger. |
| Wait for the best hullmods | On | Keeps slots open for a clearly better hullmod you don't have yet. The rest get the best you have. |
| How much better to wait for | 10% | How much better the missing hullmods have to be before it waits. |
| Most days to hold a slot | 30 | After this, the ship builds the best you have in the held slots. |
| Story points to keep | 0 | Never spends below this. |

## Fleet production

| Setting | Default | What it does |
|---|---|---|
| Order weapons for fleet button | On | The refit screen's fleet buttons: Order fleet weapons, Optimize fleet and Fleet analysis. |
| Production budget | 50% | Share of your credits one fleet order may cost. The rest of the plan is ordered next time. |

## Other

| Setting | Default | What it does |
|---|---|---|
| Log every fit | Off | Writes each fit's scores and reasons to `starsector.log`. |
