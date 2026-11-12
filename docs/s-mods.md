# S-mods and story points

- Default: Story points are not spent unless Build in s-mods (story points, permanent) is ticked in the Autofit panel. The game saves the box per save.
- Single Optimal fit: The preview shows s-mods. Points are spent on Confirm only.
- Optimize fleet: Uses the same box and lists each new s-mod before Apply to all ships.
- Existing s-mods: Not changed.

## How it picks

- Method: Each candidate is judged by refitting the whole ship with it built in. The best goes first.
- Stops at: The ship's s-mod limit, S-mods per ship (3), story points minus Story points to keep (0), or a gain below Minimum gain per s-mod (2%).
- Civilian ships: Use their own list. See [Civilian ships](civilian-ships.md).

## Waiting for better hullmods

- Wait for the best hullmods (on): If a set not yet buildable is better by 10%, only those slots stay open. Other slots get the best available.
- Most days to hold a slot (30): Afterward held slots get the best available.
- Log: `starsector.log` states what each held slot waits for.

## Limiting spending

- Box unticked: Nothing is built in.
- Story points to keep: Floor the mod does not spend below.
- S-mods per ship and Minimum gain per s-mod: Lower the first or raise the second to build in only large upgrades.
