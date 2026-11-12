# Patch notes

Every version, newest first. 0.2.1 was the first one on GitHub.

## 0.5.24 - 2026-11-12

Starsector 0.98.5a balance and rule changes. The same jar works on 0.98a and 0.98.5a.

- **Phase Anchor:** its value follows the game's dissipation and weapon recharge bonus while phased. 0.98a: x2, score bonus 0.12. 0.98.5a: x1.5, score bonus 0.09. Adaptive Phase Coils stays at 0.10.
- **Crew salary:** with Allow hullmods with drawbacks off, a hullmod that raises a ship's crew salary or crew losses counts as a drawback. On 0.98.5a this covers Safety Overrides (doubled crew salary, extra crew losses by peak time used). Hullmods that change the crew a ship needs were already counted.
- **Officers:** an inactive officer counts as no officer, and the ship is fitted for the doctrine personality. 0.98.5a allows more officers than the limit; only the limit are active.
- **Recovery Shuttles:** a Logistics hullmod on 0.98.5a. The mod reads this from the game: it counts toward the logistics limit and stays off combat ships unless logistics hullmods are allowed. It has no combat value in the score.
- **D-mods:** the 0.98.5a numbers for Degraded Shields, Faulty Power Grid, Malfunctioning Comms and Damaged Flight Deck come from the ship's live stats. Weapon durability is not scored, so Damaged Weapon Mounts' new durability penalty has no effect on the fit.
- **Read from the game, no change needed:** hull, weapon and fighter stats; market and production prices, Survival mode multipliers included; skill effects on ship stats, such as Derelict Operations' hull bonus per d-mod.
- **Not scored:** phase cloak cooldown, Phase Coil Instability, Field Modulation, Polarized Plating, Carrier Group, Fighter Uplink, and the new missile and burn drive AI.
- **S-mods:** changing s-mods at a Heavy Industry colony is done in the game's own dialog. The mod does not remove s-mods.
- **Hidden hullmods:** Low Maintenance and Manual Systems are hidden, and the mod leaves them as they are.

## 0.5.23 - 2026-11-12

- **Starsector 0.98.5a:** the mod loads on 0.98.5a. The game's ship interface (ShipAPI) has six new methods in 0.98.5a, and the mod's internal stand-in ship, used to check hullmods on ships away from the refit screen, now has them. Checked by compiling against both 0.98a and 0.98.5a-RC4. It still works on 0.98a.

## 0.5.22 - 2026-11-12

