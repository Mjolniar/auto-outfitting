# How a ship gets fitted

## The idea

- Scoring: Options are scored by how fast the ship breaks shields, strips armor and kills hull, flown by the game's AI. OP cost and the game's autofit tiers do not rank anything.
- Build style (setting): Auto picks per ship (default). Balanced covers all three layers. Anti-shield, Anti-armor and Hull killer put most firepower into one layer. Line ships on Auto or Balanced carry kinetic and an armor-cracker.
- Fleet role: Read from designation and stats (skirmisher, flanker, line cruiser, capital anchor, carrier, battle carrier, support, phase ship, missile boat). It sets range, firepower against survival, point defense and fighters.
- Fleet balance: Fighter roles and wings already in the fleet count for less. On Auto or Balanced each ship leans toward the damage the fleet lacks.

## Weapons

- Scoring: Damage against shields, armor and hull, flux, range, armor penetration, ammo and scripted effects.
- Loadout: Matched sets, one range band, at most 7 weapon groups.
- Flux: Guns above sustained flux count as burst fire paid from flux capacity.
- Side and rear mounts: Point defense or cheap flank cover when they cannot reach the main target.
- Aim: Only the main group is held back against shields. Large slow turrets do not track frigates.
- Empty mounts: Left empty only if filling makes the ship about 2% worse.
- OP cost: Taken from live ship stats, so hullmod discounts count (Valkyrian Bryncore).
- Charged guns: Autopulse, Thermal Pulse Cannon and Storm Needler count burst from stored charges.
- Community ratings: Grievous69's 0.98a tier lists break near-ties by about 5% at most.
- Priority weapons: Doctrine priority weapons and fighters win a mount when within 1% of the best choice.
- Market weapons: A bought weapon must add 1% per 10,000 credits it costs (setting Minimum gain to buy).

## Missiles

- Launchers: Count what they fire within the fight length (90 seconds by default).
- HE torpedoes and rockets: Priced against armor like guns.
- Unguided torpedoes: Count only against slow, big targets.
- Rockets: Mostly hit raised shields and land less often than guided missiles. Rockets that can hit your ships (Annihilator, Hammer, Reaper, Yunru gyrojets) count for less as each hit gets harder.

## Shields, flux and armor

- AI flux: The AI uses about 80% of its flux on shields. Upkeep counts only while the shield is up. Dissipation is wanted near 1.25x weapon flux before capacitors.
- Armor: Each hit is cut by hit / (hit + armor). Heavy Armor goes on heavily armored hulls (Onslaught, Paragon).

## Point defense

- Rating: By whether one weapon kills a missile in time. Burst PD beats LR PD.
- Floor: Every ship gets the point defense floor for its size (setting, 1 by default).

## Fighters

| Role | Reference wing |
|---|---|
| Fighters | Broadsword |
| Bombers | Dagger |
| Interceptors | Spark or Wasp |
| Support | Xyphos or Sarissa |

- Comparison: Wings are compared with the reference on firepower per OP, toughness per OP, point defense and sortie time. A wing can match but not beat it.
- Free wings: Mining Pods are valued at about 2 OP of Xyphos. Point-defense-only drones count as point defense.
- Carrier-bound wings: Sarissa and ARMAA Gunhed count as support.
- Bomber carriers: No interceptors, since the game does not send their fighters to escort.
- Escorts: Carriers escort only when two thirds of their wings are fighters, interceptors or support. The shortest-range wing caps strike range.
- Fleet mix: About 35% fighters, 35% bombers, 10% interceptors, 20% support. Defensive Targeting Array changes role values.

## Hullmods

- Selection: Matched to the ship and weapons. Remaining OP goes to vents and capacitors.
- Drawbacks: Hullmods with supply, recovery, deployment or crew salary costs or other downsides are used only if allowed in settings.
- Safety Overrides: Considered on frigates and destroyers by default. On 0.98.5a it also doubles crew salary and adds crew losses, counted as drawbacks and not scored.
- Integrated Targeting Unit: Never on frigates.
- Shield Conversion - Omni: Bonus on slow, low-tech, front-shielded ships (Onslaught, Legion, Dominator, Enforcer).
- Phase ships: Adaptive Phase Coils and Phase Anchor are valued. Phase Anchor dissipation while phased: x2 on 0.98a, x1.5 on 0.98.5a. No Heavy Armor.
- Logistics hullmods: Off combat ships unless allowed. See [Civilian ships](civilian-ships.md).

## The ship itself

- Stats: Armor, hull, flux, speed, shields, range and peak time come from live stats, skills and d-mods.
- Max CR: Degraded Life Support, Faulty Automated Systems, Increased Maintenance and skills run through the CR rules. Aim worsens as CR falls. Ships below 50% and 40% fight worse and malfunction. The malfunction thresholds are read from the game for each ship.
- Flight deck: Damaged Flight Deck, Malfunctioning Comms and Defective Manufactory lower wing value. Expanded Deck Crew raises it.
- Turrets: Damaged Weapon Mounts cost point defense and accuracy. Weapon durability is not scored.
- Officers: The officer's personality sets the fit's range. A ship without an officer is fitted for the personality from the doctrine aggression slider. On 0.98.5a an inactive officer counts as none.
- Officer skills: The captain's skills count in the fit, elite effects included. The flagship counts the player's skills. An AI core counts as a captain. Optimize fleet and Order fleet weapons use each ship's own captain.
- Officer skills, scored: Ordnance Expertise, Point Defense, Target Analysis, Missile Specialization, Energy Weapon Mastery, Field Modulation, Systems Expertise, Helmsmanship, Damage Control, Impact Mitigation, Combat Endurance, Gunnery Implants and Polarized Armor. Skills from other mods count through the ship stats they change.
- Officer skills, not scored: Missile ammo regeneration, damage to and from weapons and engines, overload time, phase cloak upkeep and cooldown, target leading, the elite zero-flux boost, hull regeneration and repairs under fire.

## Weapon groups

- Broadside ships (Conquest): Each side's matching weapons get their own group.
- Missile boats: Guided launchers link as one volley.

## How it searches

- Passes: Biggest mounts first, then smallest first. The better result is kept.
- Local changes: Swaps pairs of big mounts and trades one hullmod for another.
- Result: The same ship and stock give the same fit.
