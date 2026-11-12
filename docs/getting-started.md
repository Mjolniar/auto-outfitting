# Getting started

## Install

1. Download the zip from the [latest release](https://github.com/Mjolniar/auto-outfitting/releases/latest).
2. Extract it into `Starsector/mods/` so that `Starsector/mods/Auto Outfitting/mod_info.json` exists.
3. Enable Auto Outfitting in the launcher.

- Saves: The mod can be added or removed mid-game.
- Settings: LunaLib menu, or `data/config/jonahs_auto_outfitting.json`. See [Settings](settings.md).

## Your first fit

1. Open the refit screen and pick a ship.
2. Press Autofit, then click Optimal. The ship shows a preview.
3. Press Confirm to keep it, or close the panel to discard it.

- Result: The same stock gives the same fit.
- Leftover OP: Goes to vents and capacitors (capacitors first on carriers, phase ships, support ships and missile boats).

## The Autofit panel boxes

- Use ordnance from cargo: Uses weapons and fighter LPCs from fleet cargo.
- Use ordnance from storage: Uses storage at the docked market.
- Buy ordnance from market: Can buy from the market. The panel shows the cost.
- Allow black market purchases: Same, from the black market.
- Strip before autofitting: On fits from bare. Off keeps existing parts and fills the rest.
- Other game boxes: Apply only to the game's goals.
- Optimal fit for every goal: Ships with four stock goals have no room for the Optimal card. This box makes any clicked goal use the Optimal fit.
- Build in s-mods (story points, permanent): Off by default. Points are spent only on Confirm. See [S-mods and story points](s-mods.md).

## The fleet buttons

- Optimize fleet, Order fleet weapons, Fleet analysis, Lock this ship: See [The fleet buttons](fleet-tools.md).
- LunaLib: Its refit menu (icon by the OP readout) has Order weapons for fleet.

## Troubleshooting

- Failed fit: The ship gets the game's autofit and the error goes to `starsector.log`.
- Log every fit: Writes scores and reasons to `starsector.log`.
- Bugs: Report in the [issues](https://github.com/Mjolniar/auto-outfitting/issues) with `starsector-core/starsector.log`, the ship and the modlist.
