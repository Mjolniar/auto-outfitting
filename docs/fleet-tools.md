# The fleet buttons

- Optimize fleet: Fits every ship from what you have.
- Order fleet weapons: Orders what the best fleet needs.
- Fleet analysis: Shows fleet weaknesses.
- Lock this ship: Keeps the ship out of the first two. See [Locked ships](#locked-ships).
- Setting: Order weapons for fleet button turns all four off.

## Locked ships

- Button: Lock this ship reads Unlock this ship on a locked ship.
- Optimize fleet: A locked ship keeps its weapons, wings, hullmods, vents and capacitors.
- Order fleet weapons: Nothing is ordered for it. Its weapons and LPCs are not used for other ships.
- Balance: It still counts toward fleet balance. Single-ship Optimal still works on it.
- Saving: Locks are saved with the game.

## The fleet plan

1. Every combat ship is planned from bare and from one shared stock, biggest first.
2. Each ship leans toward what the plan lacks: anti-shield (kinetic counts fully, energy half, HE a quarter, fragmentation an eighth), armor-cracking, and fighter roles (35% fighters, 35% bombers, 10% interceptors, 20% support).
3. Up to two more passes replan each ship. A ship changes only if the new plan is clearly better.


## Optimize fleet

- Source: Ship contents, fleet cargo and storage at the docked market. Nothing is bought.
- Panel: Lists changes per ship (weapons, wings, hullmods, s-mods with story points) and the fleet mix before and after.
- Apply to all ships: Weapons and LPCs go to cargo, ships take their plan from cargo then storage, unused items go to storage.
- CR: Away from a market every changed ship loses CR, as on the refit screen. The panel shows each ship's loss before Apply. Refits at a market cost no CR.
- S-mods: Built only if Build in s-mods is ticked. See [S-mods and story points](s-mods.md).
- Civilian ships: Get their campaign fit. See [Civilian ships](civilian-ships.md).

## Order fleet weapons

- Plan: The same plan with unlimited supply of every known blueprint. It lists what custom production must build, with costs and the ships using each item.
- Queue all: Orders the list. Custom production charges nothing on pressing it. A RAT settlement charges its share at once.
- Stock: Cargo, storage, ship contents and items on order count first. Pressing again orders only what is missing.
- Budget: One order costs at most the Production budget share of credits (50%). Biggest ships' items go first.
- Base game: Custom production (needs Heavy Industry or Orbital Works), paid monthly as items are built.
- Ashes of the Domain: Vaults of Knowledge: AoTD Custom production screen, paid per unit when finished.
- AoTD goods: Takes Heavy Armaments for every item, Ships for LPCs and Electronic Components for most, monthly from colonies. The panel lists them, names the producing colony and flags goods no colony makes. Those items wait.
- Auto Colonies 0.2.39+: Special project supply plans a producer for goods no colony makes.
- Random Assortment of Things: A Frontiers settlement with Ordnance Manufacturing takes the biggest ships' items while its budget lasts. The rest goes to custom production.
- RAT settlement prices: 20% over base, paid when ordered. Budget 150,000 at most, +10,000 a month (more with other facilities). Delivered to the settlement's storage in 10 to 60 days.
- RAT without Heavy Industry or Orbital Works: The settlement takes what fits its budget. The rest is ordered next time.
- RAT stock: The settlement's storage and its open orders count as owned.
- Setting: RAT settlement orders (On) turns the settlement off for orders.

## Fleet analysis

- Shows: Firepower by damage type against anti-shield and armor-cracking targets, fighter bays by role, recommendations (shortfalls, skewed fighter roles, mixed carriers, short point defense, mixed range bands, empty mounts) and one line per ship.
- Changes: None. Its Optimize fleet button opens the fleet plan.
