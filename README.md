# Auto Outfitting

> [!WARNING]
> **AI-generated.** Mod, docs and build checks written by AI coding agents. Every version is checked offline (compile, bytecode checks, a few hundred test fits on vanilla hulls), but a lot of the newer changes haven't been tried in a real game yet. Expect bugs. If something looks off, throw it in the [issues](https://github.com/Mjolniar/auto-outfitting/issues) with `starsector-core/starsector.log` and your modlist if you can.

The game's autofit doesn't really know which weapons are good. It copies the ship's stock loadout and grabs whatever has the highest tag, so you end up with junk in big mounts and the same wing in every bay. I had Claude Code make this so the autofit button actually builds the strongest ship it can from the stuff I own.

It adds an **Optimal** option to the autofit list on the refit screen. Click it and it works out the best weapons, fighters, hullmods, vents and capacitors for that ship from your cargo and storage (and the market, if you tick that). Everything is scored on real combat numbers and on how the game's AI actually flies the ship, not on OP cost.

There are also fleet buttons that fit your whole fleet together, order the weapons you're missing, and show you where the fleet is weak.

## Install

1. Grab the zip from the [latest release](https://github.com/Mjolniar/auto-outfitting/releases/latest) and extract it into `Starsector/mods/`.
2. Enable **Auto Outfitting** in the launcher. Nothing else is required. LunaLib is optional and gives you an in-game settings menu.

Starsector 0.98a. It's fine to add to an existing save, and fine to take out again.

## Quick start

1. Open the refit screen, pick a ship and hit **Autofit**.
2. Click **Optimal** in the row of goals. The preview shows the new fit, and nothing changes until you press **Confirm**.
3. For the whole fleet at once, use **Optimize fleet** next to the Autofit button. It lists every change first, then **Apply to all ships** does it.
4. Short on good weapons? **Order fleet weapons** lists what custom production would have to build for the best fleet and queues it for you.

S-mods are off unless you tick **Build in s-mods** in the Autofit panel, and story points are only spent when you confirm.

## What it does

- Picks weapons by what they actually do: damage against shields, armor and hull, flux, range, ammo and special effects.
- Fits each ship for its job (skirmisher, line ship, carrier, phase ship, missile boat...) and for its own stats, d-mods and CR included.
- Rates fighter wings against the community's go-to wing for each role, so one cheap wing doesn't take every bay.
- Plans the fleet as a team: enough anti-shield and armor-cracking, a good fighter mix, no twenty copies of the same wing.
- Gives freighters, tankers and salvage ships a campaign fit instead of guns: cargo or fuel, Treasure Hunt Package, burn speed, sensors.
- Follows other mods' own rules (Seeker's plague ships, Emergent Threats' drones, Yunru's installers and more).
- Leaves every other autofit goal alone. Only Optimal is this mod.

## Guides

- **[Getting started](docs/getting-started.md)**: installing, your first fit, the Autofit panel boxes, and what to do if something looks wrong.
- **[How a ship gets fitted](docs/how-it-fits.md)**: what it looks at when it picks weapons, wings and hullmods.
- **[The fleet buttons](docs/fleet-tools.md)**: Optimize fleet, Order fleet weapons and Fleet analysis.
- **[Civilian ships](docs/civilian-ships.md)**: the campaign fit, and building fleet-wide hullmods into your Atlas or Prometheus.
- **[S-mods and story points](docs/s-mods.md)**: when it builds hullmods in, and how to keep it from spending points you want to keep.
- **[Other mods](docs/other-mods.md)**: what it does with the mods it knows about, and the data files you can edit.
- **[Settings](docs/settings.md)**: every setting and what it does.
- **[Patch notes](docs/changelog.md)**: every version so far.

For the code side, the full design notes are in [design.md](docs/design.md). It's long.

## Permissions

Use it however you want. Modpacks, reuploads, forks, copying the whole thing, building on it, all fine, no need to ask or credit me. Just don't sell it. See [LICENSE](LICENSE).

## Building it yourself

JDK 17, sources in `src/`:

```
javac --release 17 -cp "<core jars>;<optional mod jars>" -d build <all .java files under src>
jar cf jars/jonahsautooutfitting.jar -C build data
```

Core jars from `starsector-core`: `starfarer.api.jar`, `starfarer_obf.jar`, `fs.common_obf.jar`, `log4j-1.2.9.jar`, `json.jar`, `lwjgl.jar`, `lwjgl_util.jar`. Optional: LunaLib (plus LazyLib's Kotlin runtime) and Ashes of the Domain (Vaults of Knowledge and Theory of Toolbox).
