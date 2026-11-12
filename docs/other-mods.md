# Other mods

It works with any mod list. Weapons, wings, hullmods and hulls are rated from the game as it's loaded, so a new mod's stuff gets rated like everything else from its own numbers. Nothing needs a patch to show up.

Some mods change ships from their own scripts, though, and the fit has to play along. These are the ones it knows about.

## Mods with special handling

| Mod | What it does with it |
|---|---|
| **Treasure Hunt** | Treasure Hunt Package is valued highly on civilian ships, and built in on the ones that stay in your fleet. See [Civilian ships](civilian-ships.md). |
| **Ashes of the Domain: Vaults of Knowledge** | Order fleet weapons goes into AoTD's production and lists the goods it takes from your colonies. See [The fleet buttons](fleet-tools.md#order-fleet-weapons). |
| **Auto Colonies** (0.2.39+) | Its Special project supply plans producers for the goods an AoTD order needs that none of your colonies make. |
| **Seeker** | Plague hulls (the Guru, the other Cultist ships, the Plague Bearers) only fly plague LPCs: Fire Ant, Pillbug and Beetle. Any other wing gets thrown back to cargo by Seeker as soon as the ship shows up, so none is planned there. Plague weapons and LPCs cost max CR on any other hull, priced through the game's CR rules (about 1% for a Coda Launcher on a healthy 70% ship, much more on a ship already short of CR). |
| **Emergent Threats** | Trinity Worlds ships only fly their Nimbus drones. Carriers with an Enhanced Control Node (the Maquech) fly anything, but an empty bay gets Nimbus drones at 10 OP each, and the plan counts that. Kites and Terminators stay in their bays. Slots the mod fills from its own code are left alone. AI Subsystem Integration is valued for the hullmods it unlocks. |
| **Arma Armatura** | Bits stay in their bays. |
| **Yunru** | "Install:" hullmods are never added (they turn permanent or eat AI cores and lobsters). Let's Bayblade! (turn rate x50) is never added. Inverted Reverse Thrusters counts as a hullmod with a drawback (no flying backwards). Gyrojets count for less since they can hit your own ships. |
| **Valkyrian** | The Bryncore's OP discount and +100 range on Valkyrian weapons count. |
| **ARMAA** | Wings that stay by their carrier, like the Gunhed, count as support. |
| **MagicLib** | Weapon interference penalties are read from every mod that sets them. |
| **Random Assortment of Things**, **Vice** and other installers | Alterations, work orders and anything that uses up an item or story point is never added. |
| **LunaLib** | In-game settings menu, plus an Order weapons for fleet entry in its refit menu. |
| **Tahlan Shipworks** | Legio Infernalis hulls get no Unstable Injector, and the Stahlherz and Glanzwurf get no Safety Overrides. Their built-in hullmods remove those. The Manannan is never used: until its quest flag is set, firing it disables it and damages every ship nearby, friendly ones included. Script effects of 31 weapons are counted. Project Silk is rated on the damage its arcs deal, 0.75 of its listed damage. |
| **Diable Avionics** | The Versant and Virtuous weapon selectors sit in built-in slots and are left alone. Script effects of 7 weapons are counted. |
| **Ship Mastery System** | Turn off its "S-mod autofit option" in LunaLib. With it on, Ship Mastery's autofit replaces the Optimal fit on the refit screen. Its s-mod slots count toward the s-mod limit. |
| **Extra System Reloaded (Oddisz Edition)** | Its upgrade hullmod stays on through every refit, and the upgrades count in the scores. |

## Rules for any mod

- **Wings you can't buy or drop** (Nimbus drones, kites, Terminators, Arma bits) stay in the bay they're in. They're never taken off, moved or picked from cargo.
- **Hullmods that convert a ship** or use up items are never added. A free hullmod only goes on when it actually helps.
- **Joke and built-in parts** stay out: weapons priced at 0 OP (a hull's built-in gun or a fighter's) never go in ordinary mounts, and extra speed and handling only count up to half again the speed and twice the handling.
- **Cheat-only items** (weapons and LPCs with no blueprint, that no faction knows and no ship carries, like what console `allweapons` adds) are left out unless you turn on **Use items you can only get with cheats**. Omega, Threat and Dweller gear always counts.
- **Hullmod scripts run first.** Before any planned ship is refitted, its hullmods' scripts run once on a copy. Drones they add go into the plan, and the OP they take comes out of vents and capacitors.

## Data files

All in `data/config/` in the mod folder. You can edit them, and the last three can be extended by other mods shipping a file at the same path.

| File | What's in it | Other mods can add rows |
|---|---|---|
| `jao_rules.csv` | The rules above: slots filled by scripts, hullmods to never add or remove, plague costs, bay-only wings, hull roles and so on. The file's header explains each kind. | No, edit this mod's copy |
| `jao_weapon_effects.csv` | Script effects the plain stats miss: EMP arcs, extra damage per hit, extra armor damage, shield debuffs, payload beams, and weapons whose real damage or flux differs from their listed numbers. | Yes |
| `jao_weapon_tiers.csv` | The community AI tier for each vanilla weapon, used by **Lean on community weapon ratings**. | Yes |
| `jao_hullmods.csv` | What each hullmod is good for and when. | Yes |

Some handy rule kinds in `jao_rules.csv`:

| Kind | Example | Does |
|---|---|---|
| `never_use` | `never_use,stardust_*` | The Optimal fit never picks these weapons (same as the **Never use these weapons** setting). |
| `never_add` | `never_add,rat_*` | Never adds these hullmods. |
| `not_on_phase` | `not_on_phase,heavyarmor` | Never puts these hullmods on phase ships. |
| `min_size` | `min_size,targetingunit,DESTROYER` | Smallest hull size a hullmod is worth on (this one keeps ITU off frigates). |
| `removed_by` | `removed_by,tahlan_lirefit,unstable_injector` | Hulls with the first hullmod built in never get the second one. The first one's script removes it. |
| `role` | `role,<hull id>,Carrier` | Forces a hull's fleet role. |

Next up: [Settings](settings.md).
