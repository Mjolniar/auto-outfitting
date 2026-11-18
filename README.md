# Auto Outfitting

> [!WARNING]
> AI coding agents wrote this mod, its docs and its checks. If you hit a bug, post it in the [issues](https://github.com/Mjolniar/auto-outfitting/issues) with your `starsector.log` and modlist.

- Optimal: It adds an Optimal option to the refit screen's autofit list, and it fits weapons, fighters, hullmods, vents and capacitors from your cargo, storage and the market if you want.
- Optimize fleet: It fits your whole fleet at once, and you pick its settings before it changes anything.
- Order fleet weapons: It orders the weapons and LPCs the plan is missing through custom production.
- Fleet analysis: It shows where your fleet is weak and doesn't change anything.
- Lock this ship: A locked ship is left out of Optimize fleet and fleet orders.
- Settings: They're in the LunaLib menu (F3), and the CSV files in `data/config/` can be edited too.
- Links: Here's the [forum topic](https://fractalsoftworks.com/forum/index.php?topic=36377.0) and the [patch notes](docs/changelog.md).

## Install

Download the zip from the [latest release](https://github.com/Mjolniar/auto-outfitting/releases/latest), extract it into `Starsector/mods/` (over the old folder to update) and turn it on in the launcher. LunaLib is optional. It runs on Starsector 0.98a and 0.98.5a, and you can add it to a save or take it out at any time.

## License

You can put it in modpacks, reupload it, fork it or build on it without asking or crediting me, just don't sell it. See [LICENSE](LICENSE). To build it, compile `src/` with JDK 17 against the `starsector-core` jars.
