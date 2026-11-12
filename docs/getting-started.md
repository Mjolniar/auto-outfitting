# Getting started

How to install it, do your first fit, and what the boxes in the Autofit panel do with it. The other guides go deeper on each part.

## Install

1. Download the zip from the [latest release](https://github.com/Mjolniar/jonahs-auto-outfitting/releases/latest) and extract it into `Starsector/mods/`, so you end up with `Starsector/mods/Jonah's Auto Outfitting/mod_info.json`.
2. Enable **Jonah's Auto Outfitting** in the launcher. Nothing else is required.
3. Load your save or start a new one.

Adding it to a save mid-game is fine, and so is taking it out again. The Optimal option only exists while the refit screen is open and is never written into your save.

**Settings** are in LunaLib's settings menu if you have LunaLib. Without it, they're in `data/config/jonahs_auto_outfitting.json` inside the mod folder. The defaults are meant to be fine as they are. See [Settings](settings.md).

## Your first fit

1. Open the refit screen and pick a ship.
2. Hit **Autofit**. The panel shows a row of goals (the ship's stock loadouts). The mod adds one more: **Optimal**.
3. Click **Optimal**. The ship on screen shows the new fit right away, but that's only a preview.
4. Press **Confirm** to keep it, or close the panel to leave the ship as it was.

Clicking it again gives the same fit every time, as long as what you own hasn't changed.

After you confirm, any OP left over goes into vents and capacitors (capacitors first on carriers, phase ships, support ships and missile boats).

## The Autofit panel boxes

The game's own boxes still decide where the parts come from:

| Box | What it does with Optimal |
|---|---|
| Use ordnance from cargo | Uses weapons and fighter LPCs from your fleet's cargo. |
| Use ordnance from storage | Uses your storage at the market you're docked at. |
| Buy ordnance from market | Can buy from the market. The panel shows what it would cost before you confirm. |
| Allow black market purchases | Same, from the black market. |
| Strip before autofitting | On: the ship is fitted from bare. Off: what's already on the ship stays, and it fills the rest. |

The other game boxes (upgrade weapons, always add Reinforced Bulkheads or Blast Doors, randomize) only affect the game's own goals. Optimal decides those things itself.

The mod adds two boxes of its own:

- **Optimal fit for every goal**: some ships already have four stock goals, so there's no room for the Optimal card. Tick this and whichever goal you click gets the Optimal fit instead.
- **Build in s-mods (story points, permanent)**: off by default. With it ticked, Optimal also builds in the hullmods worth a story point most. Points are only spent when you press Confirm. More in [S-mods and story points](s-mods.md).

## The fleet buttons

The refit screen also gets three buttons for the whole fleet:

- **Optimize fleet** fits every ship together from what you have, shows you each change, then applies it.
- **Order fleet weapons** works out what custom production would need to build for the best fleet, and queues it.
- **Fleet analysis** shows where the fleet is weak. It doesn't change anything.
- **Lock this ship** keeps the ship on screen as it is when you use Optimize fleet or Order fleet weapons. Press it again to unlock.

The first two sit next to the Autofit button, and Fleet analysis and Lock this ship go in the next free spots. If you have LunaLib, its refit menu (the small icon by the OP readout) has Order weapons for fleet too. All of it is in [The fleet buttons](fleet-tools.md).

## If something looks wrong

- If an Optimal fit ever fails, the ship gets the game's own autofit instead and the error goes in `starsector.log`. You never get left with a half-stripped ship.
- Turn on **Log every fit** in the settings and every fit writes its scores and reasons to `starsector.log`. That's the easiest way to see why it picked something.
- If it's a bug, throw it in the [issues](https://github.com/Mjolniar/jonahs-auto-outfitting/issues) with `starsector-core/starsector.log`, the ship, and your modlist if you can.

Next up: [How a ship gets fitted](how-it-fits.md), or jump to [The fleet buttons](fleet-tools.md).
