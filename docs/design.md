# Jonah's Auto Outfitting: design (draft for the build step)

Status 2026-11-02: version 0.2.0 built, installed and enabled in the user's game; not yet tried in game.

**What 0.2.0 has**
- **Scoring core**: `JAOModel` (pure, offline-testable): three-phase TTK, survival, uptime, AI engagement range, PD floor, flux cap.
- **Search**: `JAOSearch` (pure): greedy, then swaps and hullmods, with vents/caps refilled on every evaluation and filler hullmods only once vents and caps are maxed.
- **Game adapters**:
  - `JAORatings`: live specs, reference targets, the three tables merged across mods;
  - `JAOShip`: scratch fleet member stats, OP, hullmod legality;
  - `JAOOptimizer`: delegate strip/pool/apply, modules, built-in and code-owned slots as fixed fits;
  - `JAOGoals`: SavedVariantData injection via MethodHandles, cleanup.
- **Offline check**: `jtbuild\JAOFitCheck.java`, run by `ao_build.ps1`, 178 checks on 14 vanilla hulls × 3 styles: legality, determinism, kinetic + armor-cracker, empty pool, no-kinetic pool, flux, runtime (1-30 ms per fit).
- **Known tuning issues (from the offline fits)**:
  - Heavy Armor is picked often, even on the Paragon;
  - broadside small energy slots on the Conquest and Paragon are left empty once PD is covered;
  - Safety Overrides is still chosen on some short-range cruisers (Falcon, Aurora).
  These are calibration knobs (kA, PD value, outrange penalty) for after the first in-game look.
Research files are in the author's research notes (not included in this repository). "R:" below points into them.

## 1. Goal

The refit screen's autofit row gets an extra **Optimal** card. Clicking it builds the strongest loadout for that ship from what the player has right now: cargo, storage, and the market or black market when those boxes are ticked. This covers weapons, fighter LPCs, hullmods, vents and capacitors. Every other card keeps the game's own autofit.

The build style decides how firepower is spread over the three layers of a kill:
- **Balanced**: break the shield, strip the armor, kill the hull.
- **Anti-shield**, **Anti-armor**, **Hull killer**: put most firepower into one layer.
- **Auto**: picks per ship.

## 2. What vanilla autofit does, and why it isn't optimal

R: `apisrc_full\...\plugins\impl\CoreAutofitPlugin.java`.

- **It copies the goal variant.** Each goal weapon's autofit categories (tags `kinetic3`, `he2`, `pd1`...) are tried in order. In each category it takes the available weapon with the highest hand-set tag level, then falls back through a fixed category list.
  - Nothing measures damage, flux, hit strength vs armor or special effects.
  - The levels roughly follow tier/OP, so "bigger number" wins.
- **Hullmods**: it copies the goal's hullmods, pads leftover OP with vents/caps, then adds Reinforced Bulkheads, Blast Doors, Hardened Subsystems, Flux Distributor/Coil and Armored Weapon Mounts (`:443-508`).
- **No damage-type balance.** If the goal's kinetic slot has no kinetic weapon available, the fallback list decides.
- **R: `weapon_rankings.md`**: 34 vanilla pairs where the tag level ranks a weapon above one that is at least 1.5x better per OP on the single-weapon kill-time model.
- **No goal at all for 171 of 1,095 combat hulls** (R: `hull_profiles.md`). For those ships Optimal would be the only autofit.

## 3. How it hooks in

R: `engine_report.md` sections 1, 2, 8.

- **Plugin**: `JAOCampaignPlugin` (transient, registered in `onGameLoad`) returns `JAOAutofitPlugin` from `pickAutofitPlugin` at `MOD_GENERAL`.
  - That beats vanilla's `CORE_GENERAL`. No installed mod competes; ties go to the plugin registered last.
  - `JAOAutofitPlugin extends CoreAutofitPlugin`, so the panel keeps its checkboxes, credit cost, "Spend free OP" and officer auto-assign.
