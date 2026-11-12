# The fleet buttons

Three buttons on the refit screen work on the whole fleet at once:

- **Optimize fleet** fits every ship from what you have.
- **Order fleet weapons** orders what the best fleet would need.
- **Fleet analysis** shows where the fleet is weak.

A fourth button, **Lock this ship**, keeps the ship on screen out of the first two. See [Locked ships](#locked-ships).

Order fleet weapons and Optimize fleet are stacked next to the Autofit button. Fleet analysis and Lock this ship go in the next free spots, and the Optimize fleet panel links to Fleet analysis too. You can turn all four off with the **Order weapons for fleet button** setting.

## Locked ships

**Lock this ship** on the refit screen locks the ship shown there. On a locked ship the button reads **Unlock this ship**.

A locked ship:
- keeps its weapons, wings, hullmods, vents and capacitors when you press Optimize fleet;
- gets nothing ordered by Order fleet weapons;
- keeps what it carries; those weapons and LPCs are not used for other ships;
- still counts toward the fleet's balance, so the other ships are planned around it.

The Autofit button's **Optimal** fit still works on a locked ship. Locks are saved with the game.

## The fleet plan

Optimize fleet and Order fleet weapons share one plan:

1. Every combat ship is planned together from one shared stock, biggest ships first.
2. Each ship leans toward what the rest of the plan is short of:
   - **anti-shield**: how fast the fleet fills enemy flux bars (kinetic counts fully, energy half, HE a quarter, fragmentation an eighth);
   - **armor-cracking**: hits strong enough to get through armor;
   - **fighter roles**: about 35% fighters, 35% bombers, 10% interceptors and 20% support.
3. Then each ship is planned again with all the others in view, up to two more passes. A ship only changes if the new plan is clearly better.

The plan doesn't care what your ships carry right now. Every ship is planned from bare, so the same fleet, stock and blueprints always give the same plan.

It runs a little each frame with a progress line. A big fleet takes a few seconds.

## Optimize fleet

Plans every ship from what you have here: what the ships carry, your fleet's cargo, and your storage at the market you're docked at. Nothing is bought.

The panel lists what changes on each ship (weapons, wings, hullmods on and off, s-mods with their story points) and the fleet's anti-shield, armor-cracking and fighter mix before and after. Nothing happens until you press **Apply to all ships**. Then:

- weapons and LPCs come off into cargo, and each ship takes its plan from cargo, then storage;
- whatever isn't used again goes to your storage there;
- hullmods only change where the game's refit rules allow (spaceport hullmods only at a spaceport);
- s-mods only get built if **Build in s-mods** is ticked in the Autofit panel (the same box as a single Optimal fit, off by default). See [S-mods and story points](s-mods.md).

**Civilian ships** (freighters, tankers, liners, salvage and survey ships) get their campaign fit instead, and their weapons and LPCs go back into the stock for the warships. See [Civilian ships](civilian-ships.md).

## Order fleet weapons

Runs the same fleet plan, but as if every weapon and fighter blueprint you know were in unlimited supply. It lists what custom production would have to build for that fleet, with costs and the ships that would use each item. Nothing is ordered until you press **Queue all**.

- **What you own is used first.** Cargo, every storage, what's on your ships, and what's already on order all count as stock. So pressing it again later only orders what's still missing.
- **Budget.** One order costs at most the **Production budget** share of your credits (50% by default). If the plan costs more, the biggest ships' items are ordered first and the rest next time you press it.
- **Nothing is charged when you press Queue all.** You pay the way the production system you're using charges.

### Where the order goes

| You have | The order goes to | You pay |
|---|---|---|
| Just the base game | The game's custom production (needs Heavy Industry or Orbital Works) | Each month as items get built |
| Ashes of the Domain: Vaults of Knowledge | AoTD's own production, its Custom production screen | Each unit when it's finished |

AoTD's production also takes goods from your colonies every month: Heavy Armaments for every item, Ships for LPCs, and Electronic Components for most. The order panel lists them, says which colony makes each one, and flags anything no colony makes, since those items wait until something does.

With **Jonah's Colony Manager** 2.39 or later, its Special project supply plans a producer for goods none of your colonies make. A good your colonies only make a little of just slows the order down.

## Fleet analysis

Shows:
- the fleet's firepower by damage type, with anti-shield and armor-cracking against their targets;
- fighter bays by role, with the most common wings;
- recommendations: anti-shield or anti-armor shortfalls, fighter roles leaning one way, carriers mixing interceptors with bombers, ships short of point defense, mixed range bands and empty mounts;
- one line per ship.

It doesn't change anything. Its **Optimize fleet** button opens the fleet plan.

Next up: [Civilian ships](civilian-ships.md).
