# Auto Outfitting

> [!WARNING]
> AI-generated. The mod, docs and build checks were written by AI coding agents. Versions are checked offline (compile, bytecode checks, test fits on vanilla hulls). Newer changes are untested in a real game. Report bugs in the [issues](https://github.com/Mjolniar/auto-outfitting/issues) with `starsector-core/starsector.log` and the modlist.

- Optimal: Adds an Optimal option to the refit screen's autofit list. It picks weapons, fighters, hullmods, vents and capacitors from cargo, storage and optionally the market, scored on combat numbers and how the game's AI flies the ship.
- Optimize fleet: Fits the whole fleet together. The button opens its settings: storage, stored ships, legal shops, black markets, credit limit, s-mods with story points, s-mod changes with credits, officers and augments.
- Order fleet weapons: Orders the missing weapons and LPCs through custom production.
- Fleet analysis: Shows the fleet's weak points.
- Lock this ship: Keeps a ship out of Optimize fleet and fleet orders.
- Settings: In the LunaLib menu (F3) when LunaLib is installed. Each setting has its description there.
- Data files: `data/config/jao_weapon_effects.csv`, `jao_hullmods.csv`, `jao_hullmod_damage.csv`, `jao_systems.csv` and `jao_rules.csv` hold what the stats miss. They can be edited.
- Other autofit goals: Not changed.
- Forum: [topic 36377](https://fractalsoftworks.com/forum/index.php?topic=36377.0).
- Patch notes: [changelog](docs/changelog.md).

## Install

1. Download the zip from the [latest release](https://github.com/Mjolniar/auto-outfitting/releases/latest).
2. Extract it into `Starsector/mods/`. To update, extract the new zip over `Starsector/mods/Auto Outfitting`.
3. Enable Auto Outfitting in the launcher. LunaLib is optional.

- Game version: Starsector 0.98a and 0.98.5a. One jar runs on both.
- Saves: Can be added to or removed from an existing save.

## Permissions

- License: Modpacks, reuploads, forks, copies and derivative work are allowed without asking or credit. Selling is not allowed. See [LICENSE](LICENSE).

## Building

- Requirements: JDK 17, sources in `src/`.
- Command: `javac --release 17 -cp "<core jars>;<optional mod jars>" -d build <all .java files under src>`, then `jar cf jars/jonahsautooutfitting.jar -C build data`.
- Core jars (`starsector-core`): `starfarer.api.jar`, `starfarer_obf.jar`, `fs.common_obf.jar`, `log4j-1.2.9.jar`, `json.jar`, `lwjgl.jar`, `lwjgl_util.jar`.
- Optional jars: LunaLib (with LazyLib's Kotlin runtime), Ashes of the Domain, Random Assortment of Things.