- **The Optimal card**:
  - The goal row has **4 slots per base hull** (`SavedVariantData.MAX_VISIBLE`). Most hulls fill 1-3 with stock goals, 12 vanilla hulls fill all 4.
  - The public `getTargetVariants` returns a copy. The card goes in through the engine's `SavedVariantData.getVisible(base)` / `addToVisible(i, variant)`, reached with MethodHandles and feature-detected.
    - That class is `DoNotObfuscate`, so its names are stable and Fast Rendering leaves them alone.
    - Nexerelin and Starship Legends already use it.
  - **Timing**: the dialog calls `pickAutofitPlugin(member)` immediately before it draws the goal row. That call is where the card is added.
  - **Contents**:
    - Display name "Optimal" (or "Optimal (Anti-armor)" etc. with "One goal per style").
    - Marker tag `jao_optimal_<style>`.
    - A real loadout: a clone of the hull's first stock goal, or the current variant.
    - It is never left empty. Player-faction NPC fleets build half their ships from goal variants (`DefaultFleetInflater` `GOAL_VARIANT_PROBABILITY = 0.5`, copied by the Imperium and AotD inflaters), so a leaked empty goal would make weaponless patrol ships.
  - **Temporary**: `JAOCleanup` (transient EveryFrameScript, runs while paused) removes tagged entries as soon as the core tab isn't REFIT. They are also removed in `beforeGameSave` and `onGameLoad`.
    - Removal covers visible slots, `getRecent(base)` (the player's × moves a card there) and `getVariantMap()`.
    - Saves never hold the card, so removing the mod is clean.
  - **Full rows**: hulls with no free slot (or if `SavedVariantData` can't be reached) use the autofit checkbox "Optimal fit for every goal" (public API). It persists per save through the dialog's `optionState`.
- **Running it**:
  - Preview runs `doFit(current.clone(), goal, 0, previewDelegate)` on every card click and option toggle. Confirm runs the same with the real delegate and the same `Random` seed.
  - So the optimizer must be **deterministic** (fixed tie order, the plugin's `random` only) and **fast** (target under 50 ms per fit, synchronous).
  - `maxSMods` is always 0 here, and the CR penalty for refitting away from port is applied by the game at save.

## 4. What the optimizer may use

R: `engine_report.md` 3, 7.

- **Weapons and LPCs**: `delegate.getAvailableWeapons()/getAvailableFighters()`, filtered by the panel's own options through `CoreAutofitPlugin.getWeapons/getFighters` (cargo / storage / market / black market).
  - Use the delegate's own entry objects. It casts them, so our own implementations would crash.
  - Respect `getQuantity()` (live, decremented even when a real-mode fit fails).
  - Price is per unit and already includes tariff; storage costs 0.
- **Strip through the delegate** (`clearWeaponSlot`/`clearFighterSlot`) first, so the ship's mounted weapons join the pool. Then call `syncUIWithVariant(current)` once, so the UI's OP check uses our variant.
- **Hullmods**: `delegate.getAvailableHullmods()` (known + always-unlocked, spaceport rule applied). We still check:
  - `isApplicableToShip` via `ship.setVariantForHullmodCheckOnly`, item requirements, phase tags;
  - incompatibilities (ITU/DTC/ATC/DFC, HSA vs Advanced Optics, shield conversions);
  - the 2-Logistics limit;
  - `canAddRemoveHullmodInPlayerCampaignRefit`.
  - `CoreAutofitPlugin.addModIfPossible` already does most of this.
- **OP**:
  - Hull OP: `hullSpec.getOrdnancePoints(stats)`.
  - Weapon OP: `w.getOPCost(stats, current.getStatsForOpCosts())`, with the player's character stats. Re-read after hullmod changes, since some mods change weapon OP.
  - Max vents/caps: 10/20/30/50 × the player's `getMaxVentsBonus/getMaxCapacitorsBonus`.
  - The engine checks none of this afterwards, so it's on us.
- **Slot rules**: `slot.weaponFits(spec)`. Exact mount type may go one size down. Hybrid / Composite / Synergy / Universal need the same size. Skip built-in, decorative, hidden, system and station slots.
- **Priority marks**: the refit delegate's `isPriority` is hard-wired to false. With "Respect priority weapons" on we read `getPlayerFaction().isWeaponPriority(id)` / `isFighterPriority(id)` ourselves and use them as a tie-break bonus.

## 5. Ship model

R: `engine_report.md` 5.1, `mechanics_report.md` 1, 3, 5.

- **Stats after hullmods and skills** come from one scratch `FleetMemberAPI` per fit:
  1. `createFleetMember(SHIP, clone)`.
  2. `setFleetCommanderForStats(player, playerFleet.getFleetData())`.
  3. For each candidate, `setVariant(candidate, false, true)` and read `getStats()`.
  - This gives armor, hull, flux, dissipation, shield damage taken, speed, range bonuses, weapon flux and damage mods, and fleet skills.
  - It does not run after-creation hullmods (Ballistic Rangefinder, High Scatter Amp, Coherer, PD Integration ...). The model adds those itself.
- **The captain** (`member.getCaptain()`) sets:
  - the engagement range (personality);
  - skill multipliers. Ordnance Expertise: +1.5 dissipation per weapon OP (elite +20 capacity per OP). Point Defense ×1.5. Missile Specialization, etc.
- **Derived**:
  - shield-tank share;
  - flux budget = dissipation − shield upkeep − system flux;
  - peak time;
  - mounted-slot geometry: front arc share, hardpoints, mirror pairs.

## 6. Weapon model

R: `mechanics_report.md` 1-2, 6, 8.

- **Numbers**: `WeaponSpecAPI.getDerivedStats()` gives dps, sustained dps, burst, flux/s and EMP. They're scaled by the ship's ROF, damage and flux mods.
- **Hit strength** = damage per projectile × armor multiplier. For beams it's 0.5 × DPS.
- **Damage type multipliers** (shield / armor / hull): kinetic 2/.5/1, HE .5/2/1, frag .25/.25/1, energy 1/1/1.
- **Armor**: damage taken `max(0.15, h/(h+P))`, with P = pooled armor, never below 5% of max armor. Hull damage while any armor rating is left: `D × max(0.15, h/(h + 0.05A))`.
- **Beams** put soft flux on shields unless the ship has High Scatter Amp. Soft flux counts only above the target's dissipation.
- **Uptime U** = `min(1, (budget × T + 0.5 × capacity) / (load × T))`, where load = non-PD flux/s + 0.5 × PD flux/s.
  - This applies to every DPS.
  - A load above 1.3 × budget is rejected: the AI switches groups off (`mechanics` 3.4).
- **Range**:
  - The AI fights at the shortest range of its heaviest group, and never stands off more than 200 past the enemy's reach (`mechanics` 3.2-3.3).
  - Weapons shorter than the main band count ×(R/R_eng)². Aggressive officers count them at 1.
  - Reach above about 1,100 gives nothing extra.
- **PD value**: missile DPS × hit factor × arc cover × reach. "Need" per size is 100/200/350/500 missile-dps × the PD floor setting.
- **Special effects**:
  - These are what makes rare weapons "objectively stronger": ion arcs, Tachyon arcs, Breach's armor damage that ignores reduction, Squall's +150 vs shields, DEM payloads, MIRVs, chain hits, damage over time.
  - Shipped as `data/config/jao_weapon_effects.csv`: weapon id → extra shield / armor / hull / EMP damage per hit, or a multiplier.
  - Vanilla rows come from `mechanics` 6.2; mod rows from `modded_report.md`.
  - Unknown scripted weapons (1,007 rows have effect classes, R: `data\weapons.json` `scripts`) get no bonus and no penalty.
  - Tier and rarity are only tie-breaks. A rare weapon wins when its measured or tabled effects make it win.
- **Missiles** (finite ammo): a damage pool, spent in the phase they suit:
  - kinetic → shields;
  - HE / energy → armor / hull after overload;
  - STRIKE only vs destroyers and up, and fired only when the AI picks their group.

## 7. Build objective

R: `mechanics_report.md` 8.6-8.7.

**Time to kill (TTK)** of the whole build against reference targets, in three phases:

```
t1 shield  = kC x flux capacity / (hard shield DPS + max(0, soft shield DPS - target dissipation))   kC 0.6 (AI vents/backs off)
t2 armor   = integrate pooled armor from A to 0.2A:  dP/dt = -0.8 x sum DPS x aM x g(HS x aM, max(P, 0.05A))  x k_spread 1.5
t3 hull    = H / sum hull DPS (residual armor)
TTK        = t1 + t2 + t3,  every DPS x uptime x range factor
```

**Targets** are medians of all loaded combat hulls per size. They're computed at load from the real specs, vanilla + mods. R: `data\reference_targets.json`, cruiser median: armor 1000, hull 8000, flux 10000, dissipation 525, shield eff 0.8.

**Weights by the ship's own size**:

| Ship size | Frigate targets | Destroyer | Cruiser | Capital |
|---|---|---|---|---|
| Frigate | 0.35 | 0.35 | 0.2 | 0.1 |
| Capital | 0.1 | 0.2 | 0.35 | 0.35 |

**Styles** (`JAOStyle`):

| Style | Minimise |
|---|---|
| Balanced | Σ w·TTK |
| Anti-shield | Σ w·(0.7 t1 + 0.15 t2 + 0.15 t3) |
| Anti-armor | TTK vs armored targets (1250-1750) with 0.15/0.55/0.3 |
| Hull killer | Σ w·(0.1 t1 + 0.3 t2 + 0.6 t3) plus burst damage inside the overload window (4/6/8/10 s) |

**Auto** picks per ship:
- missile boats with STRIKE missiles → Anti-armor;
- carriers → Balanced for the guns;
- otherwise Balanced.

The mix of the hull's stock goals (R: `hull_profiles.md` "mix") is a second hint.

**Survival** (needed so hullmods, vents and caps can be compared with guns): EHP = hull + kA·armor + shield HP.
- Shield HP = tank share × (capacity + 30 s × spare dissipation) / shield efficiency.
- kA = 10/15/20/25 per size.
- Overall score = `survival^0.5 / TTK^0.5` (offense and defense weighted equally).
- Defensive styles are a later option.

**Hard rules**:
- OP within limit; load ≤ 1.3 × budget;
- PD ≥ 0.5 × need for destroyers and up;
- one range band for the main guns (or pay the mixed-range penalty);
- mirror pairs kept symmetric;
- no STRIKE-only fits on frigates.

## 7b. Any mod list: slot goals and points

The user wants this to work with any mod list: mods missing, mods added later, odd weapon types, whole factions absent. So nothing is hard-wired per mod. The system has three layers.

### 1. Ratings come from the loaded game

At first use after a load, `JAORatings` reads every spec the game has loaded: `getAllWeaponSpecs()`, `getAllFighterWingSpecs()`, `getAllHullModSpecs()`, `getAllShipHullSpecs()`. From those it computes:
- the reference targets (medians of the loaded combat hulls);
- a rating for every weapon, wing and hullmod.

About 1,700 weapons × 4 targets is trivial. The ratings are cached until the next load or settings change.

Whatever mods are present is what gets rated. A removed mod's content simply isn't there; a new mod's content is rated like everything else.

### 2. Slot goals ("each hole has a goal it wants to end up as")

When a ship is profiled, every mountable slot gets a goal role from:
- its size, type, mount and arc;
- the hull's role (`hull_profiles` rules: carrier, phase, missile boat, brawler ...);
- the build style.

The roles:

| Role | What it's for |
|---|---|
| main gun | anti-shield or anti-armor, decided by the style and the rest of the build |
| finisher | hull damage |
| PD | missile and fighter defense |
| strike | anti-armor alpha, missiles |
| support | EMP, utility |
| flex | anything that helps |

Every weapon has a **points score per role**, on one scale for vanilla and modded weapons alike:
- 100 = the best weapon in the loaded game for that role at that slot size;
- the score is per OP and against the reference targets.

So the slot's ideal is whatever the loaded game's best is. With a strong mod weapon installed, that's the ideal; without it, the best remaining weapon is.

### 3. The goal resolves against what the player can reach

Three tiers, best first. Each slot's goal is met from the highest tier that has something for it:

1. **In stock here**: the autofit pool, meaning fleet cargo plus storage and the docked market or black market when those boxes are ticked. This is the only tier that gets fitted.
2. **Known**: what the player has learned by right-clicking a found blueprint or hullmod item:
   - weapon/fighter blueprints via `getPlayerFaction().knowsWeapon(id)` / `knowsFighter(id)` (`getKnownWeapons()` / `getKnownFighters()`);
   - hullmods via `knowsHullMod(id)`, plus the always-unlocked ones.
   Known hullmods are fittable at once, since the delegate offers them. Known weapons and fighters aren't fitted until they're in the pool. With "Log every fit" on, the log lists the known item that would raise the score most ("Gauss Cannon would add 12%"), as a production/shopping hint.
3. **Exists in the game**: the slot's ideal. It sets the 100-point scale and the goal's target, so the log can say how close a fit got.

- **When nothing in stock fits a slot's goal**, the goal shifts: the slot is re-goaled to the role with the best available score. The whole-build TTK check (§7) then confirms the shift helps. Example: no kinetic large in stock → the large slot becomes an HE/energy main gun, and the medium slots take over anti-shield if they can.
- **When nothing helps at all**, the slot stays empty and its OP goes to hullmods, vents or caps.
- **"Researched"** means learned blueprints (the user, 2026-11-02), not AotD's research tree, which covers colony knowledge. If any mod restricts items another way, the pool already reflects it, because the delegate only offers what the game allows.

### Odd content

- **Unusual mounts** (COMPOSITE, HYBRID, SYNERGY, UNIVERSAL weapons and slots): only the engine's `slot.weaponFits(spec)` decides fit.
- **Damage type OTHER or unknown**: rated as energy (1/1/1).
- **SYSTEM-hint weapons** are never candidates.
- **Unknown tags and hints** are ignored. Known ones (PD, STRIKE, GUIDED_POOR, DO_NOT_AIM, `feedback_weapon` ...) feed the AI rules in §6 and §8b.
- **Scripted weapons with no table row** are rated on their plain numbers.
- **Unknown hullmods** are measured by stat diff on the scratch member (§8). Listener-only effects that can't be measured get 0 points: never chosen for an effect we can't see, never blocking anything.
- **Data tables** (`jao_weapon_effects.csv`, `jao_hullmods.csv`, `jao_rules.csv`) are keyed by id. Rows for mods that aren't loaded match nothing and are ignored.
  - Rules that depend on a mod are checked with `isModEnabled`.
  - The mod has no compile-time dependency on any content mod, only optional LunaLib, so no class can fail to load because a mod is missing.

### Testing across mod lists

The same idea as Colony Manager's `audit_combos`:
- the offline check runs the rating and optimizer on vanilla-only specs, the full modlist, and random subsets of the 26 content mods;
- each run is checked for legal fits and no exceptions;
- for a stocked pool, a sensible slot goal mix (kinetic + HE/energy when both exist).

The Python prototypes can already be re-run per subset: `tools\dump_data.py` reads only enabled mods.

## 7c. What the community and the code agree on (calibration targets)

R: `meta_report.md` (sources dated and graded: CONS / MAJ / SINGLE / OLD), section 10.

**Rarity is not quality.**
- In AI hands, rare and high-tier weapons are often worse than common ones:
  - Gauss (D tier outside Conquest / Atlas Mk II), Plasma, Mjolnir, Hellbore, Hurricane, Mark IX and LR PD Laser rate poorly;
  - Hephaestus, HIL, Harpoon, Typhoon, Heavy Autocannon, Phase Lance, Burst PD and Flak are top tier;
  - HVD is "the most AI-friendly weapon".
- The mechanics model points the same way (§6 uptime and the AI hold rule). So weapons are ranked by measured strength, including their scripted special effects. A rare weapon wins exactly when it really is stronger; tier, rarity and tag level are never a score.
- **The user chose (2026-11-02)** an optional bias, off by default: "Prefer rare weapons" + "Rare weapon bonus" (0.15).
  - Rarity score r in 0..1 = max of: (1 − drop weight `rarity`, default 1), tier / 3, and 1 for `rare_bp` / `restricted` / omega / threat / dweller tags.
  - When on, a weapon's points and its marginal score in the fill and swap search are multiplied by (1 + rareBonus × r).
  - It never overrides the hard rules (flux cap, range band, PD floor).

**Rules the model must reproduce.** These are written as offline test expectations, not runtime rules, so the model stays mod-agnostic:

1. **Flux**: sustained non-PD flux + shield upkeep ≈ 0.8-1.1 × dissipation; hard cap about 1.5×. An over-fluxed hull gets more efficient weapons, not bigger ones.
2. **Range band**: main guns within about ±200 after bonuses, with kinetic at or slightly past the HE range. Steady/Cautious officers 900-1000; Aggressive/Reckless 450-700.
3. **Damage**: every combat ship gets a hard-flux kinetic source and an armor-cracking source (HE hit ≥ ~150-200, or energy/HE hit ≥ ~400), with kinetic weighted higher.
4. **Slot conventions** (emerge from TTK + arcs; checked in tests):
   - small ballistic → kinetic or PD, never HE;
   - medium ballistic → kinetic + one HE;
   - large ballistic → HE / high hit or the kinetic anchor;
   - synergy / composite → missiles.
5. **Missiles**: fill missile slots; never the single/double variants; unguided only within ~20° of the bow; homing only off-bore.
6. **PD**: at least one PD per threatened arc. Prefer Burst PD / Flak / Vulcans; penalise LR PD. IPDAI once PD OP ≥ ~4× its cost.
7. **Hullmod break-evens** (from the code costs):
   - Hardened Shields beats capacitors above about 4,000 / 8,000 / 12,000 / 20,000 flux capacity (frigate → capital);
   - Stabilized Shields beats vents above 60 / 120 / 180 / 300 upkeep;
   - Expanded Magazines when the build relies on charge weapons (Autopulse, Storm Needler, IR Autolance, Ion Pulser, Thumper, Burst PD);
   - ITU/DTC on gun cruisers and capitals.

   These fix the prototype's misses (§8).
8. **Bulkheads and Blast Doors** never go in before every combat-relevant option is filled. That's the opposite of vanilla's padding.
9. **Safety Overrides** only on frigates/destroyers whose main guns are all ≤ ~500 range, with max vents and Hardened Subsystems.
10. **Substitution** by role, damage type, range band and flux, never by "next category". Downsize or leave a mount empty rather than make a cross-role swap.
11. **Version guard**: 0.98.5a (announced, not out) nerfs phase ships and the Antimatter Blaster and changes the Odyssey, Anubis and XIV Onslaught.
    - Ratings come from live specs, so stat changes flow through on their own.
    - Hand-written tables (`jao_weapon_effects.csv`) carry the game version and get re-checked on release.

**Test priors**: the community AI tiers for vanilla weapons (`meta_report.md` §8: SS 10 ... F 1) are the expected ordering the model should broadly match.
- Known stat-vs-community disagreements are the hard cases:
  - overrated by stats: Gauss, Plasma, Mjolnir, Heavy Blaster, Assault Chaingun, LR PD, Hurricane;
  - underrated: HVD, Graviton, Mining Laser, Tactical Laser, IR Autolance, Mining Blaster, Squall, Annihilator Pod.
- The tiers are not shipped or used at runtime, because they exist only for vanilla weapons.

## 7d. Fleet roles (user request 2026-11-02)

Ships are built for their job in a fleet, not only their numbers. Setting: `jao_roles` "Fit for each ship's fleet role", on by default. `JAORole` (pure) holds the defaults.

| Role | Purpose | Offense weight | Speed | Targets F/D/C/Cap | Auto kill layers S/A/H | Range | PD | Wings |
|---|---|---|---|---|---|---|---|---|
| Skirmisher | grab objectives, survive until the line arrives | 0.45 | 0.25 | .45/.35/.15/.05 | 1.2/0.9/0.9 | any | ×0.7 | ×1 |
| Flanker | exploit weakness (shields down, damaged ships) | 0.65 | 0.12 | .3/.35/.25/.1 | 0.7/1.15/1.15 | any | ×0.8 | ×1 |
| Line | steady damage from range | 0.6 | 0.03 | .1/.25/.35/.3 | 1/1/1 | long | ×1 | ×1 |
| Anchor | trade with capitals, soak pressure | 0.55 | 0 | .05/.15/.4/.4 | 1.1/1/0.9 | long | ×1.1 | ×1 |
| Carrier | fighters do the work, stay back | 0.55 | 0.05 | by size | 1/1/1 | long | ×1.3 | ×1.4 |
| Battle carrier | fighters plus a gun line | 0.62 | 0.03 | .1/.25/.35/.3 | 1/1/1 | any | ×1.1 | ×1.2 |
| Support | PD and covering fire | 0.45 | 0.05 | .35/.35/.2/.1 | 1.2/0.9/0.9 | long | ×1.6 | ×1.1 |
| Phase | dive, burst, phase out | 0.7 | 0.1 | .2/.35/.3/.15 | 0.5/1.2/1.3 | close | ×0.5 | ×1 |
| Missile boat | strip armor from range | 0.6 | 0.05 | .1/.3/.35/.25 | 0.6/1.6/0.8 | long | ×1 | ×1 |
| Civilian | stay alive | 0.3 | 0.15 | .6/.3/.1/0 | 1/1/1 | any | ×1.3 | ×1 |

**Detection** (`JAORole.detect`). First the designation keywords: phase; battlecarrier; carrier/mothership with bays; escort/support/picket; missile; civilian hull; battleship/dreadnought/battlewagon/juggernaut/titan/doomwagon → Anchor; battlecruiser/artillery → Line; fast/light cruiser/light destroyer/strike/raider/pursuit/interceptor → Flanker (Skirmisher for frigates). Then the numbers: phase; ≥2 bays with guns under half the OP → Carrier; bays on a cruiser or bigger → Battle carrier; missile share ≥ 0.45 → Missile boat. Otherwise by size: frigate Skirmisher, destroyer Flanker, cruiser Line, capital Anchor.
- **Override**: `jao_rules` `role,<hull id or pattern>,<Role>`.
- **Offline check**: 13 vanilla hulls are detected as expected.

**How the role enters the score**:
- `Params.apply`: an explicit style sets the kill-layer weights, Auto takes the role's;
- offense ^ role.offense × survival ^ (1 − offense);
- target mix; preferred range when the Range setting is Auto; PD need multiplier; wing value multiplier.

**Mobility, for every ship**:
- (speed / hull speed) ^ max(0.1, role.mobility) × (handling) ^ (0.5 × mobility + 0.04);
- handling = sqrt(turn ratio × acceleration ratio) from the scratch member.
- So speed hullmods and slowing penalties count everywhere: big ships that arrive late lose fights.

**Variant name**: "Optimal: <Role>[, <style>]".

## 7e. Range: two distances, judged against who the ship fights (user, 11/02)

**The problem the user saw.** The Valkyrians Gloria anchor (`valk_radiance`, built-in 900 Redemption Shockwaves) was filled with 600-range guns. In game it engaged at 900, pushed toward 600, and was shredded by 1000+ range capitals.
- The old model compared range against a fixed 750 "median enemy" for every role.
- It gave short guns 40% credit at no cost.

**Enemy main-battery range per size** (`Target.threat`).
- Measured at load from every stock goal variant in the loaded game: the shortest range in the heaviest non-missile, non-PD group, with ITU or DTC added; median per size.
- Research dump: frigate 660, destroyer 800, cruiser 1000, capital 1250 (75th percentile 1440). These are the fallback `DEFAULT_THREAT`.
- Logged at load.

**Two distances** (mechanics 3.3).
- The ship fights at the main battery's range R_main, but closes to its shortest non-PD gun R_close part of the time, whenever it has the flux edge or the target overloads.
- Share of time closed in: steady 0.4, aggressive 0.8 (with PD in the minimum), cautious 0.15.
- Weapons: credit = (1 − close) × (in range at R_main ? 1 : (R/R_main)²) + close × (in range at R_close ? 1 : (R/R_close)²).
- Survival: for each target class, S(R) = 1 + k × clamp((R − threat)/400, −1, 0.75), with k = 0.5 (0.6 Range Long, 0.3 Close). It's blended over both distances and weighted by the role's target mix, and replaces the old "outrange" factor.
- So short guns on a long ship cost survival against everything that out-ranges their range.

**Role detection uses ship systems**: Plasma Jets, Microburn, Displacer, Phase Teleporter, Temporal Shell and mod quick-boosts make a plain cruiser or destroyer a Flanker. Burn Drive and Maneuvering Jets don't (they're on line ships). Aurora and Fury are now Flankers.

**Results**
- Offline, vanilla pool:
  - Gloria 1340, closes to 1280;
  - Onslaught 1500, closes to 1500;
  - Paragon 1500, closes to 1280;
  - Conquest 1700, closes to 1350;
  - Aurora (Flanker) 4× Phase Lance at 740.
- Full modlist pool: anchors keep a coherent band; the Stardust proton guns dropped off them.
- `JAOFitCheck` requires a closing range ≥ 0.8 × main range for anchors and ≥ 0.75 for line ships.
- The empty-mount share rose from 1% to 4%: mounts with only short guns in stock stay empty or get PD, by design.

## 7f. Same answer every click (user, 11/02)

The user saw the same Optimal button give different fits.
- **Cause**: the delegate lists stock in cargo-stack order, which shifts whenever weapons move on or off ships. Near-tied candidates resolved by whichever came first.
- **Reproduced offline**: 11 of 45 fits changed with the pool listed in reverse.
- **Fix**: `JAOSearch` sorts every slot's candidates canonically (weapon id, price, stock key) before searching.
- **Check**: `JAOFitCheck` now runs every fit with the stock reversed and requires the same result (45 checks).
- **What can still legitimately change a fit**:
  - different stock (bought or sold items, market restocks, the panel's source boxes);
  - "Strip before autofitting" unticked (the current weapons stay and the fit builds around them);
  - settings changes.

## 7g. No unspent OP, no drawback hullmods unless allowed (user, 11/02)

**Leftover OP.** The user saw OP left unspent in game. The offline fits all spent everything; in game it happened when vents and capacitors were maxed and no hullmod cleared the 0.2% gain bar, failed applicability, or cost more than what was left. Fixes:
- `JAOSearch.spendLeftover`: while points remain after the flux fill, take the best use that fits: any hullmod (filler included, no minimum gain), a bigger weapon in a slot, or a weapon for an empty mount. Accept a modelled loss only up to `LEFTOVER_TOLERANCE` 5%.
- **Apply**: vents and caps are recomputed from the OP really left after fitting, so a difference goes to vents, then caps, up to max. A weapon the game refuses is logged; leftover still unused is logged.
- **Checks**: every one of the 45 fits spends all OP, plus Wolf/Eagle/Paragon knowing only padding hullmods.

**Drawbacks** (setting `jao_allowDrawbacks`, off by default; the user flagged A_S-F Anarchy mods raising deployment/supply costs).
- With it off, `JAOShip.drawback` compares a 49-stat snapshot of the scratch fleet member with and without the hullmod. Any stat more than 0.5% worse disqualifies it: survival, mobility, ranges, a range threshold, shield, damage taken, weapon damage/rate/flux cost, ammo, weapon turn rate, peak time, CR, supplies per month, supplies to recover, deployment points, fuel, venting, fighter refit and bays, crew needed, sensor profile, burn, shield present.
- Listener drawbacks the snapshot can't see are hardcoded in `KNOWN_DRAWBACKS` (High Scatter Amp's beam range).
- Penalty s-mods (`smodPenalty`) are excluded again while the setting is off.
- Safety Overrides needs it on.
- Debug log: "hullmods skipped for drawbacks: {id=stat}".
- Not testable offline (game classes); verify in game with "Log every fit".

**Aquilon (ET battlecruiser, 250 OP, light mounts) left 30 OP.**
- Its mounts take at most about 100 OP; vents and caps take 100 more; the rest can only be hullmods. With drawbacks off and logistics off, nothing left fit.
- **Fixes**:
  - fleet hullmods (ECM, Nav Relay, Escort Package, Operations Center on the flagship; `JAOSearch.fleetMods`) are the last fallback in `spendLeftover`;
  - if OP is still spare, the variant name says "(N OP spare: drawbacks off)";
  - the log names the drawback hullmods that would fit.

## 7h. Civilian ships: a logistics fit (user, 11/02)

Freighters, tankers, liners, salvage and survey ships never fight. Setting `jao_civilianLogistics`, on by default.

**Which hulls**: `JAOOptimizer.civilian`.
- The game's civilian flag (`isCivilianNonCarrier`).
- Otherwise salvage/survey/tanker/liner/freighter/transport designations.
- Never hulls with the COMBAT hint, fighter bays, or "modified"/"combat" in the designation (Prometheus Mk II, Colossus Mk II/III, Mule, Gemini are warships).

**The fit**: no weapons or wings, then campaign hullmods in `logisticsOrder`.
- First by ship type: salvage/survey → Surveying Equipment, Treasure Hunt Package; tanker → Auxiliary Fuel Tanks; liner/transport → Additional Berthing; freighter → Expanded Cargo Holds.
- Then, for all: Augmented Drive Field, Attuned Drive Field, Stardust Engine Enhancements, IndEvo Drive Field Tuning, Insulated Engine Assembly, High Resolution Sensors, Treasure Hunt Package, Surveying Equipment, Efficiency Overhaul, Solar Shielding, Stardust Environmental Shielding, Militarized Subsystems.
- Unknown mod logistics hullmods come after those.

**Rules**:
- the game's limit of 2 logistics hullmods (built-in and s-modded don't count);
- combat drawbacks are ignored;
- with drawbacks off, supply, recovery, deployment or fuel cost increases are still refused.
- With "Build in s-mods" ticked, cruisers and capitals build the first ones in (story points on Confirm only), which frees logistics room.
- Leftover OP goes to vents and caps.

**Spaceport**: all logistics hullmods are `req_spaceport`, so away from a spaceport the existing ones stay, weapons are removed, and the name says "(dock at a spaceport to add logistics hullmods)".

Variant name "Optimal: Logistics". Not testable offline; check in game.

## 7i. Scoring rework 0.3.0 (user, 11/02: "lots of empty slots", rare weapons unused, "a weird statistical spread")

**Evidence.**
- `research/fleet_diag` (the user's save): only 2% of combat-ship mount weight was truly empty, but the fits looked empty:
  - point defense held 39% of mount weight (73% on the flagship);
  - 27 of 52 medium ballistic mounts carried small machine guns;
  - 41 small mounts held a 1-OP flare launcher;
  - capitals spent about 38% of their OP on weapons, against 30–35% on hullmods (one Overslaught: 89 weapons, 180 hullmods);
  - 32 HVD, 24 Heavy Mauler and 22 Heavy Needler sat in storage, never mounted;
  - 42 of 99 mounted weapon types appeared exactly once.
- `research/weapon_eval` (484 mountable weapons, 2003 stock variants) found weapon numbers wrong for whole classes. Under the same flux-bound model, rare weapons are not stronger per OP or per flux; their edge is size, range, hit strength and scripts.

**Benchmark.** `jtbuild/ao_bench.ps1` (JAOBench) fits the user's 19 combat hulls from their real 276-weapon pool offline and reproduces the in-game numbers.

| | Before | After |
|---|---|---|
| Weapon OP | 43% | 53% |
| Plain-PD share | 39% | 28% |
| Mount use (OP) | 75% | 93% |
| Empty mounts | 1% | 0% |
| Flux ratio | 1.05 | 1.21 (most capitals 1.3–1.4) |

**Root causes and changes:**
1. **Flux wall.**
   - Old: uptime limited by budget, plus a squared penalty past 1.3x. Once a ship was flux-bound, extra guns were worthless, so OP went to defensive hullmods and near-zero-flux PD or filler. The ratio pinned at exactly 1.30.
   - Now: burst windows (`BURST_SHARE` 0.5, `BURST_CAP` 0.6 of capacity over `BURST_SECONDS` 10). The AI opens every group up to 2x while its flux is hard (mechanics 3.4).
     - All three kill phases use the mix of sustained and burst rate. Putting only armor and hull at the burst rate made fits all-HE.
     - Gentle pressure from 1.4x (exponent 0.3), and a linear cut past `FLUX_LIMIT` 1.75x.
     - A sharper 1.25 / 0.6 pull snapped every ship back to 1.25 and filled the rest with PD again.
2. **Filled/empty credit.** Empty-mount credit was binary, so a 1-OP flare fully "filled" a mount. Now a mount counts as used by its weapon's OP against `MOUNT_OP` 4/8/16, with `EMPTY_COST` 0.5. A small gun in a medium mount is half used. Padding hullmods (Bulkheads, Blast Doors) only come once mounts are 90% used (`mountsUsed()`).
3. **PD.**
   - PD flux counted at 0.5 and damage at 0.5, plus a 25% PD bonus, so dual-role heavy guns (Devastator, SEEKER minigun) and PD spam were nearly free.
   - Now plain PD hits ships at 0.25 and pays 0.5 of its flux. PD + PD_ALSO hits at 0.6 and pays full flux. The PD bonus is 0.9–1.0.
   - The PD need is about 1, 2, 3 or 4.5 Burst PD by hull size (35/65/100/150; it was 3.6x that).
4. **Weapon groups and coherence.**
   - Distinct weapons are groups, and all PD counts as one (PD merging with PD is harmless): x0.97 per group past 7, x0.985 per distinct gun past 3.
   - x(1 − 0.25 × gun damage share outside ±200 of the main battery range). This restores range coherence for Line and Anchor ships with modded pools.
   - A flat variety penalty alone caused empty mounts (an empty mount was cheaper than a new group); it only works together with (2).
5. **Arcs.** `JAOModel.bearing`:
   - 1.0 if the mount can face the bow, 0.8 within 30°, 0.35 beyond; flux is still paid.
   - Broadside mode applies when side-only mounts carry over 40% of mount weight.
6. **Weapon numbers (JAORatings).**
   - Burst beams hit at 0.5 × in-burst DPS (Tachyon 750, was 173).
   - MIRV hit is the submunition (behavior "damage"); DEM hit is half the payload beam's DPS, soft flux.
   - Linked barrels: hit per projectile, and finite-ammo pool per projectile.
   - Finite-ammo guns that cost flux (Antimatter Blaster) are guns at their one-minute average with flux paid. They had been a free pool: 20% of picks in the vanilla re-run.
   - Sustained DPS is capped at the fire rate.
   - GUIDED_POOR missiles get about 0.85 accuracy against frigates.
   - Spread over 10° costs accuracy by the cone the target fills at 80% range (Devastator).
   - Rarity for Prefer rare uses drop weight and tier, no longer `rare_bp`.
7. **Pruning.** Value per √OP alone kept only cheap guns, so big rare ones were never tried. Now the four best outright plus the four best per OP are kept, per damage role.

**Tests.**
- JAOFitCheck has 509 checks. New ones, for each vanilla hull and style:
  - mount use ≥ 0.85 (Balanced) or 0.75;
  - plain PD ≤ 55% (Balanced) or 65%;
  - groups ≤ 8;
  - at most 2 Antimatter Blasters.
- The flux check now allows up to `FLUX_LIMIT`.
- With `-DallMods=true`, 11 of 511 fail. These are modded-stat oddities: frag guns with inflated raw damage per flux, kinetic damage carried by missiles, and the PD-heavy side mounts of the Onslaught.

**Not yet:**
- An ammo-magazine burst value for regenerating guns (weapon_eval d.6).
- The 225 modded scripted weapons that have no effects row (d.10).

## 7j. Fighters, the fleet as a whole, and OP fixes in 0.4.0 (user, 11/03)

**Reports.**
- Every carrier got ARMAA Gunhed mechs.
- An Aquilon stopped at 220 of 250 OP.
- Valkyrian ships (Elegy, Valhallan series, Nirvana Mk2) were under-equipped, with front ballistics empty.
- "Is the whole fleet primed for one damage type?"
- User: "the Broadsword is an S-tier fighter… the best all-rounder… not the perfect fit for every situation."

**Fighter value.**
- **Why Gunheds won.** The old wing number was paper DPS times a toughness factor. That counted a fighter's guns as always firing and a mining drill as constant fire. The 9-OP Gunhed scored 210 per OP, the Broadsword 53 and the Trident 2.
- **Value.** A wing is now worth `WING_VALUE_PER_OP` (20) × OP × quality, with quality 0.5–1.0 (`JAOModel.wingQuality`).
- **Four axes.** Quality compares the wing with its role's reference wing on four axes:

  | Axis | Weight | Measure |
  |---|---|---|
  | Firepower | 0.40 | Per OP. Guns count at 0.35 of paper; melee (range < 150) at a further 0.35; finite missiles once per refit + 25 s. Bombers count armor damage instead: type × hit / (hit + 500). |
  | Durability | 0.30 | Craft × (hull + 2 × armor) per OP, × 1.25 with a shield. |
  | Point defense | 0.20 | 0.4 + 0.6 × the share of firepower from PD, PD_ALSO or ANTI_FTR guns. |
  | Refit tempo | 0.10 | 1 / refit seconds. |

- **Scoring an axis.** Matching the reference scores 1 and beating it scores no more; falling short costs value.
- **Reference wings.** The Broadsword for fighters (the user's pick) and the Dagger for bombers. Interceptors and support use the 75th percentile of their role's priced wings. A missing reference falls back to the percentile.
- **Results on the research data.** Broadsword 160 (8 OP); Gunhed 146 (9 OP: no PD, melee drill, 13 s refit); Thunder 130; Gladius 99. For bombers: Dagger 360, Trident 297, Longbow 198. Script: `wing_values2.py`.
- **PD credit.** Non-interceptor wings with PD-capable guns add half their value to the ship's point defense, as the Broadsword's machine guns screen.

**Fleet balance** (`JAOFleetContext`, setting `jao_fleetBalance`, default on). Optimal reads the rest of the player's fleet:
- **Fighter roles.** Each wing is × `wingRoleFactor`, 0.6–1.3, toward `WING_MIX` (40% fighters, 25% bombers, 25% interceptors, 10% support), counted across every bay in the fleet.
- **Copies.** ×0.97 per copy of the same wing elsewhere in the fleet (up to 6), and ×0.95 per repeat on the same ship.
- **Damage mix.** With the Auto or Balanced style and at least three other combat ships, the shield and armor weights move by up to 20–25% toward 45% kinetic and 30% armor-cracking (hit × armor multiplier ≥ 300) fleet firepower.
- **The user's save.** HE 32%, kinetic 28%, frag 20%, energy 19%, armor-cracking 49%, so anti-shield was short. Bays held 17 fighter, 7 support, 1 bomber and 1 interceptor; Gunhed filled 16 of them.

**Fleet analysis panel.** The button stacks under Order fleet weapons on the refit screen. The panel shows:
- the fleet's firepower mix and fighter bays by role;
- recommendations for shield or armor shortfalls, fighter roles leaning one way, ships short of PD, mixed range bands and empty mounts;
- one line per ship.

**OP fixes.**
- **Live OP prices.** Weapon OP is priced on the scratch member's live stats (`JAOShip.op`). Cost modifiers from hullmods and listeners now count, such as Valkyrian Bryncore's ballistic discount. The old variant-only price thought the front ballistics didn't fit.
- **`settleOp` after Confirm.** It re-reads the real OP: spare OP goes into vents, then capacitors; overage cuts capacitors, then vents. Each change is logged.

**Not yet:** a per-ship "all-rounder" coverage term. The fleet carries the all-round mix and ships may specialise. (Added in 0.4.1, below.)

### 7j-2. 0.4.1: community build research (`research/build_philosophy/report.txt`)

**Sources.** Community tier lists, carrier guides, the wiki's fighter AI notes, and the Valkyrians jar.

**Fighters.**
- **Reference wings.** First loaded wins: Broadsword; Dagger; Spark, then Wasp; Xyphos, then Sarissa. If none is loaded, the reference is the 75th percentile of the role's priced wings (not OP ≥ 100, not restricted + no_sell).
- **Role from tags.** Tagged `support` or `stay_in_front_of_ship` counts as support (Sarissa, ARMAA Gunhed).
- **Bomber firepower.** Max(armor damage, shield breaking = damage × shield multiplier × 0.5), × payload reliability: guided 1.0, GUIDED_POOR 0.8, unguided 0.55, bombs 0.35. The Longbow now ties the Dagger (community A+); the Trident is 0.73 and the Khopesh 0.63.
- **EMP and tempo.** EMP counts half. Tempo = 1 / (refit + 4000 / speed) for wings that leave the carrier.
- **Axis weights** are now 0.40 / 0.25 / 0.20 / 0.15. The Gunhed lands at about 0.77 in the support pool.
- **WING_MIX** is 35 fighters / 35 bombers / 10 interceptors / 20 support. Low confidence: no source gives fleet percentages.
- **Per carrier.** Interceptors on a ship with any bomber, or on a NO_AUTO_ESCORT hull, ×0.4 on both value and PD: the game never escorts there. A lone bomber on a one-bay ship ×0.7.
- **Value stays linear in OP.** The research suggested OP^0.8, but the user wants strong, high-OP fighters on carriers.

**Ships.**
- **Coverage** (Auto/Balanced; Skirmisher, Flanker, Line, Anchor, Battle carrier, or no role; 3+ main weapons; not SO): ×(0.85 + 0.15 × min(1, kinetic / 20%)) × (0.85 + 0.15 × min(1, cracking / 15%)).
  - Plain PD is left out of the shares.
  - Coverage is judged in the swap passes only. During the greedy fill it chased crackers in the big mounts and left the small missile mounts empty (the no-kinetic Eagle check).
- **Fleet kinetic target** is 50%, and wings count in the fleet mix (interceptors excepted).
- **Flux.** The research proposed pressure from 1.30 and a limit of 1.60. On the user's fleet bench that raised plain-PD share on the Overslaughts from 46% to 54%, cut mount use, and left 18% of the Invictus's mounts empty. That is the 0.3.0 regression the user reported, so 1.40 / 1.75 stay. Valhalla Core hulls start pressure at 1.45; SO and Valhalla keep the 1.75 limit.
- **settleOp** puts capacitors first on Carrier, Phase, Support and Missile boat roles.

**Valkyrians** (checked in the jar):
- Bryncore and Micro Bryncore add +100 base range to `valk*` weapons. This is `Ship.valkRange`, applied before percentage bonuses.
- Mount-use credit uses list OP (`World.baseOp`), so a 0-OP IR-PD Cannon still fills its mount.
- Discounts: 2/4/6 OP for Valkyrian weapons, 1/3/5 for other guns, 1/2/3 for missiles.

**Fleet analysis.** It flags carriers that mix interceptors with bombers.

## 7k. The fleet plan, 0.5.0 (user, 11/05)

**Reports.**
- "Every time I run Optimal across the entire fleet, we still come up without a good balance on anti-shields", and a poor balance in fighter bays.
- Asked for a button that optimizes the whole fleet, every ship with every s-mod, item and weapon, one by one.
- Order fleet weapons ordered different weapons after a few ships were re-fitted. The user's suggestion: order everything the fleet could use and let it sit in storage.

**What was found** (`research/fleet_diag_1105`, the 11/05 save; `jtbuild/JAOFleetSim.java`, run with `ao_bench.ps1 -Fleet`):
- **Firepower.** 54% fragmentation, 16% kinetic. Industrial Evolution's Cryo Artillery alone was 36% of fleet firepower (33 mounted). It deals 1000 fragmentation damage per second at 16 OP: a quarter of that hits shields and armor, all of it hits hull.
- **Hull-heavy model.** The model puts 50–70% of a kill's time on the hull, so hull damage looks best per OP. A gentle shield weight changed almost nothing: on the simulated fleet, anti-shield moved 43% → 44% at pull 2.5 / lean 1.6, 46% at 10 / 3, and 50% at 20 / 5, which swung between passes. The ships' own scores barely moved.
- **Fighters.** All carriers took bombers: 24 of 33 bays. Value grows with OP, so a 26-OP bomber is worth three 8-OP fighters per bay, and the role pull (0.8, 0.6–1.3) was too weak to offset that.
- **Order variance.** The planner kept each ship's current hullmods, balanced against the fleet as it stood, and replanned ships over the budget with owned stock only. Every re-fit changed the next order.
- **Counting.** Anti-shield counted kinetic only, so energy fleets always read as short.

**Changes.**
- **`JAOFleetMix`** (pure; used by the plan, the analysis and the bench):
  - anti-shield = shield damage per point of damage, kinetic 1. Beams count fully: the user notes their steady pressure suits the AI, which can't time a player's quick flux-out;
  - armor-cracking = hit × armor multiplier of at least 300;
  - targets 50% / 30%;
  - `PULL` 10 with lean 0.6–3 on the shield and armor kill weights;
  - wing pull 3 with factor 0.4–1.6. The simulated fleet's bays went from 4/22/0/8 to 9/15/1/9, about two bombers and a fighter or support wing per Legion.
- **`JAOFleetPlan`**, the engine behind both buttons:
  - combat ships biggest first from one shared stock;
  - pass 1 is a full fit (hullmods and, for Optimize, s-mods), balanced against the ships planned so far;
  - passes 2–3 re-pick weapons and wings with all the others in view. A ship keeps its plan unless the new one beats it by `KEEP` (1%), judged by `JAOOptimizer.scoreAsIs` under the same params. Without that rule, 30 of 44 ships flipped every pass; with it, 8 then 2 then 0;
  - every ship is planned from bare, so the plan doesn't depend on what's fitted;
  - story points are shared across the fleet;
  - it runs in frame slices with a progress line.
- **Hullmod checks off screen: `JAOCheckShip`.** It stands in for a ShipAPI, built on `JAOStubShip`, `JAOStubShield` and `JAOStubSystem`, which `jtbuild/gen_stubs.py` generates from the installed API. It answers from the planned variant: hull size, shield (none with Shield Shunt), phase cloak, system, stats and member.
  - The refit screen's own ship can't stand in for another hull. Vanilla's shield hullmods read `getShield()`, and Safety Overrides and Dedicated Targeting Core read `getHullSize()`, both of which come from the displayed ship.
  - For Optimize fleet, `PlanDelegate` applies the refit rules where you're docked: `canBeAddedOrRemovedNow` with the docked market and trade mode.
- **Optimize fleet** (`JAOFleetApply`, `JAOFleetPanel`):
  - stock is the planned ships' weapons, fleet cargo and the docked market's storage;
  - it shows each ship's changes first;
  - Apply strips the changing ships into cargo, fits each plan from cargo then storage, sets hullmods (the s-mods are built in through the game's story point path), vents and capacitors (trimmed if over OP), weapon groups and the variant name, then moves unused items to storage;
  - shared stock variants and modules are copied first;
  - the ship shown on the refit screen is changed through its working copy (`getShipDisplay().getCurrentVariant()`), then `syncWithCurrentVariant()` and `saveCurrentVariant()`.
- **Order fleet weapons** (`JAOFleetPlanner.Job`):
  - stock is owned items plus unlimited blueprints;
  - the order is everything the plan takes from blueprints;
  - the budget only decides how much is queued now (whole ships, biggest first); "N more items next time" is shown;
  - what's on order counts as owned, so the next press orders only what's missing.
- **Fleet analysis** uses the same mix and has an Optimize fleet button.
- **Refit screen.** Optimize fleet goes beside the existing stack, in the next column, when that spot is free.

**Not verified in game:** speed of a full first pass on the user's 44 ships (the offline plan takes about 2 s; the game's per-ship fit is slower), and the refit-screen sync of the shown ship.

**0.5.1, after the first in-game run (11/05).**
- **The run.** It refitted 41 ships, but the Legions and the Corrupting Heart got no fighters: the Heart showed 230/340 OP and 5 empty bays.
- **Cause.** `HullVariantSpec.setWingId(i, null)` stores "" and `getWings()` returns the raw list, so every empty bay read as full. The apply step skipped every planned wing and stripped each "" into cargo as a blank LPC, then moved those to storage.
- **Fixes.**
  - `JAOFleetApply.wingAt` reads `getWingId` with "" as empty, and everything iterates `computeNumFighterBays`.
  - `purgeBlankLpcs()` cleans cargo and all storages on load, before each save and after each apply.
  - Empty fighter bays now count in `JAOSearch.emptyShare` as large mounts. The strong role pull could otherwise price a surplus role's wing under its OP.
  - Each applied ship logs its empty mounts and bays, wings, hullmods, vents/caps and OP.
- **Button layout.** Optimize fleet couldn't go in the next column (taken on the user's screen) and ended up only inside Fleet analysis. It now sits in the stack under Order fleet weapons; Fleet analysis takes the next free spot, and the Optimize panel links to it.

## 7l. Other mods' scripts, and what production takes, 0.5.2 (user, 11/05)

**Report.** New game with 19 new mods. A Guru-class carrier (Seeker, XXX Chi-Prince) "registered Optimal" and every bay came out empty. User: "deep analysis on all the new mods ... run a new whole check", and have Order fleet weapons work with Colony Manager so the colonies supply what production needs.

**What the log and save showed** (`fleet_diag_1105b`, research `modscan_1105_1605.txt`):
- The Guru's built-in `SKR_plagueCultist` throws every LPC except Seeker's three plague LPCs into cargo when the ship is shown (`applyEffectsAfterShipCreation`). Its fighter cost mods (-1000 on every role) made every wing cost 0 OP in the model, so the plan filled the bays with Daggers, a JYD Heavy Drone and an Arma Kouto; all four were back in fleet cargo in the save. The plague LPCs cost 1008-1012 OP everywhere else.
- Trinity Worlds `TrinityRetrofit` fills a Maquech's empty bays 0/1 with `nimbus_tw_wing_c` (10 OP each) on every stat refresh and deletes Nimbus LPCs from cargo when a TW ship is shown. The plan counted the Maquech's Nimbus drones as stock, moved them to other carriers, the Maquech got new ones for free (OP 65/45, so `tw_equipment_error`, -100% CR), and the moved ones were deleted later ("2 items weren't there any more", the Guru's empty bays on the next runs).
- Yunru's zero-OP "Install:" hullmods (`yunru_silvervale_conversion`, `yunru_automation`, `yurnu_install_alpha/beta/gamma`, `yunru_lobster_install`, triggered by `yunru_install_confirm`) turn into permanent conversions the moment a ship carrying them is shown. `JAOSearch.spendLeftover` took them as "harmless" leftover spending on ships with maxed flux and empty mounts: 13 ships ended up with a permanent `yunru_silvervale_flux` (energy weapon range for damage by captain personality; nothing for Steady).
- Plague weapons on normal hulls: the Thor carried a Dainsleif and a plague grenade launcher (-15% max CR), the Wyvern a plague Intercept launcher (-6%). The `plague` rule row existed but no code read it.

**Fixes** (`JAOCompat`, rules in `jao_rules.csv`):
- **Hull-bound wings**: an LPC tagged no_drop and no_sell stays in its bay. It's never stripped, counted as stock (`addMounted`), taken from cargo or storage (`buildSlots`) or moved by Optimize fleet.
- **Bay rules** (`bay_wings`): `SKR_plagueCultist` / `SKR_plagueBearer` fly only Seeker's `plague_LPC` list (read from the merged modSettings); `tw_trinity_retrofit` only Nimbus drones; `tw_enhanced_control_node` anything. A plan with bays left empty by a rule gets a note ("only flies Fire Ant wing, Pillbug wing and Beetle wing; none in stock").
- **Script probe**: after each fleet-plan fit, a scratch fleet member runs every hullmod's stat effects on a copy of the plan (`JAOCompat.probe`). Wings a script puts into empty bays join the plan (`ShipPlan.scripted`, never taken from cargo), the OP they take comes off capacitors then vents (vents first on carriers, phase, support, missile ships), and penalty hullmods a script adds (error/warning/malfunction/penalty) become a note. Apply runs `settle` again after `updateStats` and refreshes once more if it changed anything, so the TW penalty check sees a ship within its OP.
- **Plague**: weapons and LPCs in `seeker.plague_weapons` / `plague_LPC` carry `W.crCost` (3/6/12); `Ship.plagueMult` is 0 on `plague_immune` hulls and 0.666 with Hardened Subsystems; the score took `plagueFactor` = 1 - lost CR / 60 until 0.5.10, which prices it through `crStart` (see 0.5.10). Gated wing OP: a wing priced 1000+ OP is rated at the OP past 1000.
- **MagicLib interference** from every mod's modSettings (`MagicLib.interferences_weapons`), after the jao_rules rows.
- **Installers**: `spendLeftover` never takes a zero-OP hullmod (it spends nothing; free hullmods that help still come in through `addMods`); `never_add_ui,Installation` and `never_add` rows for `yunru_install_confirm`, `vice_apotheosis_protocol` and Roider's MIDAS levels; `never_remove,ix_dawnstar_*` (the player's Dawnstar mode).
- **purge_lpc** `nimbus_tw_*`: removed from cargo and every storage on load, before saves and after each apply (two were in Hot Springs storage).

**Production supply.** AoTD's production order is a Theory of Toolbox trade contract that takes goods from your colonies every month: Heavy Armaments for every weapon and LPC, Ships for LPCs, Electronic Components for most manufacturers (`AoTDProductionSpec`). Colony Manager 2.39.0 (its own session) reads the open orders and plans a producer for every good no colony makes (Special project supply), and keeps back the credits the orders still owe. Auto Outfitting's side: the order panel lists what the order takes, which colony makes each good, and which goods nobody makes (`JAOProduction.describe/unmade`); it says Colony Manager plans a producer when 2.39+ is enabled (`colonyManagerSupplies`, by mod id and version), else that those items wait. Queue all logs what all of AoTD's orders still wait for.

**0.5.18** (user 11/06 after Optimize fleet on 0.5.17: "a massive overflow of points now because they are not building out anything else").
- **Bug**: `JAOShip.check` counted every logistics hullmod in the set against a flat 2, s-mods and built-ins included; the game (`BaseLogisticsHullMod.getNumLogisticsMods`) skips built-in and perma mods. The Atlas, Prometheus and Colossus (cargo/fuel + drive field built in by 0.5.15's single refits) got only Treasure Hunt Package and 25/65, 25/95, 15/55 OP. Now the count skips built-ins, original perma mods and planned s-mods, and the limit is `JAOShip.maxLogistics` (maxLogisticsHullmods with the member's `Stats.MAX_LOGISTICS_HULLMODS_MOD`, skills). `runLogistics` uses the same limit, leaves perma mods alone when stripping, and checks the limit and OP before the slower `canAdd`.
- **Leftover OP**: `JAOOptimizer.SURVIVAL` after `logisticsOrder`: Reinforced Bulkheads (+hull, a disabled ship is almost always recoverable, so a keeper with s-mods isn't lost), then Blast Doors (+hull, fewer crew lost); the only non-logistics hullmods across the mod list with a campaign use (scan of every hull_mods.csv). Expected on the 11/06 fleet: Atlas TH + a second logistics hullmod + Blast Doors (60/65), Prometheus ~90/95, Colossus ~51/55, Buffalo 28/28, Dram 19/30 (logistics limit). Still no vents, capacitors or weapons. Checks 566 (runLogistics has no offline check). Not tried in game.

**0.5.17** (user 11/06: "capacitors and vents are completely useless on any civilian craft ... build in as many fleet-wide useful things as we possibly can on some of the biggest ships, like the Atlas ... and the Prometheus ... with a toggle ... Same thing with salvage rigs ... Treasure Hunt ... very valuable to have as an S-mod").
- **No flux on civilians**: `runLogistics` no longer fills leftover OP with vents and capacitors; it stays free.
- **Fleet-wide s-mods**: new setting `jao_civilianSmods` (default on). With Build in s-mods ticked, keepers (`JAOOptimizer.keeper`: cruisers, capitals, and salvage/survey rigs of any size via `salvageRig`, which now also looks for the built-in `repair_gantry`; the old check named `salvage_gantry`, which doesn't exist) build in `FLEET_WIDE` first: Treasure Hunt Package (1/2/4/8, x2 as an s-mod, summed fleet-wide by `Misc.getFleetwideTotalMod`, capped per source at 3x its base), Surveying Equipment (5/10/20/40, x2 as an s-mod, summed fleet-wide), High Resolution Sensors (fleet sensor range, summed with diminishing returns; built in it only frees a logistics slot), then burn mods. Only fleet-wide ones are built in now (before: whatever came first in `logisticsOrder`, so cargo holds on a freighter). Built-in ones don't count against `maxLogisticsHullmods`, so the ship's own job still gets its two slots. Optimize fleet plans them too (`planCivilian` passes `planSmods` and the plan's story points; before: never s-mods on civilians), and Confirm builds them in like the warships' s-mods.
- **Treasure Hunt first**: in `logisticsOrder` Treasure Hunt Package now comes right after cargo/fuel/berths for every civilian (before: after burn, profile and sensors, so a Buffalo never reached it).
Treasure Hunt on warships isn't valued (it would take a combat s-mod). runLogistics runs only in game, so no offline check covers it; checks 566. Not tried in game.

**0.5.16** (user 11/06: Mining Pods "probably got a bit too much of a value boost because they are zero ... they are pretty much just point defense where they stick to the ship itself").
- **Free wings**: `JAORatings.wingRaw` left `op` at its default 10 when a wing costs 0 OP, so a Mining Pod was valued as a 10-OP wing (in game about 112, offline 200, a Broadsword 160) and filled support bays for free (0.5.15 game log: 5 pods on 4 Moras, support bays 3 -> 10). Now a 0-OP wing's axes are taken at op 1 and priced by `JAOModel.freeWingOp`: the most OP at which it still matches its role's reference wing per OP on firepower and durability, 1..`FREE_WING_MAX_OP` 10. Mining Pod 2.3 OP (value 47), Brattice 4.5 (90), JYD Termite 5.7 (106), Dajeil drones 2.5 (50).
- **PD drones**: wings that never leave the carrier (range 0 or `stay_in_front_of_ship`) with nothing but PD guns (`JAOModel.pdDrone`; Mining Pods, Dajeil, Spriggan bits) are `w.pd`: ship damage x0.25 like a PD gun, full value toward PD, out of the fleet's damage mix (research ai_behaviour_1106 CF-5). Xyphos (PD share 0.46) and Sarissa are unchanged.
Fleet plan on the 11/06 evening save (fleet_diag_1106b, 4 Moras): pods 6 -> 1 (left on the Mora with two 25+ OP bombers), support bays 10 -> 5; anti-shield 50 -> 51%, armor-cracking 46 -> 46%. `JAOFleetSim -DoldFree=true` runs the old pricing. Checks 566. Not tried in game.

**0.5.15** (user 11/06: "Yes, do them": the two gaps 0.5.14 left, phase ships and running hot).
- **Phase ships**: `adaptive_coils` on a phase hull adds `s.extra` 0.10 (`JAOShip.ADAPTIVE_COILS`; Phase Anchor keeps 0.12); `armorHullEhp` stays linear on phase hulls; phase survival x(1 + 0.3 x sqrt(capacity / hull capacity)) (`PHASE_EHP`, `Ship.baseCap`) instead of a flat x1.3; jao_rules kind `not_on_phase` (`JAOCompat.notOnPhase` in `hullmodCandidates`), row `heavyarmor`. Phase hulls now: Adaptive Phase Coils on all, no Heavy Armor, more capacitors (Ziggurat 19, Doom 11). The phase cloak's flux while phased (dissipation keeps running) isn't modelled.
- **Running hot**: wasn't. Our fits average 1.22x their flux budget and the stock variants 1.47x; FLUX_SOFT 1.2 brought ours to 1.16 and made fits more kinetic-heavy, so it stays 1.4. Flux Distributor's lead (61% of the experts' hulls, theirs 19%) comes from the model rating extra dissipation above capacitors once vents are maxed; `SHIELD_REGEN_SECONDS` 20 (research meta_report 1: Flux Distributor beats capacitors past about 27 s) moved it only to 57% and dropped Stabilized Shields below the experts, so it stays 30.
Expert hulls, ours / theirs: Heavy Armor 0.25 / 0.28, Stabilized 0.29 / 0.25, Hardened 0.57 / 0.44, RFC 0.18 / 0.28, Flux Distributor 0.61 / 0.19. Endorsement 44% of hullmods, 28% of weapons. Checks 563. Fleet plan on the 11/06 fleet: anti-shield 53%, armor-cracking 59%. Not tried in game.

**0.5.14** (user 11/06: "Yes, do it": fix how armor is valued so Heavy Armor catches up).
- `JAOModel.armorHullEhp` replaces `hull + KA x armor`: the armor pool (KA x rating, standing for the cells) wears down in 10 steps while each hit of `INCOMING_MIX` (kinetic 120 x0.30, HE 350 x0.20, HE 1000 x0.10, energy 200 x0.30, frag 60 x0.10) is cut by the armor still standing (`armorFactor`, floor 15%), then the hull through the 5% floor; x `ARMOR_EHP_SCALE` 0.614 so the 234 vanilla hulls average what the linear term gave; blended `ARMOR_SHAPE` 0.5 with the linear term.
- Prototype (research ai_work, python): the pooled-rating version (pool = rating) made armor nearly worthless (hull dominated); pool = KA x rating doubled survival everywhere, hence the scale. Full per-hit curve: Heavy Armor on 43% of the expert hulls (theirs 28%, phase ships included); blended 0.5: 29%.
- `JAOSearch.pairSwap` is source-neutral: every source of a top weapon is tried until one is in stock (an owned copy fitted elsewhere had hidden the blueprint copy; JAOFitCheck "owned + blueprint sources pick the same weapons").
Expert hulls, ours / theirs: Heavy Armor 0.29 / 0.28, Stabilized 0.29 / 0.25, Armored Weapons 0.18 / 0.22, Hardened 0.57 / 0.44, RFC 0.18 / 0.28, ITU 0.68 / 0.53, Flux Distributor 0.61 / 0.19. Expert endorsement 46% of hullmods (42% in 0.5.13), 27% of weapons. Checks 563. Fleet plan on the 11/06 fleet: anti-shield 53%, armor-cracking 59%. Not tried in game.
Still open: phase ships take Heavy Armor (phase hullmods other than Phase Anchor have no modelled value); Flux Distributor stays high because fits run hotter than the experts' 0.8-1.1x flux (FLUX_SOFT 1.4).

**0.5.13** (user 11/06: "Yes, do it": model incoming fire on shields so Hardened Shields gets its due).
- First try: incoming fire (`INCOMING` {150, 300, 500, 800} x `SHIELD_BLOCK` x shield efficiency x damage taken) off the guns' flux budget. Hardened Shields rose, but every fit went flux-cheap: Conquest 6 Arbalests, Antimatter Blaster spam on a Hammerhead, Safety Overrides on a Medusa (SO doubles dissipation; it can't vent). The expert rule is vents until the main guns' flux about equals dissipation, so the guns' budget stays as it was.
- Kept: `JAOModel.duty` (research ai_behaviour_1106 SF-6): engaged t_e = 0.6 x capacity / incoming shield flux, break t_r = 0.6 x capacity / (2 x dissipation x vent rate; plain dissipation with Safety Overrides) + 5 s; score x t_e / (t_e + t_r). `SHIELD_BLOCK` 0.35 (0.5 put Hardened Shields on 75% of the expert hulls against their 44%). Faster venting now counts here (VENT_W removed). Logged as `stay`.
- Search: two starts (`improve` biggest mounts first, then smallest first, best kept); `pairSwap` (every pair of medium/large mounts with their best 4 candidates each); `fillEmpty` (an empty mount takes its best weapon if that costs at most 2%). The Medusa had scored a third below its own earlier fit under the tighter-flux model; offline fits average 58 ms, at most 243.
- Expert hulls (32 slot-mapped loadouts) vs ours: Stabilized 0.29 / 0.25, Hardened 0.68 / 0.44, Flux Distributor 0.64 / 0.19, Heavy Armor 0.07 / 0.28, RFC 0.21 / 0.28; expert endorsement 42% of hullmods, 27% of weapons. HE guns 0.10 of gun damage on vanilla hulls (experts 0.19), armor-cracking 0.29 (0.24): missiles and energy carry the armor work.
Checks 563 (staying time; Balanced mount use 0.84, an OP-bound Valkyrian capital). Fleet plan on the 11/06 fleet: anti-shield 53%, armor-cracking 65%, settles in 3 passes. Not tried in game.
Still open: Heavy Armor under-valued and Flux Distributor over-valued against the experts (armor's damage reduction per hit is linear in the survival term).

**0.5.12** (user 11/06: "Yes, do them", the five items 0.5.11 left open).
- **Doctrine**: `JAOShip.personalityOf` takes an officer-less ship's personality from the player faction's doctrine aggression (`FactionPersonalityPickerPluginImpl`: 1 cautious, 2 steady, 3 aggressive, 4 aggressive/reckless, 5 reckless); it was always steady.
- **ITU off frigates**: new jao_rules kind `min_size` (hullmod id -> smallest hull size), row `targetingunit,DESTROYER`; `JAOCompat.tooSmall` in `hullmodCandidates`. Destroyers stay with the model.
- **Omni conversion**: `adaptiveshields` on a FRONT-shield low-tech hull (manufacturer contains Low Tech) turning <= 30 deg/s gets `s.extra += 0.04` (`OMNI_BONUS`): the AI blocks torpedoes and big shells with it (several sources, builds_update_1106 2.5); the stats only see the arc x0.7.
- **Weapon groups** (`JAOGroups.arrange`, after `autoGenerateWeaponGroups` in JAOOptimizer and JAOFleetApply): the game already gives each weapon its own group (mechanics 3.1), so (a) on broadside hulls (side mounts 40%+ of the guns' size weight) a group with the same weapon on both sides splits into left and right while under 7 groups; (b) on missile boats (missiles 30%+ of weapon OP) groups of 2+ conserved launchers (max ammo under 20) are LINKED for volleys (tournaments_1106 rule 3). Logged.
- **Expert loadouts slot-mapped**: `research\auto_outfitting\ai_work\refs_from_builds.py` turns builds_update_1106's blocks into JAORefBench refs (`ai_work\expert_refs.txt`, 32 gunships on 28 hulls; carriers and unknown hulls skipped). `ao_refbench.ps1 -Props refs=<file>`. The mapping is partial (about a third of mount weight unmapped on average), so score ratios understate the experts; the hullmod and damage-profile comparisons are the useful part. They showed Expanded Magazines (0.28 of expert builds, 0 ours) and Resistant Flux Conduits (0.28, 0 ours), both unmodelled.
- **Charged guns** (`JAOModel.chargedDps`; `W.burstDps/ammoMax/regen` from `usesAmmo` + `getAmmoPerSecond`): recharge-limited dps plus the stored charges, emptied `MAG_REFILLS` (3) times a fight: Autopulse 300 -> 450, with Expanded Magazines 525, s-mod (+50% recharge) 675. Ship `ammoBal/ammoEn` (`get*AmmoBonus`) and `regenBal/regenEn` (`get*AmmoRegenMult`); flux scales with the dps.
- **Resistant Flux Conduits**: `Ship.empTaken` (`getEmpDamageTakenMult`) and `ventRate`: score x (1 + EMP_W[size] x EMP avoided) x (1 + 0.03 x extra vent rate), EMP_W {0, 0.01, 0.025, 0.05}. First tried at {0.02..0.06} with vent 0.08: RFC on 52% of hulls, frigates included; now capitals and Remnant units.
Checks 559 (charges, RFC). Vanilla bench: ITU 65% -> 43% of hulls, RFC 0 -> 24%. Fleet plan on the 11/06 fleet: anti-shield 54%, armor-cracking 61%. Built and checked on Java 28 (jtbuild\game_java.ps1). Not tried in game.
Still open: experts take Hardened Shields more and Stabilized Shields / Flux Distributor less than the model (the model has no incoming-fire flux on the shield, which is what makes Hardened Shields worth flux); the s-mod planner isn't in the offline bench.

**0.5.11** (user 11/06: "go online and do even more research on builds and build philosophy ... tournaments ... what the AI uses and how the AI fights ... make this mod literally as good as we can possibly make it"). Four research reports in `research\auto_outfitting`: `ai_behaviour_1106.md` (the combat AI decompiled into `ai_work\out\ai`; Q1-Q8 with line refs and a top-10), `tournaments_1106.md` (Brawl King S1/S2, older forum tournaments), `builds_update_1106.md` (Grievous69's and GrumpyThumper's 0.98 guides, 15 forum threads, ~130 Reddit threads; 59 reference loadouts), plus `ai_work\weapon_tiers.json`. New offline harness `jtbuild\JAORefBench.java` / `ao_refbench.ps1`: fits every vanilla gunship hull from all vanilla weapons and scores the 161 vanilla goal variants with the same model, with term gaps, damage profiles, hullmod frequencies, `-Dmodvalue` (each hullmod's worth) and `-Dalts` (per-slot alternatives); `OfflineWorld.extend()` models 17 more vanilla hullmods. `ai_work\endorse.py` measures the share of our picks the expert loadouts mention (26% -> 28% weapons, 37% -> 39% hullmods).
Changes, from code (ai_behaviour_1106):
- Missiles one by one in `phases` (MI-1/2): armor math per missile; `missileUse` = STRIKE_USE by target size (unguided {0.05, 0.3, 0.7, 0.95}, guided {0.05, 0.95, 1, 1}) or 0.5 + 0.5 SIZE_HIT; `pdSurvival` = 1 / (1 + 0.25 x 100 / missile HP) (`W.projHP`); free-fire missiles (20+ shots, not STRIKE) send 60% of non-kinetic damage into a raised shield. Reference run: Annihilators 71 -> 13 mounts, Harpoons 3 -> 26.
- `SHIELD_USABLE` 0.8 of capacity for shield HP (SF-1); `AUTOFIRE_CAP` 0.3 (0.45 frigates/destroyers) instead of 0.5 in uptime, `BURST_CAP` 0.6 -> 0.4 (SF-3); `VENT_TARGET` 1.1 -> 1.25 in `fluxFill` (SF-2); `SHIELD_UPTIME` 0.6 on upkeep.
- Shield-efficiency hold only on the main group (`mainId`, the heaviest non-missile group), 1 - 0.6 x clamp((X - r)/0.6) with X 0.9 steady / 0.75 aggressive / 1.0 cautious (SF-5); other callers keep the old 0.5 rule.
- Carriers: escort when >= 66% of ranged wings are non-bombers (75% and half interceptors/support with an aggressive captain) (CF-1); wing reach capped by the shortest ranged non-support wing (`reachCap`, CF-2); Defensive Targeting Array (`Ship.dta`, wing range 0) bombers x0.2, interceptors/PD wings x1.3, fighters x0.8 (CF-4).
- Turret tracking `track()` per target size: (turret turn x turn mult + half the ship's turn when bow-facing) / {19, 9, 5, 3} deg/s, floor 0.35, beams half (AIM-2); `W.turnRate`, `Ship.turnRate`.
- Systems: Accelerated Ammo Feeder x1.29 ballistic rate at the same flux/s, High Energy Focus x1.075 energy damage (SYS-2/3).
From the research and the harness:
- Handling only counts on slow hulls: maneuver exponent x clamp(20 / hull turn rate, 0.3, 1) x (0.6 + 0.4 hardpoint share) (`TURN_REF`, `Ship.baseTurn`). Aux Thrusters 59% -> 20% of hulls (the user's fleet had it on all 16).
- Point defense: `pdKill` = clamp(dps x max(0.5, range/400) / 300, 0.2, 1) (one weapon killing a 300-HP missile in time; the AI's PD picks a random missile), and `PD_EXTRA` 0.04 up to 3x the floor. Burst PD 41 -> 74 mounts, LR PD 63 -> 36. PD_NEED 60/130/250/400 was tried and reverted: PD-filled destroyers (the user's 11/02 complaint, JAOFitCheck PD share).
- Community tiers (`jao_weapon_tiers.csv`, 85 vanilla weapons from Grievous69's lists; `JAORatings.prior`, `W.prior`, setting `jao_communityTiers`): x(1 + 0.08 (score - 6)/4) on a weapon's dps and pool, so it only settles near-ties (LR PD and Burst PD were within 1%).
- No point defense at all: x0.95 more (tournament rule 1: such ships back off from every missile).
- `JAOSearch.swapMods`: replaces one hullmod with another when the trade beats both (up to 3 rounds after the main loop).
Checks: 558, 0 failed (escort tests rewritten for the game's rule; leftover-OP checks allow 1-2 OP when vents and capacitors are both maxed; kinetic missiles count as anti-shield). Offline fleet plan on the 11/06 fleet: anti-shield 54%, armor-cracking 60%. Not tried in game.
Not done then (done in 0.5.12 unless noted): doctrine aggression; ITU off frigates; Omni conversion on slow armored hulls; weapon groups; expert loadouts slot-mapped. Still open: HEF hulls favouring Tachyon Lance over HIL; Breach/Hydra/DEM re-rates.

**0.5.10** (user 11/06: "the Coda launcher is subtracting combat rating from the max ... factor that into your build plans ... make sure we're also analyzing the d-mods of all the ships and building against them"). The Coda Launcher (`SKR_pdGrenade`) is a WEAK plague weapon: `SKR_plague` (hullmod `SKR_plagueWarning`, shown as CONTAMINATION DANGER) takes 3 points of max CR per copy; the score had priced it since 0.5.2 with a flat `plagueFactor` (5% a Coda, 20% a STRONG item) whatever the ship's CR. The scratch member's live stats already carried most d-mods (armor, hull, flux, speed and handling, shield damage taken, ballistic/energy range, peak time); the ones that worked through stats the model never read:
- **Max CR** (`Ship.maxCr`, `JAOShip.maxCr`): 0.7 plus every flat modifier on `getMaxCombatReadiness` except the crew ones and `SKR_plagueWarning` (the score adds the plague from what it fits), times its mult. Degraded Life Support, Faulty Automated Systems and Increased Maintenance take 5 points each; skills and hullmods that raise it now count. `crEffect(c)` follows `CRPluginImpl`: aim accuracy 1.5c - 0.5 weighted `CR_AIM` 0.25 of hits; above 70% +12% at 100% (damage, damage taken, speed: 10% each); below 50% -12% at 0; malfunctions below 40% (-20% at 0) and critical ones below 20% (-40%). 1 at 70%. `crStart(max, deploy)` averages the first deployment and the next one (`Ship.deployCr` = hull CR to deploy through `getCRPerDeploymentPercent`). The plague is `crStart(maxCr - plague/100)`: on a 70% ship with a 12% deployment a Coda costs 1.3%, a STRONG item 5.6% (9.2% on a 55% ship, 7.7% on an 85% one that loses its bonus), a MILD on a 55% d-mod ship more than on a healthy one; the old 1/60 slope charged several times the game's effect. The PPT term `cr` is unchanged.
- **Carrier deck** (`Ship.deck`, `deckFactor`): wing value x (1/refit mult)^0.5 x (1/replacement decrease mult)^0.25 x (replacement increase mult)^0.15 x (0.85 + 0.15 engagement range mult) x `JAOCompat.wingMult`. Damaged Flight Deck x0.88, Malfunctioning Comms x0.94, Expanded Deck Crew x1.08 (it read as worth nothing before: replacement rate is a dynamic stat). `wing_mult` rule rows cover hullmods that change the craft (`applyEffectsToFighterSpawnedByShip`, never run on the scratch member): `defective_manufactory` 0.88, scaled by the ship's `dmod_effect_mult`.
- **Turrets** (`turnMult`, `beamTurnMult`, `recoilMult`): turret PD hits x `pdTrack` = turn mult^0.25 (Damaged Weapon Mounts x0.75: 0.93; Advanced Turret Gyros +75%: 1.15), spread x max recoil mult in PD and in `accuracy` (Damaged Weapon Mounts x1.3, Ill-Advised x2).
- **Malfunctions** (`Ship.malfunction`): score x (1 - 1.5 x weapon malfunction chance), Ill-Advised 5% -> x0.925.
Logged per fit (`maxCr`, `crStart`, `deck`, `malf`, `d-mods [...]`); the Optimize fleet panel says how many ships have d-mods. The 11/06 15:01 save (`fleet_diag_1106`): 26 ships, no d-mods; plague weapons on the Taygete (Assault Revolver, MILD) and the Alcmaeon (Charger, WEAK), and one on the Zealot (plague hull, free). Not tried in game.

**0.5.9** (user 11/06: "Optimize entire fleet does not take into account the civilian ships and should ... attuned drive field, surveying equipment, salvage gantries ... maybe just have it skip the s-mods"). With "Civilian ships: fit for the campaign" on, Optimize fleet plans civilian hulls too (`JAOFleetPlan.civilianShips`, `ShipPlan.civilian`, `planCivilian`): `JAOOptimizer.logistics` runs `runLogistics` inside the plan, stripping from bare (`strip = planning || STRIP`) and with no s-mods in planning (`smodRoom` 0). Planned once, no stock taken, no share in the fleet mix; what they carry joins the stock. Order fleet weapons still skips them.

**0.5.8** (user: "gyrojet cannons are very overvalued ... slow moving ... basically missiles ... I've seen a couple team kills"; then "anything that's cheat available only shouldn't be included in calculation and shouldn't be added; maybe a toggle"). Yunru's gyrojets are unguided ROCKETs (turn rate 0, launch speed 50, max 500, collision MISSILE_FF) at 240 / 557 / 1,533 kinetic per rocket, tagged restricted/no_sell/no_dealer, rarity 0, no blueprint, on no ship. Model: `missileReliability` (guided 1, poorly guided 0.85, unguided 0.75, x0.9 under 600 speed, friendly-fire rockets x(1 - 0.25 x min(1, hit/1000)), floor at hit 200); unguided counts as friendly fire (the missile spec doesn't expose its collision class; vanilla's unguided rockets and torpedoes are MISSILE_FF, guided ones NO_FF). `Eff.rel` carries it, and `missileShield` now applies it and the target-size hit too (kinetic missiles had counted every round into shields: the all-weapons fleet's 70% anti-shield). Offline plan on `fleet_diag_1105c`: kinetic 63% -> 41%, energy 4% -> 31%, anti-shield 70% -> 61%; no Gyrojet Autocannons or Twin Rockets (11 and 23 in game). `jao_cheatItems` (off): `JAOCompat.cheatOnly` = no `_bp` tag, no NPC faction knows it, no non-fighter ship variant carries it in a normal mount, not omega/threat/dweller; the player faction's known lists don't count (console allblueprints). Applied to fit candidates and fleet-order blueprints.

**0.5.7** (user: "check missiles"). In the all-weapons fits finite ammo was over half of a 90-s fight's damage on 9 ships (96% on the CFT Galleon), most with Expanded Missile Racks and 0 vents. `phases` spreads a launcher's whole pool over the fight window (`missileWindow`), with no cap at what the launcher can fire: a CFT Twin Rocket (20,000 damage at 200 dps, 100 s to empty) was credited 222 dps, and 444 with racks; Squall and Neutron Pod hold 156-160 s. `W.poolRate` (the weapon's dps while it has ammo) now caps the pool at rate x fight length, so ammo past what fits in the fight is worth nothing and Expanded Missile Racks only pays on launchers that empty inside it. Outlier: Yunru's `yunru_gyrojet_auto` (restricted, no_sell; ~1,530 per shot, 300 rounds, ~460,000 damage per mount in the data) stays huge even capped (about 380,000 in 90 s); only reachable with cheats. Longer real battles: raise "Fight length" to favour sustained weapons and vents.

**0.5.6** (user 11/05: "check what happened when we had access to every single weapon"). Console `allweapons`, `allwings` and `accallhullmods all`, then Optimize fleet on 28 ships (`fleet_diag_1105c`): anti-shield 49% -> 70%, armor-cracking 40% -> 67%, no empty mounts or bays, no errors. The fleet went missile-heavy (CFT Volley Rocket x43, CFT Twin Rocket x23, RAT P-Wave x18, Yunru gyrojets x21, with Expanded Missile Racks and autoloaders), which is also why many ships ran 0 vents. Three faults:
- `yunru_bayblade` ("Let's Bayblade!", 0 OP, turn rate x50; its spinning-ship check is commented out in Yunru's code) on all 28 ships: the mobility term had no upper bound. Now `SPEED_CAP` 1.5 and `MANEUVER_CAP` 2 in `JAOModel`, plus a never_add row.
- `yunru_irt` (Inverted Reverse Thrusters: speed, acceleration and turning x (decel + accel) / accel, about x1.7-2; no flying backwards) on most ships: the lost reverse is invisible to the stat diff. Now in `JAOShip.KNOWN_DRAWBACKS`, so only with "Allow hullmods with drawbacks".
- `cutting_beam_ix` (0 OP, 600 dps; an IX built-in) mounted as a free gun. Candidates with base OP 0 are skipped (3 such weapons in the whole modlist).

**0.5.5** (user 11/05: Optimize fleet under 0.5.3 still put Daggers, a JYD Heavy Drone and a Kouto on both Gurus). `jao_rules.csv` was read with `getMergedSpreadsheetDataForMod("kind", ...)`. The game's merge (`LoadingUtils`) takes a file as the master mod's only when its folder path contains the master id; "Jonah's Auto Outfitting" doesn't contain `jonahs_auto_outfitting`, so the file went through the id-deduplicating loop and only the **last row of each kind** survived, since 0.1. bay_wings kept only the Enhanced Control Node row (`*`), skip_slots only radiant_ix, never_add / never_remove / interference one row each. Rules are now read with `loadCSV(RULES, MOD)` (no merge), and the load logs the count per kind. jao_hullmods.csv and jao_weapon_effects.csv are keyed by unique ids, so the merge didn't hurt them.

**0.5.4** (user 11/05: "if somebody just clicks the button and it uses them all, they'd be super pissed"): Optimize fleet builds s-mods only while the Autofit panel's "Build in s-mods" box is ticked, read from `SavedVariantData.getOptionState()` (`JAOGoals.optionChecked`; the game saves every box's state there). The box is off by default; the `jao_fleetSmods` setting (default on) is gone. The panel and the button tooltip say which way it is.

**0.5.3**: Colony Manager's session confirmed 2.39.0 (unreleased until the troubleshooting round ends) reads each order line's `getReqResources()` (exact remainder) and plans producers only for goods no colony makes; a good made in small amounts isn't boosted. The order panel says so whenever Colony Manager 2.39+ is enabled.

**Checks.** `jtbuild\ao_modscan.py` (every enabled mod: scripts that change loadouts or cargo, installers, zero-OP hullmods, hull-bound and gated wings, unsellable weapons). JAOFitCheck 542 checks (plague factor and copy, zero-OP leftover); 552 in 0.5.10 (CR curve, plague on a d-mod ship, deck, PD tracking, recoil, malfunctions). Research data regenerated for 92 sources (`data_1102` kept); the all-mods run fails 16 of 536 vanilla-tuned checks, driven by the new mods' weapon numbers (CFT's 7-OP 1,040-dps autocannon and 15,000-damage volley rockets, Yunru's 0-OP fighter gun), not by this change. Fleet sim on `fleet_diag_1105b`: 15 ships, settles in two passes.

**Not verified in game:** the cultist bays staying empty without plague LPCs, the Maquech keeping its Nimbus drones within OP, and the order panel's supply lines.

## 8. Hullmods

R: `hullmods_vanilla.json` (129 vanilla, effect numbers with file:line), `hullmods_work\report_preview.txt` (the report; a hook stopped the agent writing the .md), `hullmods_mods.json` + `modded_report.txt`.

- **Value of a hullmod** = the gain in overall score from applying it, divided by its OP. It's compared with the same OP spent on vents (10 dissipation/OP), capacitors (200 capacity/OP) or a weapon upgrade.
  - Greedy, re-scored after every pick because mods interact: SO range cap, range stacking, shield mods vs Shunt.
- **Known effects** come from a shipped table `data/config/jao_hullmods.csv`. Vanilla rows are filled from `hullmods_vanilla.json`; mod rows from `hullmods_mods.json`.
- **Unknown mod hullmods** are measured, not guessed. Apply the mod to the scratch member and diff the stats: armor, hull, flux, dissipation, shield taken, ranges, speed, ROF, damage taken. This covers any mod hullmod that works through `applyEffectsBeforeShipCreation`. Listener-only effects need a table row.
- **Never for combat**: logistics/civilian mods (setting), Recovery Shuttles, Converted Fighter Bay, Neural Interface/Integrator, ECM/Nav Relay/Operations Center (fleet-level), Escort Package (context-only).
- **Code facts that change values** (vs the tooltips):
  - Heavy Armor has no maneuver penalty unless S-modded.
  - ECCM cuts missile range to ×0.8.
  - Integrated PD AI only turns small weapons into PD when S-modded.
  - ITU always beats DTC when both are known.
  - Ballistic Rangefinder adds to base range (capped 800/900).
  - Flat range bonuses aren't scaled by percent bonuses.
  - Safety Overrides keeps 25% of range past 450.
- **Prototype**: R: `tools\hullmod_reco.py` → `hull_hullmods.md`, picks for all 1,095 combat hulls. Run with the hullmod report's first formulas, it matches only 29% of the combat hullmods designers put in stock goals. The misses show what to calibrate:
  - **Range**: a flat range curve undervalues ITU on 900-range capitals. Fix with the AI engagement model (§6) and an exchange term: shots taken while out-ranging.
  - **Armor vs shield**: armor is overvalued against shields (Shield Shunt picked on Onslaught/Conquest). Fix kA, and tank share for high-efficiency shields.
  - **Filler mods** beat vents too easily (Auto-repair, Turret Gyros, Resistant Flux Conduits). Lower their constants.

## 8b. Modded content rules

R: `modded_report.txt` section 2, 700 mod hullmods in `hullmods_mods.json`, weapon quirks in `weapons_mods_quirks.json`. Shipped as `data/config/jao_rules.csv`.

**OP**
- Price every weapon and wing through the engine (`getOPCost(stats, variant.getStatsForOpCosts())`, `computeOPCost`) after hullmods are on. About 25 mod hullmods change weapon OP; Valkyrian BrynCore does it per weapon through a listener.
- Negative-cost hidden hullmods attached by mod code (ASM relic, TW, IX ECM, Diktat reverter, RAT Exceptional Construction) really add OP. They must survive the strip; vanilla's strip only removes what `canAddRemoveHullmodInPlayerCampaignRefit` allows, and we never remove hidden mods.

**Re-read the variant after every hullmod add.** Many mods silently delete conflicting hullmods: TBJ, TW and Vice targeting cores remove ITU/DTC; Amorphous Alloy removes Heavy Armor; missile forges remove Expanded Missile Racks and the Autoloader; Arma frames remove IPDAI and SO; and more.

**Never fill or clear code-owned slots**:
- Arma Armatura frames (built-in slots driven by ~50 selector hullmods);
- A_S-F Phantasmagoria, SEEKER Siegfried;
- ET-IX/TW slots on paragon_ix_elite WS 001/002 and Tigershark IX/TW WS 004.

**Never add**:
- work orders, RAT alterations, System Reset;
- item or story-point installs (IX stabilizer/panoptic, ET masker/desync, Arma OVERLORD);
- `*_handler`, `*_error`, `*_checker`, `*_discount_*` helpers;
- selectors.

**Never remove**:
- anything hidden or built in, mode/selector hullmods;
- `rat_controller`, `rat_exceptional_construction`;
- Starship Legends `sun_sl_*` (carry ship traits);
- AotD hull mods, IndEvo defects/pets.

**Wings** (0.5.2, §7l)
- No_drop + no_sell LPCs stay in their bays (scripts place them and delete them from cargo).
- `bay_wings`: Seeker plague hulls fly only plague LPCs, Trinity Worlds hulls only Nimbus drones (Enhanced Control Node carriers anything).
- Bays a hullmod script fills are found by a scratch-member probe and kept in the plan.

**Weapons**
- At most one `feedback_weapon` per ship.
- MagicLib interference weapons cost (n−1) × 20/40/80 dissipation each when 2+ are fitted (jao_rules rows plus every mod's modSettings).
- SEEKER plague items cost 3/6/12% max CR each on hulls that aren't plague hulls (×0.666 with Hardened Subsystems), scored by `plagueFactor` (0.5.2), by the CR curve `crStart` since 0.5.10.
- Fragment weapons need `fragment_swarm`.
- `vice_seeker` and `archaic_c` weapons only get range from their own hullmods.
- A_S-F artillery hulls need a target designator fitted.

**Hull-wide modifiers** mostly show up in the scratch-member stats: TTE_bolt energy +20% damage / −20% flux / +15% range; TW trinity range ×0.9; TBJ +70–100% range.

**Custom AI**:
- missile AI: armaa, A_S-F, IndEvo, Imperium, SEEKER, SWP, Valkyrians;
- ship AI: Imperium, KoL, SotF, SWP, Underworld.
- Real value there can differ from the vanilla-AI model. These are not penalised; just noted.

**Mod bugs found** (no action, FYI):
- `valk_fluxfog`: −0.2% damage taken, tooltip says −20%.
- `stardust_DriveFieldOvercharge`: supplies ×2.25, tooltip says +50%.
- TW relic range penalty is never registered.
- Reactive Combat Shields misspells the Immanence Engine id.

## 9. Optimizer

`JAOOptimizer`, stages in the class javadoc.

1. **Profile and pool.** Strip through the delegate, sync once, scratch member, reference targets (cached per session), available weapons grouped by spec.
2. **Candidates per slot.** Specs that fit, honour mount arcs (unguided missiles need the front: vanilla's 20°/45° rule), have quantity, and pass the price/option filters. Mirror pairs (same size/type, mirrored angle) are one decision when enough copies exist.
3. **Initial fill.** Big slots first. For each slot take the candidate with the best marginal score per OP, against the TTK of the build so far. Balance falls out of TTK: a second kinetic adds little once t1 is short, HE gains while t2 dominates.
4. **Improve.** Single-slot swaps and pair swaps until no swap improves the score. Bounded: at most N passes, deterministic order.
5. **Carriers.** Bays get the best wing by value per OP. Interceptors/PD wings if PD is short, bombers for Anti-armor, fighters otherwise. Replacement-rate math from `mechanics` 7.
6. **Hullmods, vents, caps.** Greedy per OP (§8), re-scored each step. Then loop 3-6 once more with the OP split moved (drop the worst weapon for a hullmod, or the reverse) while the score improves.
7. **Apply.** `delegate.fitWeaponInSlot(slot, entry, variant)` with engine slot objects, `fitFighterInSlot`, `variant.addMod`, vents/caps. Then:
   - record purchases (`recordWeapon/recordFighter`, so `getCreditCost` is right);
   - `getWeaponGroups().clear()`, `setMayAutoAssignWeapons(true)`, `autoGenerateWeaponGroups()`;
   - display name;
   - `syncUIWithVariant`.
8. **Modules** (multi-module ships): each module gets the same weapon optimization on its own slots, with no hullmods (vanilla rule).

**Runtime**: up to roughly 60 slots on the largest hulls and up to ~200 distinct available specs. Greedy plus swaps over cached per-weapon numbers is O(slots × candidates × passes). TTK is closed-form per phase, plus a 10-step armor integral.

## 10. When the player doesn't have the ideal weapons

- **Only the pool counts.** TTK picks the best mix of what exists. With no kinetic weapons in stock, Balanced leans on energy/HE and spends more OP on flux. Anti-shield falls back to the best shield DPS available.
- **An empty slot is allowed** when no available weapon raises the score; its OP goes to hullmods/vents. A weak gun with net-negative flux cost isn't forced in.
- **Bought weapons** count by credit cost only through the panel's own checkboxes. A mild tie-break prefers owned items, as vanilla does.
- **One weapon, several ships**: the fit is per ship in the order the player fits them. "Mass autofit" in the fleet tab runs the plugin per member, so earlier ships take the best items. This could later be ranked by ship size.

## 11. Settings

Implemented in the scaffold: `JAOSettings` + `LunaSettings.csv` + `data/config/jonahs_auto_outfitting.json`.

| Setting | Default | Notes |
|---|---|---|
| Add the Optimal goal | on | |
| Build style | Auto | Auto, Balanced, Anti-shield, Anti-armor, Hull killer |
| One goal per style | off | Extra cards only fill free slots. |
| Range | Auto | Long / Close shift R_eng and the range factor. |
| Fight length | 90 s | T for uptime, missile pools, peak-time penalty. |
| Value special weapons | on | Uses `jao_weapon_effects.csv`. |
| Prefer rare weapons | off | Bias × (1 + bonus × rarity score), §7c. |
| Rare weapon bonus | 0.15 | 0-0.5. |
| Respect priority weapons | on | Faction doctrine marks. |
| Point defense floor | 1 | × PD need. |
| Allow logistics hullmods | off | |
| Log every fit | off | Scores and reasons to starsector.log (no on-screen messages). |

Removed after the engine research: "Build in hullmods" (refit autofit always gets 0 s-mods).

## 10b. Changes after the first in-game look (2026-11-02)

**Legion crash.** `oneFeedbackWeapon` called `getWeaponSpec` on fighter wing ids, which throws, so every Legion fell back to vanilla (14× in starsector.log). Fixed: wings are skipped.

**Empty mounts were a model artifact, not optimal.** Three causes:
- offense and survival were weighted equally, so capacitors and defensive hullmods competed with guns OP for OP;
- the duel model gives no value to flank coverage, extra targets or soaking hits;
- the flux wall.

Fixes:
- **Offense weight**: score = offense^0.6 × (EHP × outrange)^0.4.
- **Empty-mount cost** (`JAOSearch.EMPTY_COST` 0.3): applied to the slot-size-weighted share of choosable mounts left empty.
- **Result**: Balanced fits leave about 1% of mount size empty in the offline check (it was large).

**Accuracy**: `JAOModel.accuracy`, a per-target-size hit share for projectiles (shot speed and spread) and unguided missiles. Frigates dodge slow spread shots; beams and guided missiles always hit.

**MagicLib interference**:
- `jao_rules` interference rows → `W.interference` (20/40/80);
- each costs (count − 1) × its strength in dissipation, halved by Resistant Flux Conduits (`interferenceMult`);
- it enters the flux budget and the vent fill.

**Wings**:
- sortie uptime from refit time (0.75 → 0.5);
- toughness from fighter hull + 2 × armor (0.6-1.2).
- Different wings on several identical carriers is expected: each fit takes the best LPCs still in stock.

**Stardust Ventures proton guns** are genuinely that flux-efficient in their CSV (Light Proton Gun: 25 damage, 2 flux per shot, about 12 damage per flux). The new setting **"Never use these weapons"** (`jao_skipWeapons`, ids or patterns) plus the `never_use` rules kind lets the player exclude any weapon or mod.

**S-mod planner**: now a beam search over sets (width 3), not greedy (greedy took Heavy Armor first when ITU + another mod was the better pair). "Waiting" is reported even when nothing known is worth it.

**Known hullmods** are the delegate's `getAvailableHullmods()`, the same list as the refit's hullmod picker: learned (saved in the campaign) + always-unlocked, minus hidden, minus spaceport-only when not docked.
- On save KalPhit (11/02): 85 learned + 33 unlocked = 118 available. The Optimal fit tries 80; s-mods 59.
- **Excluded**:
  - 18 logistics (setting off);
  - 9 never_add (work orders, System Reset);
  - 9 fleet-level or campaign-only (ECM, Nav Relay, Ops Center, Escort Package, Recovery Shuttles, Stardust Harmonic/Overcharge);
  - for s-mods also 19 `no_build_in` (Safety Overrides, ET adaptive subsystems, conversions) and 2 penalty s-mods.
- Missile Autoloader and Phase Anchor were wrongly left out (listener effects). They now have modelled effects (`JAOShip.MODELLED`).

**Safety Overrides** (user: "polarizing ... needs to be very carefully weighted"):
- **Setting** `jao_safetyOverrides`: Never / Frigates and destroyers (default) / When it scores best. The candidate filter applies it; the game already bars SO from capitals.
- **Model costs**:
  - peak time is judged against a whole battle (max(180 s, 2 × fight length)), so ×0.33 peak time costs readiness unless Hardened Subsystems offsets it;
  - a range cap removes up to 60% of the firepower share from main guns longer than threshold + 100 (550 for SO);
  - no venting costs ×0.93.
- **Offline result**: SO in 0 of 42 vanilla fits. Every fit that picks it must have a long-gun share ≤ 25% (`JAOFitCheck`).

## 11a. S-mods with story points (user request 2026-11-02, built in 0.2.0)

**Switch**: the autofit panel checkbox **"Build in s-mods (story points, permanent)"** (`jao_build_smods`).
- Off by default; the game remembers it per save, like its own boxes.
- It only applies to Optimal fits.

**Settings**:

| Setting | Default | |
|---|---|---|
| S-mods per ship | 3 | within the ship's own limit, `Misc.getMaxPermanentMods`: 2, +1/+1 with skills |
| Minimum gain per s-mod | 2% | |
| Wait for the best hullmods | on | |
| How much better to wait for | 10% | |
| Story points to keep | 0 | |

**Planner** (`JAOSMods`):
1. Candidates are the known, buildable hullmods:
   - not `no_build_in`, hidden or never_add;
   - combat value per `jao_hullmods.csv`;
   - not a mod whose s-mod effect is a penalty (`smodPenalty`: Heavy Armor, Expanded Missile Racks, Missile Autoloader, Fragment Swarm). The model doesn't score maneuverability or rate of fire losses, and the community says never.
2. Each candidate is tried as an s-mod: 0 OP plus its s-mod effects, measured on the scratch fleet member via `addPermaMod(id, true)`.
   - A cheap screen keeps the base fit's weapons and re-chooses hullmods, vents and caps.
   - The top 3 then get a full re-optimisation.
3. Greedy, best first. Each pick must add the minimum gain.
4. **Wait**: the same search over every buildable hullmod in the loaded game. If that set beats the best available set by the margin, only the slots its missing hullmods would take are held. The other slots get the best available set of that size, and the log names what's held ("not known yet" or "dock at a spaceport"). A ship holds slots for at most `jao_smodWaitDays` (default 30), counted from the first run that held one; the start time is kept in sector persistent data `jao_smod_waits` (member id → timestamp, plain JDK types). After that it builds the best it has. Changed 11/02, after the user saw ships build nothing. In their save, Plutus, Oldensilent and Ire of Venus all waited for Emergent Threats' AI Subsystem Integration. Its 13–19% is genuine: built in, it lets non-Remnant hulls use the ET "adaptive" subsystems the player already knows (28 vice_ hullmods). But it's a rare tier-3 drop. "No room" notes now say which limit applies: "already has N of N s-mods", "no story points", or the per-ship setting.
5. The final fit is searched with the chosen s-mods as 0-OP keepers.

**Spending** happens only on Confirm.
- **Detecting Confirm**: after the strip, the real delegate's `syncUIWithVariant` rebuilds the refit ship (`getShip()` changes, and its variant is ours). The preview one does nothing. A second signal: the real delegate takes fitted weapons out of their cargo.
- **Preview**: the s-mods are added to the dialog's own copy, so the preview shows them and spends nothing.
- **Confirm**: `spendStoryPoints(n, ..., average getBuildInBonusXP, "Built X into ship (hull)")` (the game's own message, same as a manual build-in), then `SModRecord` + `PlaythroughLog`, then `addPermaMod(id, true)`. If the story points didn't drop, they go on as ordinary hullmods, dropped again if they don't fit the OP.
- Every real decision is written to starsector.log: built, waiting, or nothing worth it.

**S-mod penalties are weighed, not banned** (user, 11/02). Heavy Armor (−25% turning and acceleration), Expanded Missile Racks (missile rate of fire ×0.8) and Missile Autoloader (10 s cooldown → ammo ×1.35 instead of ×1.5) stay candidates:
- the scratch member applies the s-mod penalty stats;
- the model scores handling (mobility factor) and the missile rate (pool × min(1, missile rate of fire)).
- **Offline result**: the Eagle's plan dropped Heavy Armor for Hardened Shields + Expanded Missile Racks.

**Not yet**: enhancing built-in hullmods (s-modding a built-in) and re-planning a ship that already has s-mods from earlier.

**Offline check** (`JAOFitCheck`, Eagle):
- 1-2 picks from the known list, the same plan twice, about 85 ms;
- holds slots when the best mods aren't available, and still builds the other slot when only one is missing;
- nothing at a 50% minimum gain, nothing with no room.

## 11b. Phase 2: "Build the best weapons for this fleet" (production planner)

User request 2026-11-02. It comes after the single-ship Optimal fit works.

**What it does.** One press plans the Optimal fit for every ship in the player fleet as if every blueprint the player knows were in stock. It then works out which weapons and fighter LPCs are missing, and how many, and queues them for custom production, delivered to the gathering point (the home system).

**Planner** (`JAOFleetPlanner`, in this mod, since it needs the optimizer):
1. **Pool** = cargo + storage (counted once across the whole fleet) + every known weapon/fighter blueprint (`getPlayerFaction().knowsWeapon/knowsFighter`) at unlimited quantity, priced at production cost (`FactionProductionAPI.getUnitCost`). Items already in the production queue (`getCurrent()`) count as incoming, so pressing twice doesn't double-order.
2. **Order**: fit ships biggest first (capitals, then cruisers ...), then by deployment cost, so scarce owned items go to the ships where they're worth most. The same style setting applies, or each ship's chosen Optimal style.
3. **Shopping list** = what the fits use that isn't owned: weapon/wing id → count, with each item's score gain. It's trimmed to:
   - a credit budget (Colony Manager's reserve if it's installed, else a setting);
   - optionally, months of production capacity (`getMonthlyProductionCapacity()`).
   The cut drops the smallest gain per credit first.

**Confirm screen.** A dialog in the game's own style lists every item with count, cost and "used by" ships, plus the total cost and estimated months. Buttons:
- **Queue all**: `FactionProductionAPI.addItem(WEAPON/FIGHTER, id, qty)` for each line, then opens the Colonies tab (`showCoreUITab(OUTPOSTS)`) so the queue is on screen.
- **Cancel**.

Nothing is ordered without that press. Orders are charged by the game as production runs, like hand-made orders.

**Where the button goes** (lowest risk first):
1. **Refit screen**: a LunaLib refit button ("Order best weapons for fleet"). Public LunaLib API (`LunaRefitManager.addRefitButton`), already installed.
2. **Colony Manager's empire panel**: a "Fleet outfitting" page with the list and the same button, when both mods are enabled. Colony Manager calls `JAOFleetPlanner` through its usual guarded optional-integration pattern (`isModEnabled` + a class compiled against the optional jar, checked by `audit_combos`). Colony Manager could also re-run it monthly in Auto mode, within its budget, to keep the fleet stocked.
3. **Fleet tab button** (what the user pictured): no public API. It would need the same UI-tree technique as Colony Manager's colony-screen button (`CAScreenButton`). Possible, but brittle across game updates. Stretch goal.

**Works without Colony Manager.** Custom production is vanilla, so the planner and the refit button work alone. Colony Manager only adds the panel page, its budget/reserve and monthly automation. This follows the any-mod-list rule (§7b).

**Open / unverified:**
- Whether `showCoreUITab(OUTPOSTS, custom)` can open the custom-production sub-panel directly, or only the Colonies tab.
- Whether the queue needs a gathering point set first. If none is set, the dialog asks the player to pick one (the game's own rule).
- How production capacity limits a large one-off order: the game spreads it over months.
- Runtime of a whole-fleet plan (30-50 ships): seconds at most, run only on the button press.

**As built (0.2.0, 2026-11-02; differs from the plan above where noted):**
- **Button** (revised 11/02 after the user couldn't find it): LunaLib 2.0.5 lists mod refit buttons only behind its small icon left of the OP readout, so `JAOScreenButton` (transient script, no LunaLib needed) puts an "Order weapons for fleet" button on the refit screen itself. It finds the refit panel as LunaLib does (core UI → current tab → the panel with `syncWithCurrentVariant`, OP readout from `getOpDisplay`) through `JAORefl` (method handles), places it in the first free spot (logged with every button's text and rectangle). The first in-game try found the refit panel (`coreui.refit.U`) and Autofit but offered no free cell, so the first choice is now the empty cell right of "Autofit... [V]" in the game's 190x25 button grid, at that size ("Order fleet weapons"), then right of Strip, then around Autofit and the OP readout and along the panel's edges. JAOFitCheck tests this against the logged layout, and opens `JAOOrderPanel` as an overlay in the core UI (dimmed screen, Escape or a click outside closes). The LunaLib entry `JAORefitButton` stays as a second route. Mods that restyle other screens (AoTD Vaults of Knowledge + Ashlib, Building Menu Overhaul, Grand Colonies, UAF) don't add anything to the refit panel; only LunaLib does. The panel (900x580, game colors) shows items, ships, cost, credits, budget, monthly capacity and months, gathering point, notes, a scrolling list (count, name, total and unit cost, first three ships), and **Queue all** / **Close**. Queue all is disabled with nothing to build or no production capacity. It doesn't open the Colonies tab; the status line points there.
- **Pool**: owned = fleet cargo + every market's storage + weapons and LPCs mounted on fleet ships and their modules (not built-in or `skip_slots`) + the production queue, at price 0. Blueprints = `getKnownWeapons/getKnownFighters` (no SYSTEM/DECORATIVE/BUILT_IN) at `getUnitCost`, quantity 9999. Mounted weapons are pooled fleet-wide: the order assumes weapons can move between ships.
- **Fits**: `JAOOptimizer` planning mode on a clone of each ship: always strips, takes the planner's pool directly (not the refit panel's cargo/storage/market boxes), keeps the ship's hullmods (no hullmod search, no s-mods, no fleet mods), never writes module variants back, logs nothing per ship. `ownedOnly` weapons may come from a known blueprint. Civilian ships with the logistics fit are skipped.
- **Budget**: `jao_productionBudget` share of credits (default 0.5). Ships go biggest first; a ship whose blueprint items would take the order past the budget is re-planned with owned stock only (counted in the panel note). No gain-per-credit trimming yet.
- **Queue all**: sets the gathering point to the player market with the most production capacity when none is set, then places the order with the production in use (`JAOProduction`, decided at run time; the mod works with or without AoTD):
  - **Vanilla**: `addItem` per line into `FactionProductionAPI.getCurrent()`. `CoreScript.doCustomProduction` builds it at month end within capacity and charges then.
  - **AoTD: Vaults of Knowledge** (`aotd_vok`), which replaces the custom production screen: `JAOAotdProduction`, compiled against Vok.jar and AoTDToolboxTheory.jar, is only loaded when that mod is enabled. It places the order exactly as AoTD's Confirm does: `new AoTDProductionOrderSnapshot()`, then `productionData` gets `new AoTDProductionOrderData(spec.getId(), spec, amount)`, then `initSnapshotConsumption()` (registers it with `AoTDProductionManager` and adds a `ProductionTradeContract` for the resources). Each unit is paid when it's finished. Prices come from `AoTDProductionSpecManager.getWeaponSpec/getFighterSpec(id).getProductionCost()`; only items with `isLearnedByFaction` are offered. Incoming = remaining units of the current snapshots plus the vanilla list.
  - **The 11/02 bug**: the first order (134 items, 16 kinds) went into vanilla's list. AoTD's screen doesn't show that list, but AoTD's patched CoreScript still builds it at month end, and the planner counted it, so re-plans found nothing to build. With AoTD, the panel now lists the weapons and LPCs waiting in vanilla's list, and Queue all moves them into the AoTD order (`removeItem` only after AoTD accepted it). If the AoTD probe fails, vanilla is used and the panel says so.
- **Search changes this needed** (apply in refit too): prune counts distinct weapons, not stock entries, and keeps every source of a kept weapon; candidates sorted by `CANON` (cheapest source first) after the prune; a last pass swaps a priced pick for the same weapon from a cheaper source still in stock.
- **Tests**: JAOFitCheck "planning" checks (eagle, onslaught, conquest, paragon): with one owned + unlimited priced blueprint per weapon, the weapons picked match a single unlimited source, and no blueprint is used while an owned copy is free.
- **Not done**: Colony Manager page and monthly re-order; fleet-tab button; per-line deselection in the panel.

## 12. Testing plan

Offline, the same jtbuild pattern as Colony Manager:
- `ao_build.ps1`: javac, banned-class scan, verify with and without LunaLib, game script-loader check (done);
- plus `JAOFitCheck.java`: real `starfarer.api` specs loaded offline where possible; otherwise fixed weapon/hull rows from `data\*.json`. It runs the optimizer on fixed pools and checks:
  - OP limits, slot legality and determinism (same seed → same fit);
  - balance (Balanced has both kinetic and HE/energy when both exist);
  - style shifts;
  - empty-pool behaviour.

The Python prototypes in `tools\` give expected values to compare against.

In game (the user):
- the Optimal card appears in the 4th slot and disappears after leaving refit and after save/load;
- preview equals confirmed fit;
- credit cost is right when buying;
- no weaponless NPC ships after saving;
- simulator duels: Optimal vs the stock goals.

## 13. Risks

- `SavedVariantData` is engine code: a game update can change it. It's feature-detected, with the checkbox fallback.
- A missed cleanup leaves a card in the save. It's harmless because it carries a stock loadout, but we purge on load.
- The scratch-member stats miss after-creation hullmods and captain personal skills (outside a fleet). These are modelled by hand.
- The scoring constants (kC, k_spread, kA, soft-flux value, PD need, target weights) are tuning knobs, not game numbers. They need simulator checks.
- Scripted mod weapons without a table row are valued on plain numbers only.
- Performance on 60-slot capitals with large markets is unmeasured.

## 14. Research index

All in the author's research notes (not included in this repository).

| File | What |
|---|---|
| `data\hulls.json` | 1,647 hulls (1,095 combat) incl. 394 skins: CSV stats, slot geometry, built-ins, hints/tags, goal variants |
| `data\weapons.json` | 1,666 weapons (484 player-fittable): CSV + .wpn/.proj, derived DPS/flux/hit strength/armor DPS, autofit tags, script classes |
| `data\wings.json`, `data\hullmod_rows.json`, `data\goal_variants.json` | 200 wings, 825 hullmod CSV rows, 2,003 goal variants |
| `data\reference_targets.json` | median enemy per size, vanilla and all |
| `data\hull_profiles.json`, `hull_profiles.md` | roles, slot shares, designed range and damage mix per hull |
| `data\hull_hullmods.json`, `hull_hullmods.md` | prototype hullmod picks per hull |
| `weapon_rankings.md` | per slot type/size ranking by kill time, plus vanilla tag disagreements |
| `hull_overview.csv` | one row per combat hull |
| `engine_report.md` | refit autofit internals, hook design (decompiled under `engine_work\`) |
| `mechanics_report.md` | damage, armor, flux, AI, range, skills, scripted effects, scoring model (`mechanics_work\`) |
| `hullmods_vanilla.json`, `hullmods_work\report_preview.txt` | vanilla hullmods with numbers and scoring formulas |
| `hullmods_mods.json`, `modded_report.txt`, `weapons_mods_quirks.json` | 700 mod hullmods, cross-mod rules, 912 weapon-quirk entries (`.txt`: a hook stopped the agent writing `.md`) |
| `meta_report.md` | community fitting knowledge (graded sources, 34 per-hull builds, 18 optimizer rules); raw tier/thread texts and a 156-weapon stats table in `meta_work\` |
| `tools\*.py` | dump_data, analyze, profiles, hullmod_reco (rerun after mod changes) |
