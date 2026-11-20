# Patch notes

## 0.5.75 - 2026-11-20

- Optimize fleet: Added "Remove d-mods" with a box per ship type (frigates, destroyers, cruisers, capitals, civilian), per save and off by default.
- D-mods: Repairs come after the weapons, hullmods, s-mods and s-mod changes, from what's left of the credit limit and the Domain-grade Parts in fleet cargo and storage at the docked market.
- D-mods: Each ship is repaired with whichever is cheaper: parts (the hull's parts per d-mod, at their base price) or a restoration for credits (needs a spaceport).
- D-mods: Ships with the most d-mods times deployment points per cost go first. The ship shown on the refit screen is left out.

## 0.5.74 - 2026-11-19

- Compatibility: Updated for Starsector 0.98.5a-RC8.

## 0.5.73 - 2026-11-19

- Optimize fleet: Added boxes in the S-mods section for which ship types get s-mods: frigates, destroyers, cruisers, capitals and civilian ships. They are saved per save and also apply to Optimal on the refit screen.
- S-mods: A type that is off gets no new s-mods and no s-mod changes with credits. S-mods already on a ship stay.
- Settings: "Civilian ships: build in fleet-wide hullmods" is the starting value of the Civilian box.

## 0.5.72 - 2026-11-19

- Optimize fleet: The planning estimate splits the planner's own time from its share of each frame. A laggy campaign gives the planner less time, and that no longer raises the estimate for later runs.
- Optimize fleet: Ships with modules count the modules' slots.
- Optimize fleet: One run moves the learned speed at most 3 times either way.
- Optimize fleet: The time left counts down with the clock and drops at most 1.5 times faster. Fixed the estimate falling fast once the first ships were done.
- Optimize fleet: The ships done so far correct the estimate gradually.

## 0.5.71 - 2026-11-18

- Auto Colonies: Added a public no-argument constructor to JAOAutofitPlugin, so other mods can create it with getInstanceOfScript. Without a fleet commander the vent and capacitor limits are the base ones.

## 0.5.70 - 2026-11-18

- Stations: Optimal fits on stations (STATION hint) and their modules give speed and handling no value and no evasion, and score against every target size alike.
- Stations: Station cores and modules are fitted as Anchors (carriers stay carriers).
- Auto Colonies: Station fits through the autofit plugin with "jao_optimal_any_goal" ticked. No story points are spent and no cargo is touched on that path.

## 0.5.69 - 2026-11-18

- Place officers: Every officer gets a ship while ships that can take one are free. An officer whose ships all score no gain keeps their own ship, or takes the free ship where they lose least.
- Place officers: Fixed ships of officers dropped from the plan staying marked as taken.
- Place officers: Officers left without a ship are written to starsector.log with the number of ships that can take one.

## 0.5.68 - 2026-11-18

- Optimize fleet: Fixed the time left growing while it plans. Ships not planned yet are scaled by how the ships already planned compared with their estimate.
- Optimize fleet: Fixed the starting estimate. Planning time grows with slots^1.6 per ship, and s-mod planning is no longer counted as the main cost.
- Optimize fleet: The estimate learns one rate per finished run. 0.5.67 learned ship by ship, so the last and smallest ships set the rate.
- Optimize fleet: Expected passes are no longer rounded up to 3.
- Optimize fleet: The estimate and the measured planning time are written to starsector.log.

## 0.5.67 - 2026-11-18

- Optimize fleet: The settings page estimates how long Plan fleet takes, from the ships, their choosable slots, the kinds of weapons and LPCs it can pick from, s-mod planning and officer placement.
- Optimize fleet: The progress line shows the time left while it plans and places officers.
- Optimize fleet: The estimate learns from every finished plan and is kept in the game's common folder (jao_plan_times.json).
- Optimize fleet: Fixed the progress note saying a big fleet takes a few seconds.

## 0.5.66 - 2026-11-18

- Reverse Engineered Private Arsenal: Optimize fleet buys from the Private Arsenal at the docked colony with Legal shops on. With an Omega core in the hub its items cost nothing.
- Reverse Engineered Private Arsenal: Order fleet weapons counts a free Private Arsenal's stock as owned. The Reverse Engineering Storage is never used.
- Navarchy of Tania: Added scripted effects for 6 weapons (Nidhogg and Garra pellets, Agulha Defense System, Acid Howitzer, Purifier, Porcupine Bomb Launcher).
- Navarchy of Tania: Added 22 hullmod rows, 11 ship system rows and 27 rules (hullmods its hulls delete, Combat Autoforge and Battlecruiser Package missile reloads, set effects, roles).
- Navarchy of Tania: S-mods that Ancient Frame deletes are never built in (Integrated Point Defense AI next to Missile Racks and Cargo Holds, Flux Coil Adjunct next to the Solaris set).
- Navarchy of Tania: Change s-mods with credits keeps every s-mod of a complete Ancient Frame set.
- Navarchy of Tania: The Wader gets a warship fit.
- Voidfaring Hubship Start: Added roles for the core and side modules, the Offense Focus system and the drone launchers.
- Weapons: Added a pellets column to jao_weapon_effects.csv (armor sees each pellet, damage per second is unchanged).
- Scoring: Reference targets count each hull design once. Skins and d-hulls are left out.
- Rules: Added the kinds smod_lost, smod_set and warship.

## 0.5.65 - 2026-11-17

- Optimize fleet: Officer placement values an officer by the share the ship's score rises, times the ship's deployment points. Capitals and cruisers get officers ahead of frigates with a similar gain.
- Optimize fleet: The officers note says officers move to where they help the fleet the most.

## 0.5.64 - 2026-11-17

- AI Tweaks: Optimal and Optimize fleet add Custom AI to every cruiser and capital when AI Tweaks is enabled and the hullmod is known. Frigates and destroyers keep what they have. Never built in as an s-mod.
- Settings: Added "Custom AI on cruisers and capitals (AI Tweaks)", on by default.

## 0.5.63 - 2026-11-17

- Optimize fleet: Fixed the settings page shifting right below the credit limit's Less and More buttons and cutting off text at the right edge.

## 0.5.62 - 2026-11-17

- Text: Second voice pass on the LunaLib setting descriptions, tooltips, the Optimize fleet, Order fleet weapons and Fleet analysis panels and the README. Same settings, numbers and ids.

## 0.5.61 - 2026-11-17

- Text: Settings descriptions (LunaLib and the settings file), tooltips, the Optimize fleet, Order fleet weapons and Fleet analysis panels and the README reworded in the author's voice. Same settings, numbers and ids.
- Settings: Civilian ships description says leftover OP goes into vents and capacitors.
- Settings: Minimum gain to buy says Optimize fleet buys only with its shop ticks on.
- Settings: Order weapons for fleet button says turning it off hides only that button.
- Fleet analysis: The interceptors and bombers note says a carrier with too many bombers doesn't send fighters to escort.

## 0.5.60 - 2026-11-16

- Compatibility: Fixed real Nimbus LPCs (IX Revival) being deleted from cargo and storage on save. Only the Nimbus drone wings are removed.
- Optimize fleet: Station modules get their planned vents and capacitors. Modules no longer end up over their OP.
- Optimize fleet: Hullmods that need an item (Fragment Swarm, Shrouded hullmods, Secondary Fabricator) are planned for as many ships as there are items. A ship whose item is gone at Apply skips that hullmod.
- Optimize fleet: Hullmod items come only from fleet cargo when "Use storage here" is off.
- Optimize fleet: The ship on the refit screen stays as it is while it still has its stock loadout. The plan says so.
- Optimize fleet: A ship that can't be planned keeps all its weapons and LPCs. Other ships no longer plan with them.
- Optimize fleet: Closing the panel any way (Escape, a click outside, leaving the refit tab) stops the planning.
- Optimize fleet: Planning that stops part way shows "Couldn't plan the fleet".
- Optimize fleet: The settings page no longer restocks the market's shops each time it is redrawn.
- Order fleet weapons: Closing the panel stops the planning.
- S-mods: The wait timer for better hullmods ends when s-mods are built.
- S-mod changes: A new s-mod only goes on while the ship is at or under its size cap. Hidden and never_remove s-mods are not removed.
- Optimal fit: A stripped Converted Hangar no longer leaves a wing in a bay the ship doesn't have.
- Optimal fit: S-mods already on the ship are not added again as hullmods. Their OP is no longer left unspent.
- Optimal fit: When the fit fails on a hull with no stock goal, the ship goes back to its old loadout.
- Logistics fit: A civilian ship stays within its OP when its s-mods can't be built.
- Carriers: Wings with range 0 stay with the carrier and don't count toward the escort rule.
- Weapons: Mod weapons with infinite or undefined damage figures count as 0.
- Settings: "Optimal goal" off hands the Autofit panel to the game or to other mods.
- Settings: "Order weapons for fleet button" off hides only that button. Optimize fleet, Fleet analysis and Lock stay.

## 0.5.59 - 2026-11-16

- Fleet roles: Added the Brawler role for slow frigates, destroyers and cruisers (Enforcer, Dominator, Monitor and similar). Speed against other loaded ships of the same size now sets the role: slow ships brawl, very fast cruisers flank.
- Fleet roles: Fast ships get survival for outrunning what they fight (Skirmisher most, Flanker less). Phase Skimmer and Phase Teleporter ships count it more, and burn drives and jets add their speed. Plasma Burn doesn't count.
- Fleet roles: Mora, Drover, Gemini and Colossus Mk.III are battle carriers. Monitor and Centurion are escorts. Atlas Mk.II is line artillery.
- Carriers: AI support carriers (Heron, Condor, Astral) take point defense and missiles over guns.
- Flagship: The ship you fly gets a balanced fit. It fights every ship size, carries its own point defense, has kinetic and armor-cracking damage, and keeps its role. A carrier flagship keeps guns. Setting "Balanced flagship", on by default.
- Optimize fleet: The flagship is planned first and gets first pick of the weapons. Special hulls (Ziggurat, Remnant, Omega and other ships no blueprint builds) are planned before others of their size.
- Search: Added extra search starts, each with the most repeated weapon set aside for a fresh fill. Fits were better on 9 of 89 vanilla hulls and worse on none. Fit time is about 20% longer.
- Hullmods: Armored Weapons and Advanced Optics are no longer blocked by the drawback filter for turret turn rate. ECCM no longer counts as a missile range loss.
- Hullmods: High Scatter Amplifier beams deal hard flux, and its damage bonus counts once. Missile Autoloader reloads small missiles only. Makeshift Shield Generator now gives a shield in the model. Accelerated and Extended Shields count more on small ships.
- Hardpoints: Hardpoint aim depends on the ship's turn rate. Auxiliary Thrusters count more on slow hardpoint ships like the Onslaught.
- Checks: 875 automated checks pass.

## 0.5.58 - 2026-11-16

- Optimize fleet: Fixed the settings page staying on screen under the plan after Plan fleet.
- Optimize fleet: Fixed the old page staying on screen after Less, More or Settings.

## 0.5.57 - 2026-11-16

- Optimize fleet: The button opens a settings panel first. Plan fleet plans with those settings. Settings on the plan goes back. Settings are kept per save.
- Optimize fleet: Added "Use storage here". On by default.
- Optimize fleet: Added "Buy from legal shops" and "Buy from black markets". Shops at the docked market are read as the refit screen reads them: open shops only, the shop's own item rules, the refit screen's price with tariff. Black markets include Underworld's Cabal Exchange and Scrapyard. Off by default.
- Optimize fleet: Added a credit limit for shop items and s-mod changes: 10%, 25%, 50%, 75% or 100% of your credits. 25% by default. Bigger ships buy first. A ship that would go over the limit uses what you own.
- Optimize fleet: Added "Build in s-mods with story points". Until it is set on the panel it follows the Autofit panel's box. The Autofit panel's boxes now change only the single-ship Optimal fit.
- Optimize fleet: Added "Change s-mods with credits". It uses the game's Change s-mods at your own colony with heavy industry: 75,000 to 250,000 credits per s-mod removed or added, no story points. A change is made only on a ship with no free s-mod slot, when the ship gets at least 10% stronger (more on small ships) and the price fits the credit limit. Removed s-mods are not built back in with story points. Off by default.
- Optimize fleet: Place officers, Place augments and Strip stored ships moved to the settings panel.
- S-mods: The minimum gain per s-mod is scaled by ship size: x 20 / deployment points, from 0.5x to 4x. A 5 DP frigate needs 4x, a 40 DP capital 0.5x. Setting "S-mods favor bigger ships", on by default.
- S-mods: Frigates get at most 1 s-mod and destroyers at most 2. S-mods already on the ship count. Settings "Most s-mods on a frigate" and "Most s-mods on a destroyer".

## 0.5.56 - 2026-11-16

- Compatibility: Starsector 0.98.5a-RC7.

## 0.5.55 - 2026-11-16

- Extra System Reloaded: Augment moves are confirmed with ESR before items change hands. An augment ESR still lists stays installed, and no copy goes back to cargo.
- Extra System Reloaded: At Apply, nothing moves to a ship that left the fleet, has no free slot or fails ESR's requirement. Nothing is removed while ESR's "Removing destroys the augment" is on.
- Extra System Reloaded: ESR's hullmod is synced again on every ship whose augments changed, after the refit screen saves.
- Extra System Reloaded: Temporal Capacitor's extra charge and charge regen count only for systems with charges. Fire Control Equalizer adds to Ballistic Rangefinder, from the weapon's own range.
- Hullmods: Conflict checks and the Reactive Combat Shields limit read the planned hullmod set, its s-mods and the hull's built-ins.
- Optimize fleet: Stored ships are stripped only if they are still in storage at Apply. A stored ship that can't be read is left as it is.
- Optimize fleet: Augment lines show only when there are augments. "Augments not placed" lists those left in cargo or storage.

## 0.5.54 - 2026-11-15

- Hullmods: Fixed hullmods being added next to an s-mod or built-in hullmod that conflicts with them. Example: Hardened Shields next to an s-modded Prismatic Shield, whose script deletes it and leaves 25 OP unspent.

## 0.5.53 - 2026-11-15

- Optimize fleet: Added "Strip stored ships". With it ticked, the weapons and LPCs on ships in your storage at this market join the stock, and Apply takes them off. What the fleet doesn't use goes to storage as items. Locked ships keep theirs. Off by default, set per save.
- Optimize fleet: The panel's options (Place officers, Place augments, Strip stored ships) are on their own row above the buttons.

## 0.5.52 - 2026-11-15

- Extra System Reloaded (Oddisz Edition): Optimize fleet places augments. Each augment in cargo, in this market's storage or on a warship goes to the ship where it adds the most. Slots, one of each per ship and ESR's install requirements are followed.
- Extra System Reloaded: Augments move only at a friendly market with a spaceport (ESR's Extra Systems bay). With ESR's "Removing destroys the augment" on, installed augments stay and only free slots are filled.
- Extra System Reloaded: "Place augments" in the Optimize fleet panel turns placement on or off for the save. On by default. The panel lists every ship's augment changes before Apply.
- Extra System Reloaded: Fits count each ship's augments: Nanolattice armor repair, Missile Autoforge reloads, the Fire Control Equalizer's range, Overload Damper on shielded ships, and the stat effects of the rest.
- Phase ships: Phase cloak upkeep changes (Phase Coil Tuner and others) are counted.

## 0.5.51 - 2026-11-15

- Shields: Shield damage taken by damage type is counted against a reference mix of incoming fire. Prismatic Shield (kinetic x0.5, HE and fragmentation x2) scores x0.88 on top of its x0.8. Submatter Shield, Solar Shielding, Stardust Core and SotF Daydream hulls also count by type.
- Emergent Threats: Reactive Combat Shields is added only where it works: shield efficiency worse than 0.6 after other shield changes, or an Interdictor Array installed. It showed a warning and did nothing on 0.6 shields (Thorn, Paragon) and with Prismatic Shield.
- Secrets of the Frontier: The Thorn's Hypnagogic Incursion is counted: ballistic and energy ammo regen and rate of fire x10 during the dash, no damage taken. Missiles get no ammo back from it.
- Rules: Added the rule kind shield_eff_min to jao_rules.csv.

## 0.5.50 - 2026-11-15

- Random Assortment of Things: Hyperspatial Javelin and Hyperspatial Dart shots count as homing. They land on every target size, and turret turn rate does not lower their score.
- Random Assortment of Things: Their reload counts x2 while phased on phase-cloak ships, for an estimated 35% of a fight. Other ships get the normal reload.
- Random Assortment of Things: Charge bonuses no longer raise their score. Their scripts fix the charges at 5 (Javelin) and 6 (Dart).
- Weapon effects: Added the columns homing, phaseRegenMult and fixedAmmo to jao_weapon_effects.csv.

## 0.5.49 - 2026-11-15

- Advanced Modifications: Added support for its 23 hullmods. Effects that only show in combat are counted: Infinity Targeting Core range and double damage, hull and armor repair, shield immunity windows, flux bursts, timeflow and damage taken.
- Advanced Modifications: Alpha Subsystem, Delta Subsystem, Weapon Control Supercomputer and Crystallized Armor use up cores or transplutonics on install with no refund. Autofit adds them only to ships that already paid.
- Advanced Modifications: Crystallized Armor counts as removing the shield. Combat-only drawbacks (Crystallized Armor, Galvanized Armor, Nano Hull, Delta Shield) follow "Allow hullmods with drawbacks".
- Rules: Added the rule kinds combat, no_shield, drawback and paid_install to jao_rules.csv.

## 0.5.48 - 2026-11-14

- Civilian ships: OP left after every logistics and survival hullmod goes into vents and capacitors, alternating, up to the hull's limits. Before, it stayed unspent.

## 0.5.47 - 2026-11-14

- Fleet-size skills: The test ship gets the fleet's data, so Automated Ships, Phase Corps, Flux Regulation, Crew Training and Carrier Group scale with the fleet as in the game. They were read at their full bonus.
- Built-in wings: Count in the ship's score (their point defense and role) and in the fleet's wing balance.
- Point defense need: Canister Flak and flare systems lower it (judgment 0.8 to 0.95).
- Flux Shunt (IX and ET): Its 3 s with weapons off is counted next to its venting.

## 0.5.46 - 2026-11-14

- Guns and missiles with charges: Capped at the magazine plus what regenerates in a 90 s fight. Regeneration was counted twice (Autopulse 450, now 350; slow-regenerating missiles 1.5 to 2 times too high).
- Lidar Array: While active, hardpoint ballistic and energy guns fire faster and turrets and beams pause (hardpoints x1.28, the rest x0.66). Also for the Knights of Ludd versions.
- Random Assortment of Things: Chronos conversion and Chronos cores cap non-missile base range at 500/600/700 by size, then x0.8.
- Weapon range hullmods: Seeker lenses (+200/+300/+800 to seeker weapons), Adaptive Trajectory Analyzer (+50%) and Optimized Targeting Core (+75%) on archaic weapons, Dawnstar Lacerator (+200). New rule kind `weapon_range`.
- Hard-flux beams: Added Intrepid's Neutron Autolance, CGR Onslaught's beam and the Dawnstar beams.
- Test fits: The test ship carries the real ship's id. IndEvo Forge World Experiment stats and Starship Legends traits now show, and IndEvo no longer stores an entry per test.
- Emergent Threats: Drones only also when Adaptive Drone Bay is fitted. Drone Tactics exempts a ship only with AI Subsystem Integration as an s-mod or the Abomination Interface.

## 0.5.45 - 2026-11-14

- Beams with charges: Sustained damage is capped at a charge's damage times charges a second. A burst beam spends one per burst.
- Beams with several rays: Armor hit strength is split between the rays (Kaleidoscope 5, Strahlhammer and Fauchard 2).
- Hydra and other MIRVs with DEM warheads: Armor hit strength comes from the warhead's payload beam (250, was 500).
- Hull repair: Field Repair Nanites, Adaptive Entropy Arrester and IX Entropy Arrester count their repair as extra hull. Elite Combat Endurance counts 0.5% of hull a second, at most 2000 or half the hull a fight.
- Reactive Combat Shields: Counted as shield damage taken x0.8.
- Emitter diodes and Laser Collimator: Their beams count as hard flux on shields. They were scored by their penalties only.
- Trinity Worlds: Weapon range x0.9 on its hulls.
- Missile reload systems: Fast Missile Racks and the UAF and A_S-F versions give launchers more volleys a fight (about x1.5).
- Fleet roles: Bays with built-in wings no longer make a ship a carrier (Venture, Apex).
- Rules: Added hull_regen, shield_taken, beams_hard and range_mult.

## 0.5.44 - 2026-11-14

- Phase ships: Fixed 89 hulls with a damper or flak defense (Vanguard, Invictus and others) being scored as phase ships. They can take Heavy Armor and other non-phase hullmods, and their role and survival are scored as normal ships.
- Ship systems: Scored from a new table (`jao_systems.csv`, 79 rows). Lidar Array +35% ballistic and energy range, missile forges extra magazines, fighter feeds and reserve wings, flux shunts, and the mod copies of the vanilla systems. Accelerated Ammo Feeder copies are matched by their script. High Energy Focus is x1.115, was x1.075.
- Officers: The test ship carries the real captain. Ships with an officer no longer get Support Doctrine; officer-only fleet skills (Wolfpack, Carrier Group, Cybernetic Augmentation) count. Officer placement values follow.
- Support Doctrine: Its 1.5 flux a second per weapon OP counts for every weapon of the fit on ships without an officer. It counted only kept weapons.
- Armor: Armor counted for damage reduction follows the ship's effective armor bonus (UAF Supercap x0.5 on 16 hulls, Imperial Armor Package, TBJ Hek).
- Time rate: Ships with a faster time rate (timeflow hulls and hullmods) fire, dissipate and move faster in the score.
- Ammo bonuses: Flat bonuses (RAT Missile Reserves +2) count per 10 rounds. They were read as x3.

## 0.5.43 - 2026-11-14

- United Aurora Federation: Fixed Secondary Focus. UAF's code gives a fixed bonus to every cruiser or capital with it: rate of fire +13%, damage +6.5%, weapon flux -11%, projectile speed +11%. The fitted guns don't change it. 0.5.37 scored it per gun, which was wrong.
- Weapon effects: 55 rows added for A_S-F, SEEKER and Valkyrians weapons.
- Hullmods: 25 rows added or fixed for Arma Armatura, UAF and Tahlan. Arma Armatura rows with no stat effect are no longer used as filler.
- Rules: 12 added (hullmods a hull removes; Tahlan Quantum Link Device stays, its removal takes the CIEVE officer off the ship).
- SEEKER: Glare and Ravager interference set to MILD, matching SEEKER's own settings.

## 0.5.42 - 2026-11-14

- Emergent Threats: Fixed AO's test fits using up Synthesis cores. Adaptive Tactical Core takes one from cargo when an uncaptained ship is checked; AO no longer adds it.
- Weapon EMP: Now valued. EMP on hull hits keeps the target's weapons down for their repair time, which cuts its fire at this ship. Only the share beyond what the damage alone does counts.
- Beams: Soft flux on shields counts after shield efficiency, against 0.6 of the target's dissipation. It was the whole dissipation, which counted one or two beams as nothing against shields.
- Arcs and armor extras: Counted at the share of fire that lands. They were counted in full.
- Breach SRM: Its 250 armor damage per hit now counts. Finite missiles skipped it.
- Effect rows: `softFluxOnShields FALSE` now turns soft flux off.
- Weapon effects: 14 rows added and 5 fixed for vanilla, Threat Tech, Secrets of the Frontier, AotD, IndEvo, armaa, JAA, CFT and Ridiculous Weapons weapons.
- AotD: Entropy Lance is never fitted. The AI fires it only at 80% flux and it shuts off shields and other weapons while firing.
- Emergent Threats: 41 hullmod rows fixed, 27 rules added (hullmods a hull removes, fighter bonuses, Adaptive Drone Bay drones only). Abyssal Entropy Projector scored as 2/2/3/4 arcs of 300 energy every 10 s.

## 0.5.41 - 2026-11-14

- Weapon effects: 112 rows added and 6 fixed from a code read of Tahlan, Diable, Emergent Threats, RAT, Imperium, Stardust, Knights of Ludd, Ship/Weapon Pack, UAF, Yunru, Roider and TTE weapons (`jao_weapon_effects.csv`, 185 rows).
- Tachyon Lance: Fixed its EMP arcs to 0.62 a second. Its firing cycle with the cooldown is 6.5 s.
- Tahlan: Deathgaze, Strahlhammer and Strahlblick per-second effects fixed to their full firing cycle. They were counted 1.7 to 3.5 times too high.
- Tahlan: Manannan is never fitted. On a ship without its unlock it deals 50000 damage to its own ship.
- Hullmods: 63 rows added and 30 fixed (`jao_hullmods.csv`). Combat-only effects are marked so the stats don't count them.
- Rules: 39 rules added: hullmods a hull's own script removes, items AO must not remove (Roider, Yunru Quickstart, SWP Extreme Modifications, Ship Mastery override), fighter effects (`wing_mult`).
- Hullmods: Incompatibility now checks both hullmods' rows.

## 0.5.40 - 2026-11-14

- Hullmods that deal damage: Scored as weapons without a mount (`jao_hullmod_damage.csv`). Shrouded Lens: 75 energy 1/2/3/4 times a second by hull size, range 400, hits missiles too. Shrouded Thunderhead: Rift Lightning of 200/300/400/500 energy on targets the ship's non-beam weapons just hit, rate from the fit's hit rate. Both cost 1 flux per damage.
- Use Shrouded hullmods: Added to the settings, off by default. Off: Shrouded hullmods are never added; ones already on a ship stay. On: they are scored and used, and their +50% crew casualties are accepted. A fleet with a Shrouded hullmod makes the Luddic Path, the Luddic Church and the Knights of Ludd hostile when they see it.
- Optimize fleet and Order fleet weapons: Fixed the game freezing while big fleets are planned. Planning takes turns with the game between frames, so the progress line keeps updating even when one ship takes seconds (IX Paragon: about 20 s).
- Random Assortment of Things: Fixed Optimize fleet and Optimal removing RAT alterations, which lost the installed item.
- Docs: The GitHub page keeps the README and these patch notes. The other guides are removed.

## 0.5.39 - 2026-11-14

- Optimize fleet: Added Place officers, a box in the Optimize fleet panel (off by default, saved per game). It moves each officer to the ship where they add the most to the build score. The fits are planned for the new captains. Changing the box plans the fleet again.
- Place officers: The player, AI cores and unremovable officers stay where they are. Automated, locked and mothballed ships, the flagship and civilian ships are left out.
- Place officers: An officer moves only when the new ship gains more than 3% over staying.

## 0.5.38 - 2026-11-14

- Threat fragment weapons: Scored by the ship's fragment swarm. The swarm holds 20/40/60/100 fragments and regrows 1/2/3/5 a second by hull size. Seeker Fragment uses 2 a second, Kinetic Fragments 1, Unstable Fragment 0.5, Defabrication Swarm 1.2. Weapons past the supply count less.
- Threat fragment weapons: Score nothing on a ship without a fragment swarm, Voltaic Discharge included.
- Kinetic Fragments: Counts the 4 fragments that fly with each missile, 100 kinetic each.
- Fragment Swarm: Never removed, never added and never built in by the mod.
- Abyssal Glare: Counts its rift explosions at the rate on player ships, 100 energy about every 0.5 s.
- Hungering Rift: The game allows 10 rifts at once per battle. Past 3 launchers in the fleet, each launcher counts less.
- Tachyon Lance: Fixed its EMP arcs counting at 4 a second. One burst every 2.5 s gives 1.6 a second.
- Hullmods: Hullmods the game's own autofit never applies are never added.

## 0.5.37 - 2026-11-14

- United Aurora Federation: Fixed Secondary Focus going on ships as spare-OP filler. It is scored from the fit's medium and large guns by slot type.
- Spare OP: Hullmods with no effect on the score are the last choice for leftover OP, after fleet hullmods and bigger weapons.

## 0.5.36 - 2026-11-14

- Emergent Threats: Fixed AI Subsystem Integration being built in on every warship. The mod no longer builds it in. You still can.
- Emergent Threats: Fixed carriers with AI Subsystem Integration built in getting crewed wings (Drone Bay Malfunction). They get uncrewed wings only.
- Rules: Added never_smod and drones_only to `jao_rules.csv`.

## 0.5.35 - 2026-11-14

- Optimize fleet: Spends story points down to Story points to keep. Fixed it stopping at 2 per run.
- Story points per Optimize fleet: Default 0, was 2. 0 = no limit.
- Story points to keep: Default 5, was 3.

## 0.5.34 - 2026-11-14

- Optimize fleet: Fixed civilian ships taking the per-run story points before warships. Warships are planned first.
- S-mod note: Fixed the note naming the story point reserve when Story points per Optimize fleet was the limit.
- Optimize fleet panel: Names the per-run cap when no s-mods are built.

## 0.5.33 - 2026-11-14

- Debug: Added Remove all s-mods and refund story points to the LunaLib menu (F3). Turned on during a game, it removes every s-mod from every ship in the fleet, modules included, and returns one story point per s-mod. Built-in hullmods lose their s-mod bonus. Bonus XP is kept. Runs once per switch to On.

## 0.5.32 - 2026-11-14

- S-mods: Picked for the ship without the d-mods restoration removes. D-mods built into the hull stay (Ill-Advised Modifications on the Colossus Mk.II and Luddic Path hulls). Weapons, vents and ordinary hullmods still count every d-mod.
- Fit log: The s-mod note names the d-mods left out.

## 0.5.31 - 2026-11-14

- Surveying Equipment: Added only while the fleet's survey supply reduction is below the sector's need. The need brings 90% of the planets not yet fully surveyed to the 5-supply floor. A fully surveyed sector needs none.
- Treasure Hunt Package: Added only while the fleet's boost is below 2x the median reward (35 with Treasure Hunt 2.3.1).
- High Resolution Sensors: Added only while one more adds at least half its own range to the fleet's sensor bonus.
- Burn hullmods: Added only on civilian ships slower than the slowest warship.
- Fleet needs: Apply to logistics fits and built-in fleet-wide s-mods, in a single fit and in Optimize fleet. Other mods' hullmods with the same effect follow the same rule.
- Fit log: Lists skipped utility hullmods and the fleet totals.

## 0.5.30 - 2026-11-14

- Story points: With 5 story points and the new defaults, one Optimize fleet builds 2 s-mods and keeps 3.
- S-mods per fit: Renamed from S-mods per ship. Default 1, was 3.
- Story points to keep: Default 3, was 0.
- Story points per Optimize fleet: Added. Default 2. One Optimize fleet spends at most this many, biggest ships first.
- Civilian ships: build in fleet-wide hullmods: Default off, was on.
- Optimize fleet panel: Shows the per-run cap next to the story points kept.
- Existing settings: Values already saved in LunaLib stay as set.

## 0.5.29 - 2026-11-13

- Officer skills: The captain's ship skills count in every fit, elite effects included. Ships without a captain score as before.
- Officer skills: Optimize fleet and Order fleet weapons use each ship's own captain. The flagship uses the player's skills. AI cores on automated ships count as captains.
- Ordnance Expertise: Adds 1.5 dissipation per OP of weapons and wings in the fit, and 20 capacity per OP when elite.
- Point Defense: +50% damage to missiles and fighters and the elite +200 point-defense range raise point-defense value.
- Target Analysis: Damage bonuses by target size shorten the time to kill each target size.
- Energy Weapon Mastery: Energy weapons gain up to +30% damage within 600 range, scored at half flux. The elite -10% energy weapon flux counts.
- Missile Specialization: Ammo, rate of fire, damage and missile hit points count.
- Field Modulation: Shield damage taken counts. The elite hard flux dissipation with shields up raises staying power.
- Systems Expertise: Shorter cooldowns raise the value of Accelerated Ammo Feeder and High Energy Focus. The elite damage reduction counts.
- Helmsmanship, Damage Control, Impact Mitigation, Combat Endurance, Gunnery Implants: Speed, handling, damage taken, peak time, CR, range and recoil effects count.
- Polarized Armor: The 90% armor cap counts. Its speed and EMP resistance count at half hard flux. The elite vent rate counts.
- Not scored: Missile ammo regeneration, damage to and from weapons and engines, overload time, phase cloak upkeep and cooldown, target leading, the elite zero-flux boost, hull regeneration and repairs under fire.
- CR model: CR loss past peak time follows the ship's own rate (Combat Endurance, Fragile Subsystems).
- Fit log: Lists the captain skills applied to each fit.

## 0.5.28 - 2026-11-13

- Compatibility: The game version in mod_info is 0.98.5a-RC4. TriOS no longer marks the mod incompatible. 0.98a still loads it.
- Install: The installed AutoOutfitting.version matches the installed version. TriOS no longer offers an update for a version already installed.

## 0.5.27 - 2026-11-13

- Order fleet weapons: Added Random Assortment of Things settlement orders. A Frontiers settlement with Ordnance Manufacturing takes the biggest ships' items while its budget lasts, at its prices (20% over base), paid when ordered.
- Order fleet weapons: With custom production too, the rest of the order goes there. Without Heavy Industry or Orbital Works, the settlement takes what fits its budget and the rest is ordered next time.
- Order fleet weapons: The settlement's storage and its open orders count as owned stock.
- Order panel: Shows the settlement's share, its budget, its monthly refill and the delivery time. The item list splits into Settlement and Custom production.
- Settings: Added RAT settlement orders (On).

## 0.5.26 - 2026-11-12

- Optimize fleet: Fixed ships off the refit screen skipping the CR loss of an in-space refit. Every changed ship loses the same CR as on the refit screen.
- Optimize fleet: The panel shows each ship's CR loss before Apply to all ships.
- AI Tweaks: Fixed Strip and Optimize fleet removing its five 0-OP hullmods.
- Priority weapons: Fixed Respect priority weapons having no effect. Doctrine priority weapons and fighters win a mount when they score within 1% of the best choice.
- Market prices: Added the setting Minimum gain to buy (1% per 10,000 credits). A market weapon or LPC must make the ship that much stronger than owned items. Survival mode prices weapons x10 and LPCs x5.
- Hullmod data: Fixed 4 duplicate rows in `jao_hullmods.csv` (Expanded Deck Crew, Insulated Engine Assembly, Recovery Shuttles, Solar Shielding).
- Expanded Deck Crew: Fixed it being refused on automated carriers such as the Blinding-class.
- Recovery Shuttles: Fixed the 0.98.5a note. Only automated ships are refused.
- Escort Package: Added a value as an s-mod. 0.98.5a: shield and armor damage taken -20% on destroyers and -10% on cruisers. 0.98a: destroyer shields only.
- CR model: Changed the malfunction thresholds to the game's values for each ship. The fit log names the 0.98.5a risk tier (Low, Serious, Critical).
- Docs: Fixed the design notes on the Auto style and officer skills. Officer skills are not counted in fits.
- README: Lists Starsector 0.98a and 0.98.5a.

## 0.5.25 - 2026-11-12

- Version Checker: Added AutoOutfitting.version. TriOS and MagicLib show the installed and latest version.
- Docs: Rewrote all docs at half length.
- Mod info: Shorter description.

## 0.5.24 - 2026-11-12

- Starsector 0.98.5a: Added support. The same jar works on 0.98a and 0.98.5a.
- Phase Anchor: Value follows the game's dissipation and weapon recharge bonus while phased. 0.98a: x2, score bonus 0.12. 0.98.5a: x1.5, score bonus 0.09. Adaptive Phase Coils stays at 0.10.
- Crew salary: With Allow hullmods with drawbacks off, a hullmod that raises crew salary or crew losses counts as a drawback. On 0.98.5a this covers Safety Overrides.
- D-mods: Degraded Shields, Faulty Power Grid, Malfunctioning Comms and Damaged Flight Deck use the ship's live stats. Weapon durability is not scored.

## 0.5.23 - 2026-11-12

- Starsector 0.98.5a: Fixed loading. The mod's stand-in ship has the six new `ShipAPI` methods.

## 0.5.22 - 2026-11-12

- Name: Renamed to Auto Outfitting. Folder `Auto Outfitting`, zip `AutoOutfitting.zip`, repository [Mjolniar/auto-outfitting](https://github.com/Mjolniar/auto-outfitting). Mod id `jonahs_auto_outfitting` is unchanged, so saves, LunaLib settings and ship locks keep working.
- Updating: Delete the old mod folder in `mods/` before extracting this version.

## 0.5.21 - 2026-11-11

- Locked ships: Added Lock this ship and Unlock this ship buttons on the refit screen. Optimize fleet and Order fleet weapons leave a locked ship as it is and do not use its weapons for other ships.
- Locked ships: It still counts toward the fleet's balance. Optimal fit still works on it.
- Locked ships: See [The fleet buttons](fleet-tools.md#locked-ships).

## 0.5.20 - 2026-11-11

- Settings page: Fixed a crash opening the LunaLib page. "Lean on community weapon ratings" and "Rare weapon bonus" used a single % sign, now %%.

## 0.5.19 - 2026-11-11

- Empty mounts: An empty or under-used mount costs up to 1.0 of the fit score (was 0.5). Mount size left empty with every mod's weapons in stock: 17% to 7%.
- Tahlan Shipworks and Diable Avionics: Added script effects for 38 weapons. Project Silk is rated on its arc damage, 0.75 of listed damage.
- `jao_weapon_effects.csv`: Added columns extraDamage, extraType, extraChance, extraOnShields, extraPerSecond, damageMult, fluxMult and selfFluxPerShot.
- Tahlan hullmods: Legio Infernalis ships get no Unstable Injector. Stahlherz and Glanzwurf get no Safety Overrides. Added rule kind `removed_by` in `jao_rules.csv`.
- Manannan (Tahlan): Never used. Firing it before its quest flag is set deals 50,000 damage to its own ship.
- Ship Mastery System: Turn off its "S-mod autofit option" in LunaLib settings. With it on, its autofit replaces Optimal fit.

## 0.5.18 - 2026-11-06

- Civilian ships: Fixed s-mods and built-in hullmods counting against the limit of two logistics hullmods. Leftover OP goes to Reinforced Bulkheads, then Blast Doors.

## 0.5.17 - 2026-11-06

- Civilian ships: Removed vents and capacitors.
- Civilian ships: Added setting Civilian ships: build in fleet-wide hullmods (on). With Build in s-mods ticked, cruisers, capitals and salvage rigs build in Treasure Hunt Package (with the Treasure Hunt mod), Surveying Equipment, High Resolution Sensors and Burn speed, in that order.
- Treasure Hunt Package: Placed right after cargo, fuel or berths on every civilian ship.
- Salvage rigs: Fixed rigs with a built-in Salvage Gantry not being recognized.

## 0.5.16 - 2026-11-06

- Free wings: A wing that costs 0 OP is valued at what it does. Was rated as 10 OP. Mining Pod bays in the test fleet: 6 to 1.

## 0.5.15 - 2026-11-06

- Phase ships: Adaptive Phase Coils and Phase Anchor are valued on phase hulls. Survival grows with flux capacity.

## 0.5.14 - 2026-11-06

- Armor: Valued as cutting each hit by hit / (hit + armor). Heavy Armor goes on heavily armored hulls such as the Onslaught and Paragon.

## 0.5.13 - 2026-11-06

- Search: Runs twice (biggest mounts first, then smallest first) and keeps the better fit. Tries pairs of big mounts and trades between hullmods. A mount is left empty only when filling it costs more than about 2%.

## 0.5.12 - 2026-11-06

- Doctrine personality: Ships without an officer are fitted for the personality from the doctrine aggression slider.
- Shield Conversion - Omni: Small bonus on slow, low-tech, front-shielded ships.
- Charged guns: Autopulse, Thermal Pulse Cannon and Storm Needler count the burst from stored charges. Expanded Magazines is valued on those ships.

## 0.5.11 - 2026-11-06

- Scoring: Loadouts are scored by how the game's AI fights, read from the game's combat code.
- Missiles: HE torpedoes and rockets are priced against armor like guns. Unguided torpedoes count only against slow, big targets. Enemy point defense shoots down weak missiles.
- Shields and flux: Shields use 80% of flux capacity. AI ships get dissipation to 1.25x their weapons' flux before capacitors.
- Aiming and point defense: Only the main weapon group is held back against shields. Large slow turrets cannot track frigates.
- Carriers: Escort only when about two thirds of wings are fighters, interceptors or support. The shortest-range wing caps strike range.
- Lean on community weapon ratings: Added setting (on). Of two weapons that score about the same, the one rated higher for AI ships (Grievous69's 0.98a tier lists) wins. Moves a value by about 5% at most.

## 0.5.10 - 2026-11-06

- Max CR: Fits are priced by each ship's max CR (skills, hullmods, d-mods), minus the plague the fit carries.
- D-mods: Added Damaged Flight Deck, Malfunctioning Comms, Defective Manufactory, Damaged Weapon Mounts and Ill-Advised Modifications.
- Optimize fleet: Reports how many ships have d-mods.

## 0.5.9 - 2026-11-06

- Optimize fleet: Fits civilian ships with cargo, fuel, burn, sensors, surveying and salvage hullmods and no weapons. Their weapons return to stock.

## 0.5.8 - 2026-11-05

- Use items you can only get with cheats: Added setting (off). Console-only weapons and LPCs are left out of fits, Optimize fleet and orders.

## 0.5.7 - 2026-11-05

- Launchers: Count for at most what they can fire within the fight length.

## 0.5.6 - 2026-11-05

- Yunru's Let's Bayblade!: Never added. Speed and handling count only up to 1.5x speed and 2x handling.
- Inverted Reverse Thrusters: Counts as a hullmod with a drawback.
- 0-OP weapons: Built-in and fighter guns do not go in ordinary mounts.

## 0.5.5 - 2026-11-05

- `jao_rules.csv`: Fixed only the last row of each rule kind applying.

## 0.5.4 - 2026-11-05

- Optimize fleet: Builds s-mods only with Build in s-mods ticked (off by default). Removed the "Optimize fleet builds s-mods" setting.

## 0.5.3 - 2026-11-05

- Order panel: Shows what Auto Colonies covers. Auto Colonies 0.2.39 plans producers only for goods no colony makes.

## 0.5.2 - 2026-11-05

- Other mods' scripts: Seeker plague hulls (the Guru) fly only plague LPCs. Trinity Worlds hulls fly only Nimbus drones. Wings you cannot buy or drop stay in their bays.
- Installer hullmods: Yunru's Install hullmods and other zero-OP installers are not added.
- AoTD orders: List what they take from your colonies and flag what no colony makes.

## 0.5.1 - 2026-11-05

- Optimize fleet: Fixed carriers left without fighters. Added its own button under Order fleet weapons.

## 0.5.0 - 2026-11-05

- Optimize fleet: Added button. Plans every combat ship together from ship contents, cargo and storage where docked, balancing anti-shield, armor-cracking and fighter roles. Refits all ships (s-mods with story points, leftovers to storage).
- Order fleet weapons: Uses the same plan.

## 0.4.1 - 2026-11-03

- Fighters: Rated against community picks (Broadsword, Dagger, Spark or Wasp, Xyphos or Sarissa). Wings that stay by their carrier count as support.
- Carriers and phase ships: Capacitors first.

## 0.4.0 - 2026-11-03

- Fighters: Rated against a reference wing per role (Broadsword for fighters, Dagger for bombers).
- Optimal: Balances fighter roles, repeated wings and anti-shield against anti-armor across the fleet.
- Valkyrian Bryncore: Hullmod weapon-cost discounts count.
- Spare OP after Confirm: Goes into vents and capacitors.

## 0.3.0 - 2026-11-02

- Weapons: Real weapons in every mount. No 1-OP fillers or small guns in big mounts.
- Loadouts: Matched weapon sets in one range band, within the game's 7 weapon groups.

## 0.2.1 - 2026-11-02

- Optimal: Added goal on the refit screen's autofit list.
- S-mods: Added optional s-mods. Story points are spent on Confirm.
- Order weapons for fleet: Added button. Queues missing weapons in custom production or Ashes of the Domain's.
- Requirements: No required mods. LunaLib adds the settings menu. Otherwise use `data/config/jonahs_auto_outfitting.json`.
