# Other mods

- Any mod: Weapons, wings, hullmods and hulls from any mod are rated from their loaded stats. These mods have special handling.

## Mods with special handling

- Treasure Hunt: Treasure Hunt Package is valued on civilian ships. See [Civilian ships](civilian-ships.md).
- Ashes of the Domain: Vaults of Knowledge: Order fleet weapons uses AoTD production. See [The fleet buttons](fleet-tools.md#order-fleet-weapons).
- Random Assortment of Things: Order fleet weapons orders at a Frontiers settlement with Ordnance Manufacturing. See [The fleet buttons](fleet-tools.md#order-fleet-weapons).
- Seeker: Plague hulls fly only plague LPCs (Fire Ant, Pillbug, Beetle). Plague weapons and LPCs cost max CR on other hulls.
- Emergent Threats: Trinity Worlds ships fly only Nimbus drones. Carriers with an Enhanced Control Node (Maquech) fly anything, and an empty bay gets Nimbus drones at 10 OP each. Kites and Terminators stay. Slots the mod fills itself are left alone.
- Yunru: "Install:" hullmods and Let's Bayblade! are not added. Inverted Reverse Thrusters counts as a drawback. Gyrojets count for less.
- MagicLib: Weapon interference penalties are read from every mod that sets them.
- Random Assortment of Things, Vice and other installers: Alterations, work orders and anything using an item or story point are not added.
- Tahlan Shipworks: Legio Infernalis hulls get no Unstable Injector. Stahlherz and Glanzwurf get no Safety Overrides. The Manannan is not used. Script effects of 31 weapons count. Project Silk is rated at 0.75 of listed damage.
- Diable Avionics: Versant and Virtuous weapon selectors in built-in slots are left alone. Script effects of 7 weapons count.
- Ship Mastery System: Turn off its "S-mod autofit option" in LunaLib, or its autofit replaces Optimal. Its s-mod slots count toward the limit.
- Extra System Reloaded (Oddisz Edition): Its upgrade hullmod stays through refits and counts in scores.
- AI Tweaks: Its five 0-OP hullmods (Finisher Beam Protocol, System Shunt, Custom AI, Search and Destroy, Role: Skirmisher) stay through Strip and Optimize fleet.

## Rules for any mod

- Fixed wings: Nimbus drones, kites, Terminators and Arma bits stay in their bay.
- Converting hullmods: Hullmods that convert a ship or use up items are not added.
- Zero-OP weapons: Not used in ordinary mounts. Extra speed and handling count up to 1.5x and 2x.
- Cheat-only items: Weapons and LPCs without blueprint, faction or ship (console `allweapons`) are left out unless Use items you can only get with cheats is on. Omega, Threat and Dweller gear counts.
- Hullmod scripts: Run once on a copy before refit. Drones they add enter the plan and their OP comes from vents and capacitors.

## Data files

- Location: `data/config/` in the mod folder. Other mods can add rows to the last three by shipping a file at the same path.

- `jao_rules.csv`: Script-filled slots, hullmods not added or removed, plague costs, bay-only wings, hull roles. The header explains each kind. Edit this mod's copy.
- `jao_weapon_effects.csv`: Script effects the stats miss (EMP arcs, per-hit and armor damage, shield debuffs, payload beams, wrong listed damage or flux).
- `jao_weapon_tiers.csv`: Community AI tier of each vanilla weapon.
- `jao_hullmods.csv`: What each hullmod is good for and when.

Rule kinds in `jao_rules.csv`:

| Kind | Example | Effect |
|---|---|---|
| `never_use` | `never_use,stardust_*` | Weapons not picked. |
| `never_add` | `never_add,rat_*` | Hullmods not added. |
| `not_on_phase` | `not_on_phase,heavyarmor` | Hullmods not put on phase ships. |
| `min_size` | `min_size,targetingunit,DESTROYER` | Smallest hull size a hullmod is worth on. |
| `removed_by` | `removed_by,tahlan_lirefit,unstable_injector` | Hulls with the first hullmod do not get the second. |
| `role` | `role,<hull id>,Carrier` | Forces a hull's fleet role. |