- **New name:** the mod is now called Auto Outfitting. Its folder is `Auto Outfitting`, the zip is `AutoOutfitting.zip`, and the GitHub repository is [Mjolniar/auto-outfitting](https://github.com/Mjolniar/auto-outfitting). Jonah's Colony Manager is now called Auto Colonies.
- **Updating:** delete the old `Jonah's Auto Outfitting` folder in `mods/` before you extract this version. The mod id is unchanged (`jonahs_auto_outfitting`), so saves, LunaLib settings and ship locks keep working.
- **Auto Colonies check:** the order panel's note about Auto Colonies supplying AoTD orders works again. Auto Colonies now numbers its versions with a leading 0 (0.3.0.x), which the version check read as older than 2.39.

## 0.5.21 - 2026-11-11

- **Locked ships:** a new **Lock this ship** button on the refit screen locks the ship shown there. Optimize fleet and Order fleet weapons leave a locked ship as it is, order nothing for it, and don't use what it carries for other ships. It still counts toward the fleet's balance. Press **Unlock this ship** to undo. The Autofit button's Optimal fit still works on locked ships. Locks are saved with the game as a plain list, so removing the mod stays safe. See [The fleet buttons](fleet-tools.md#locked-ships).

## 0.5.20 - 2026-11-11

- **Settings page crash:** opening Auto Outfitting's page in the LunaLib settings menu crashed the game. Two setting descriptions had a single % sign ("Lean on community weapon ratings" and "Rare weapon bonus"). LunaLib reads % as a formatting code, so they now use %%. The build now checks every setting text for this.

## 0.5.19 - 2026-11-11

- **Empty mounts:** an empty or under-used mount now costs up to 1.0 of the fit score, up from 0.5. In the offline check with every mod's weapons in stock, the share of mount size left empty went from 17% to 7%. With vanilla weapons it went from 4% to 1%. The modelled cost is about 1% of offense and survival on the stock reference ships.
- **Tahlan Shipworks and Diable Avionics weapons:** the script effects of 38 weapons are counted: extra damage per hit, explosions, EMP arcs and flux effects. Project Silk is rated on the damage its arcs deal, 0.75 of its listed damage. Its shot itself deals no damage.
- **Effects file:** `jao_weapon_effects.csv` has 8 new columns: extraDamage, extraType, extraChance, extraOnShields, extraPerSecond, damageMult, fluxMult and selfFluxPerShot. The file's header explains each one.
- **Tahlan hullmods:** Legio Infernalis ships get no Unstable Injector, and the Stahlherz and Glanzwurf get no Safety Overrides. Their built-in hullmods remove those when the ship is shown, so the OP went unused. New rule kind `removed_by` in `jao_rules.csv`.
- **Manannan (Tahlan):** never used. Until its quest flag is set, firing it in the campaign disables the weapon, deals 50,000 damage to its own ship and sets off an explosion that hits every ship within 10,000, friendly ones included.
- **Ship Mastery System:** turn off its "S-mod autofit option" in the LunaLib settings. With it on, Ship Mastery's autofit replaces the Optimal fit on the refit screen.

## 0.5.18 - 2026-11-06

- **Civilian ships use all their OP.** S-mods and built-in hullmods were being counted against the limit of two logistics hullmods, which the game itself doesn't do. So an Atlas with cargo holds and a drive field built in got Treasure Hunt Package and nothing else, with 40 of its 65 OP unused. The count now matches the game's, skills that raise the limit included.
- **Leftover OP goes to Reinforced Bulkheads, then Blast Doors.** More hull, a disabled ship is almost always recoverable, and fewer crew lost. Still no vents, capacitors or weapons on civilian ships.

## 0.5.17 - 2026-11-06

- **No vents or capacitors on civilian ships.** They never fight, so that OP was wasted.
- **Civilian ships that stay in your fleet build in fleet-wide hullmods.** New setting **Civilian ships: build in fleet-wide hullmods**, on by default. With Build in s-mods ticked, cruisers and capitals like the Atlas and Prometheus, and salvage rigs, build in:
  1. Treasure Hunt Package (with the Treasure Hunt mod)
  2. Surveying Equipment
  3. High Resolution Sensors
  4. Burn speed

  It works in a single refit and in Optimize fleet, and story points are only spent when you confirm. Built-in hullmods don't count against the logistics limit, so cargo, fuel or berths still fit.
- **Treasure Hunt Package moves up** to right after cargo, fuel or berths on every civilian ship.
- Fixed: salvage rigs with a built-in Salvage Gantry weren't recognized as salvage rigs.

## 0.5.16 - 2026-11-06

- **Mining Pods and other free wings are valued at what they actually do.** A wing that costs 0 OP used to be rated as if it cost 10, so a free Mining Pod scored about like a Broadsword and took bays on real carriers. Now it comes out at about 2 OP worth of Xyphos. In the test fleet, Mining Pods went from 6 bays down to 1.
- **Point-defense drones count as point defense.** Drones that sit in front of the carrier with nothing but PD guns (like Mining Pods) count toward point defense, not damage against ships. They still go in a bay when the ship's OP is better spent elsewhere.

## 0.5.15 - 2026-11-06

- **Phase ships are fitted the way experts fit them.** Adaptive Phase Coils and Phase Anchor are valued on phase hulls, and their survival grows with the flux capacity they phase on, so capacitors matter.
- **No Heavy Armor on phase ships.** It's the `not_on_phase` rule in `data/config/jao_rules.csv` if you want to change it.

## 0.5.14 - 2026-11-06

- **Armor is valued the way it works in the game.** It cuts every hit by hit / (hit + armor), so it shrugs off small arms and matters most on ships that already have plenty of it. Before, armor counted as plain hit points and Heavy Armor almost never went on. Now it goes on heavily armored hulls like the Onslaught and Paragon, about as often as in expert builds.
- Fixed: when the search swapped two mounts at once, a copy of a weapon already fitted on another mount could hide a blueprint copy of the same weapon.

## 0.5.13 - 2026-11-06

- **Hardened Shields gets its due.** Fire on a ship's shield fills its flux until the AI backs off to vent, and fits now count how long a ship stays in the fight. Hardened Shields, capacitors and faster venting all keep it engaged longer. Before, Hardened Shields only counted as extra shield HP, so it lost to Stabilized Shields and Flux Distributor almost everywhere. Experts call it the safe default on shielded ships, and the mod now agrees.
- **A sturdier search.** It runs twice, biggest mounts first and then smallest first, and keeps the better fit. It also tries pairs of big mounts and trades between hullmods, so one early pick can't lock a ship into a weaker loadout. A mount is only left empty when filling it costs more than about 2%.

## 0.5.12 - 2026-11-06

The rest of the research round, plus what expert builds showed once they were put on real ships and compared with the mod's fits.

- **Doctrine personality.** Ships without an officer are fitted for the personality your doctrine's aggression slider gives them (Command > Doctrine), not always Steady.
- **No Integrated Targeting Unit on frigates.** The guides agree it isn't worth its OP there.
- **Shield Conversion - Omni** gets a small bonus on slow, low-tech, front-shielded ships (Onslaught, Legion, Dominator, Enforcer). The AI uses the omni shield to block torpedoes and big shells and lets the armor take the rest.
- **Resistant Flux Conduits** counts for its EMP resistance and faster venting, mostly on capitals.
- **Charged guns** like the Autopulse, Thermal Pulse Cannon and Storm Needler count the burst from their stored charges, not just their recharge rate. Expanded Magazines is valued on those ships, since experts take it first there.
- **Broadside weapon groups.** A broadside ship (a Conquest, say) gets each side's matching weapons in their own group, so it fires the side that bears.
- **Missile volleys.** A missile boat's guided launchers are linked to fire as one volley.

## 0.5.11 - 2026-11-06

Loadouts are now scored by how the game's AI actually fights, read from the game's own combat code, plus a round of research into tournaments and the newest 0.98a build guides.

- **Missiles.** HE torpedoes and rockets are priced against armor the same way guns are. Unguided torpedoes only count against slow, big targets, since that's the only time the AI fires them. Enemy point defense shoots weak missiles down (about a third of 50-HP rockets, but few 500-HP Reapers), and free-firing rockets mostly hit raised shields. In the test run, Annihilators fell from 71 mounts to 13 and Harpoons rose from 3 to 26.
- **Shields and flux.** Shields only use 80% of flux capacity, shield upkeep only counts while the shield is up, and AI ships get dissipation to 1.25x their weapons' flux before capacitors.
- **Aiming and point defense.** Only the main weapon group is held back against shields, large slow turrets can't track frigates, and point defense is rated by whether one weapon can kill a missile in time, so Burst PD beats LR PD.
- **Carriers** only escort when about two thirds of their wings are fighters, interceptors or support, the shortest-range wing caps how far all of them strike, and Defensive Targeting Array changes what each role is worth.
- **Ship systems and handling.** Accelerated Ammo Feeder and High Energy Focus count toward the weapons they boost. Extra turning only matters on slow ships that aim hardpoints by turning, so Auxiliary Thrusters stops going on every ship.
- **The search can trade one hullmod for another** when that beats both.
- **New setting: Lean on community weapon ratings** (on). When two weapons score about the same, the one the community rates higher for AI ships wins (Grievous69's 0.98a tier lists). It moves a weapon's value by about 5% at most.

## 0.5.10 - 2026-11-06

- **Fits are priced by each ship's max CR.** Seeker's plague weapons (the Coda Launcher and the rest) used to cost the same whatever the ship's CR. Now each ship's max CR (skills, hullmods, and d-mods like Degraded Life Support, Faulty Automated Systems and Increased Maintenance), minus the plague the fit carries, goes through the game's CR rules: the AI aims worse as CR drops, and there are the 70, 50, 40 and 20% lines. A Coda costs a healthy ship about 1%, more on one already short of CR.
- **D-mods the live stats missed now count.** A carrier's deck (Damaged Flight Deck, Malfunctioning Comms, Defective Manufactory, and Expanded Deck Crew going the other way), turret turning and recoil (Damaged Weapon Mounts, with Advanced Turret Gyros helping point defense), and weapon malfunctions (Ill-Advised Modifications).
- Optimize fleet says how many ships have d-mods, and each fit logs its max CR, deck and d-mods.

## 0.5.9 - 2026-11-06

- **Optimize fleet fits civilian ships too.** Freighters, tankers, liners, salvage and survey ships get their campaign fit: cargo, fuel, burn, sensors, surveying, salvage and similar hullmods, and no weapons. Their weapons go back into the stock for the warships. No s-mods on them yet, that came in 0.5.17.

## 0.5.8 - 2026-11-05

- **Unguided rockets land less often** than guided missiles.
- **Rockets that can hit your own ships** (Yunru's gyrojets, Annihilators, Hammers, Reapers) count for less the harder each one hits.
- **Kinetic missiles into shields** use real hit chances.
- **New setting: Use items you can only get with cheats** (off). Weapons and LPCs that only the console gives you are left out of fits, Optimize fleet and orders.

## 0.5.7 - 2026-11-05

- **A launcher counts for what it can fire in the fight.** It counts for at most what it can fire within the fight length, so extra ammo (Expanded Missile Racks) only pays on launchers that run dry inside the fight. Before, deep slow launchers and missile racks were overrated and won most ships in an all-weapons test.

## 0.5.6 - 2026-11-05

Fixes from an all-weapons test run:

- **Yunru's Let's Bayblade!** (turn rate x50) is never added, and speed and handling only count up to half again the speed and twice the handling.
- **Inverted Reverse Thrusters** counts as a hullmod with a drawback (no flying backwards).
- **0-OP weapons** (built-in and fighter guns) never go in ordinary mounts.

## 0.5.5 - 2026-11-05

- **Every rule in `jao_rules.csv` applies now.** The file was read with the game's merged-spreadsheet call, which only kept the last row of each rule kind. So the Seeker plague-bay rule (the Guru) and most slot, hullmod and interference rules never took effect, and Optimize fleet kept putting Daggers on the Gurus.

## 0.5.4 - 2026-11-05

- **Optimize fleet only builds s-mods with Build in s-mods ticked.** Same box as a single Optimal fit, off by default. Story points are too valuable for one button to spend them all. The separate "Optimize fleet builds s-mods" setting is gone, and the panel and button tooltip say whether s-mods will be built.

## 0.5.3 - 2026-11-05

- **The order panel says what Auto Colonies covers.** Auto Colonies 0.2.39 plans producers only for goods no colony makes. A good your colonies only make a little of just slows the AoTD order.

## 0.5.2 - 2026-11-05

- **Other mods' scripts are respected.** Seeker's plague hulls (the Guru) only fly plague LPCs, Trinity Worlds hulls only Nimbus drones, and wings you can't buy or drop stay in their bays.
- **Hullmod scripts run first.** Each planned ship is checked against its hullmod scripts: drones they add, OP they take.
- **No installer hullmods.** Yunru's Install hullmods and other zero-OP installers are never added.
- **Plague weapons cost max CR** on other hulls.
- **MagicLib weapon interference** is read from every mod.
- **AoTD orders list what they take** from your colonies, and flag what no colony makes (Auto Colonies 0.2.39+ plans producers for those).

## 0.5.1 - 2026-11-05

- Fixed: Optimize fleet left carriers without fighters. Empty bays were read as full and blank LPCs went to storage. The blank ones get cleaned up on load.
- **Optimize fleet has its own button** under Order fleet weapons on the refit screen.

## 0.5.0 - 2026-11-05

The fleet plan.

- **New Optimize fleet button.** Plans every combat ship together from what the ships carry, your cargo and the storage where you're docked, balancing anti-shield, armor-cracking and fighter roles across the fleet. It shows each ship's changes, then refits them all (s-mods with story points, leftovers to storage).
- **Order fleet weapons uses the same plan**, so pressing it again only fills what's missing.
- **Anti-shield measures how fast the fleet fills enemy flux bars**, and fleet and fighter-role balance are much stronger.

## 0.4.1 - 2026-11-03

Community build research folded in:

- **Fighters are rated against the community's picks:** Broadsword, Dagger, Spark or Wasp, Xyphos or Sarissa. The Gunhed and other wings that stay by their carrier count as support.
- **Bombers** are judged by armor or shield damage times how reliably they hit.
- **No interceptors on carriers with bombers.**
- **Line ships carry both kinetic and an armor-cracker.**
- **Valkyrian Bryncore range** is counted.
- **Capacitors first** on carriers and phase ships.

## 0.4.0 - 2026-11-03

- **Fighters are rated against a reference wing per role** (Broadsword for fighters, Dagger for bombers) on firepower, toughness, point defense and refit time, so one cheap wing no longer fills every carrier bay.
- **Optimal balances with the rest of the fleet:** fighter roles, repeated wings, anti-shield against anti-armor.
- **New Fleet analysis panel** on the refit screen.
- **Hullmod weapon-cost discounts count** (Valkyrian Bryncore).
- **Spare OP after Confirm goes into vents and capacitors.**

## 0.3.0 - 2026-11-02

Scoring rework.

- **Real weapons in every mount.** No 1-OP fillers or small guns in big mounts, and point defense in proportion.
- **Coherent loadouts.** Matched weapon sets in one range band, within the game's 7 weapon groups.
- **Burst windows.** Guns past the ship's sustained flux still count for the bursts they can fire from flux capacity.
- **Corrected numbers** for burst beams, MIRVs, DEM missiles, linked barrels and the Antimatter Blaster.

## 0.2.1 - 2026-11-02

First release.

- **The Optimal goal** on the refit screen's autofit list builds the strongest loadout from what you own.
- **Optional s-mods**, with story points only spent on Confirm.
- **Order weapons for fleet** button: queues what your fleet is missing in custom production or Ashes of the Domain's.
- No required mods. LunaLib adds the settings menu, otherwise it's `data/config/jonahs_auto_outfitting.json`.
