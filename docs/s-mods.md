# S-mods and story points

Story points are worth a lot in a normal playthrough, so the mod never spends them unless you tell it to.

## Turning it on

Tick **Build in s-mods (story points, permanent)** in the Autofit panel. It's off by default, and the game remembers the box per save.

- **Single Optimal fit:** the preview shows the s-mods it would build. Points are only spent when you press **Confirm**, never by the preview.
- **Optimize fleet:** uses the same box. The panel lists every new s-mod and its story point before you press **Apply to all ships**, and says whether s-mods are on.

The mod does not change s-mods already on your ships. On 0.98.5a the game can change a ship's s-mods for credits at your colony with Heavy Industry; that is done in the game's own dialog.

## How it picks

S-mods are permanent, so each candidate is judged by fitting the whole ship again around it: the hullmod costs no OP once built in, it gets its s-mod bonus, and the freed OP goes to weapons, other hullmods, vents and capacitors. The best one goes first, then the next, and so on.

It stops at whichever comes first:
- the ship's s-mod limit;
- **S-mods per ship** (3 by default);
- your story points minus **Story points to keep** (0 by default);
- the next s-mod making the ship less than **Minimum gain per s-mod** (2%) stronger.

Civilian ships use their own list, see [Civilian ships](civilian-ships.md).

## Waiting for better hullmods

Sometimes the best s-mod for a ship is one you don't know yet, or can only add at a spaceport. With **Wait for the best hullmods** on (the default):

- if a set you can't build yet would be clearly better (by **How much better to wait for**, 10%), only the slots those hullmods would take are kept open, and the rest get the best you have now;
- `starsector.log` says what each held slot is waiting for;
- after **Most days to hold a slot** (30 days), the ship gives up waiting and builds the best it has.

Turn it off and every ship just builds the best it can right now.

## Keeping points safe

- Leave the box unticked. Nothing gets built.
- Set **Story points to keep** to the number you never want it to go below.
- Lower **S-mods per ship**, or raise **Minimum gain per s-mod** so only the big upgrades get a point.

Next up: [Other mods](other-mods.md).
