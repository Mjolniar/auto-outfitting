# Auto Outfitting

> [!WARNING]
> Heads up, this is AI-generated. The mod, the docs and the build checks were all written by AI coding agents. If you hit a bug, post it in the [issues](https://github.com/Mjolniar/auto-outfitting/issues) with `starsector-core/starsector.log` and your modlist.

- Optimal: This adds an Optimal option to the refit screen's autofit list. It picks weapons, fighters, hullmods, vents and capacitors from your cargo, storage and the market if you want, and it scores them on combat numbers and how the game's AI actually flies the ship.
- Optimize fleet: This fits your whole fleet together in one go. The button brings up its settings first (storage, stored ships, legal shops, black markets, a credit limit, s-mods with story points, s-mod changes with credits, officers and augments), so you can set it up how you want before it runs anything.
- Order fleet weapons: This orders whatever weapons and LPCs the plan is still missing through custom production.
- Fleet analysis: Shows you where your fleet's weak, and it doesn't change anything.
- Lock this ship: Keeps a ship out of Optimize fleet and fleet orders, so it stays the way you built it.
- Settings: They're in the LunaLib menu (F3) if you have LunaLib, and every setting has a description there.
- Data files: `data/config/jao_weapon_effects.csv`, `jao_hullmods.csv`, `jao_hullmod_damage.csv`, `jao_systems.csv` and `jao_rules.csv` hold the stuff the stats miss, and you can edit them if you want to tweak something.
- Other autofit goals: Those still work the same as in the base game.
- Forum: [topic 36377](https://fractalsoftworks.com/forum/index.php?topic=36377.0).
- Patch notes: [changelog](docs/changelog.md).

## Install

1. Download the zip from the [latest release](https://github.com/Mjolniar/auto-outfitting/releases/latest).
2. Extract it into `Starsector/mods/`. To update, just extract the new zip over `Starsector/mods/Auto Outfitting`.
3. Turn on Auto Outfitting in the launcher. LunaLib is optional.

- Game version: Starsector 0.98a and 0.98.5a. The same jar runs on both.
- Saves: You can add it to an existing save or take it back out whenever you want.

## Permissions

- License: Feel free to put it in modpacks, reupload it, fork it, copy it or build on it, no need to ask or credit me. Just don't sell it. See [LICENSE](LICENSE).

## Building

- Requirements: JDK 17, sources in `src/`.
- Command: `javac --release 17 -cp "<core jars>;<optional mod jars>" -d build <all .java files under src>`, then `jar cf jars/jonahsautooutfitting.jar -C build data`.
- Core jars (`starsector-core`): `starfarer.api.jar`, `starfarer_obf.jar`, `fs.common_obf.jar`, `log4j-1.2.9.jar`, `json.jar`, `lwjgl.jar`, `lwjgl_util.jar`.
- Optional jars: LunaLib (with LazyLib's Kotlin runtime), Ashes of the Domain, Random Assortment of Things.
