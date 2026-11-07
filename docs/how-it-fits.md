# How a ship gets fitted

What the Optimal fit looks at when it builds a ship. You don't need any of this to use it, it's here if you want to know why it picked something.

**Contents:** [The idea](#the-idea) · [Weapons](#weapons) · [Missiles](#missiles) · [Shields, flux and armor](#shields-flux-and-armor) · [Point defense](#point-defense) · [Fighters](#fighters) · [Hullmods](#hullmods) · [The ship itself](#the-ship-itself) · [Weapon groups](#weapon-groups) · [How it searches](#how-it-searches)

## The idea

Killing a ship takes three steps: break its shield, strip its armor, then kill the hull. The fit spreads firepower so the ship can do all three, and scores every option by how fast the ship would actually do that in a fight, flown by the game's AI.

The numbers come from the game's own combat code: how the AI aims, when it raises shields, when it backs off to vent, when it fires missiles. OP cost and the game's autofit tiers aren't used to rank anything.

**Build style** (in the settings) changes the balance:
- **Auto** picks per ship. It's the default.
- **Balanced** covers all three layers. Line ships on Auto or Balanced always carry both kinetic and an armor-cracker.
- **Anti-shield**, **Anti-armor** and **Hull killer** put most of the firepower into one layer and keep enough of the rest to finish the job.

**Fleet role.** Each ship is fitted for its job, read from its designation and numbers: skirmisher frigates, flankers, line cruisers, capital anchors, carriers, battle carriers, support, phase ships and missile boats. The role sets who it mostly fights, firepower against staying alive, range, point defense and fighters.

**The rest of the fleet** counts too. Fighter roles you already have plenty of, and wings already flying elsewhere, count for less. On Auto or Balanced, each ship leans toward whatever damage the fleet is short of.

## Weapons

- Each weapon is scored on damage against shields, armor and hull, flux cost, range, hit strength against armor, ammo and scripted special effects.
- Loadouts stay coherent: matched sets of the same weapon, one range band, and no more than the game's 7 weapon groups.
- Guns past the ship's sustained flux still count. They fire in bursts (when the enemy overloads, or on a flank) paid from flux capacity. So big mounts get real weapons, not 1-OP fillers or small guns.
- Side and rear mounts that can't reach the main target get point defense or cheap flank cover.
- **Aim:** only the main weapon group is held back against shields, and large slow turrets can't track frigates.
- A mount is only left empty when filling it would make the ship more than about 2% worse.
- Weapon OP costs come from the ship's live stats, so hullmod discounts count (Valkyrian Bryncore, for example, and its +100 range on Valkyrian weapons).
- **Charged guns** like the Autopulse, Thermal Pulse Cannon and Storm Needler count the burst from their stored charges, not just their recharge rate.
- **Community ratings** break near-ties: when two weapons score about the same, the one rated higher for AI ships in Grievous69's 0.98a tier lists wins. It moves a weapon's value by about 5% at most.

## Missiles

- A launcher counts for what it can fire within the fight length (90 seconds by default). Extra ammo only helps launchers that would run dry before that.
- HE torpedoes and rockets are priced against armor the same way guns are.
- Unguided torpedoes only count against slow, big targets, since that's the only time the AI fires them.
- Free-firing rockets mostly hit raised shields.
- Enemy point defense shoots weak missiles down: about a third of 50-HP rockets, but few 500-HP Reapers.
- Unguided rockets land less often than guided missiles. Rockets that can hit your own ships (Annihilators, Hammers, Reapers, Yunru's gyrojets) count for less the harder each one hits.

## Shields, flux and armor

- The AI only uses about 80% of its flux on shields, shield upkeep only counts while the shield is up, and AI ships want dissipation to about 1.25x their weapons' flux before capacitors.
- **Staying in the fight:** fire on the shield fills a ship's flux until the AI backs off to vent. Hardened Shields, capacitors and faster venting keep it in longer, so Hardened Shields counts as the solid default experts call it.
- **Armor** cuts every hit by hit / (hit + armor). It shrugs off small arms and matters most on ships that already have a lot of it, so Heavy Armor goes on heavily armored hulls like the Onslaught and Paragon rather than everywhere.
- Survival is worked out against a mix of incoming fire, not as plain hit points.

## Point defense

- Point defense is rated by whether one weapon can kill a missile in time, so Burst PD beats LR PD.
- Every ship gets at least the point defense floor for its size (a setting, 1 by default).
- Advanced Turret Gyros helps point defense. Damaged Weapon Mounts' slower turning hurts it.

## Fighters

Each wing is compared with the community's top pick for its role:

| Role | Reference wing |
|---|---|
| Fighters | Broadsword |
| Bombers | Dagger |
| Interceptors | Spark or Wasp |
| Support | Xyphos or Sarissa |

It compares four things: firepower per OP (for bombers, armor damage or shield breaking, times how reliably the payload lands), toughness per OP, point defense, and sortie time (refit plus the trip out and back). A wing can match the reference on each but not beat it, so no cheap wing takes every bay.

- **Free wings** like Mining Pods are valued at what they actually do, about 2 OP worth of Xyphos. Drones that sit in front of the carrier with nothing but point-defense guns count as point defense.
- **Wings that stay by their carrier** (Sarissa, ARMAA Gunhed) count as support.
- **Carriers with bombers don't get interceptors.** The game never sends a bomber carrier's fighters to escort, so the interceptors couldn't screen it.
- Carriers only escort when at least two thirds of their wings are fighters, interceptors or support, and the shortest-range wing caps how far all of them strike.
- Defensive Targeting Array changes what each fighter role is worth.
- Across the fleet, it aims for about 35% fighters, 35% bombers, 10% interceptors and 20% support.

## Hullmods

- Hullmods are picked for the ship and its weapons: range mods only with guns that use them, missile mods only with missiles, and so on. Then the remaining OP goes into vents and capacitors.
- Hullmods with a drawback (supply cost, recovery cost, deployment cost, anything that makes something worse) are only used if you allow them in the settings.
- **Safety Overrides** is only considered on frigates and destroyers by default, and counted with its costs (range past 450, peak time, no venting).
- **Integrated Targeting Unit** never goes on frigates.
- **Shield Conversion - Omni** gets a small bonus on slow, low-tech, front-shielded ships (Onslaught, Legion, Dominator, Enforcer), where the AI uses it to block torpedoes and big shells.
- **Resistant Flux Conduits** counts for its EMP resistance and faster venting, mostly on big ships.
- **Expanded Magazines** is valued on ships with charged guns.
- **Phase ships** get Adaptive Phase Coils or Phase Anchor valued properly, their survival grows with the flux capacity they phase on, and Heavy Armor never goes on them.
- Logistics hullmods (cargo, fuel, sensors, burn) stay off combat ships unless you allow them. Civilian ships are different, see [Civilian ships](civilian-ships.md).

## The ship itself

Every ship is fitted for its own numbers: armor, hull, flux, speed, shields, range and peak time come from its live stats, skills and d-mods included. On top of that:

- **Max CR.** Degraded Life Support, Faulty Automated Systems, Increased Maintenance and skills are run through the game's CR rules. The AI aims worse as CR falls, and ships below 50% and 40% fight worse and malfunction.
- **The flight deck.** Damaged Flight Deck, Malfunctioning Comms and Defective Manufactory make that carrier's wings worth less. Expanded Deck Crew makes them worth more.
- **Turrets.** Damaged Weapon Mounts cost point defense and accuracy.
- **Malfunctions.** Ill-Advised Modifications' weapon malfunctions count.
- **Officers.** A ship without an officer is fitted for the personality your doctrine's aggression slider gives it (Command > Doctrine), not always Steady.
- **Ship systems.** Accelerated Ammo Feeder and High Energy Focus add to the ballistic and energy weapons on their hulls.
- **Handling.** Extra turning counts most on slow hulls that aim their hardpoints by turning, not on nimble frigates.

## Weapon groups

After the game makes its own weapon groups:
- a broadside ship (a Conquest, say) gets each side's matching weapons in their own group, so it fires the side that bears;
- a missile boat's guided launchers are linked to fire as one volley.

## How it searches

- It runs twice, filling the biggest mounts first and then the smallest first, and keeps the better result.
- It tries swapping pairs of big mounts and trading one hullmod for another, so one early pick can't lock the ship into a weaker fit.
- The same ship with the same stock gets the same fit every click.

Next up: [The fleet buttons](fleet-tools.md).
