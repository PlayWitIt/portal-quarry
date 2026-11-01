# Portal Quarry

A client-side Fabric mod for Minecraft 1.21.11 that farms obsidian from the nether portals the game builds for you.

When you go through a portal and there's no portal on the other side, the game builds one: 14 obsidian. Portal
Quarry mines it, banks everything in your ender chest, `/kill`s you with an empty inventory, respawns you at your bed,
stores the obsidian in shulker boxes, and goes again. About 1,000 obsidian an hour, AFK.

By **PlayWit Creations**.

## Installing
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.11**, plus
   [Fabric API](https://modrinth.com/mod/fabric-api).
2. Put `portal-quarry-<version>+1.21.11.jar` in your `mods` folder.

## Setting up (once)

**In the overworld**, within 6 blocks of where you stand:
- a lit **nether portal**;
- a **bed** you've slept in (that's where you respawn);
- an **ender chest**;
- a **shulker box**, placed, that the obsidian goes into; and **chests** (a double chest or two) holding your **empty
  shulker boxes**. When the placed box is full, the mod breaks it, puts it in a chest, and places an empty one in
  its spot. (No shulker box? It fills the chests instead.)
- **XP bottles** in a chest or your ender chest, if your pickaxe has Mending.

**In your ender chest:** a stack of **cobblestone or netherrack** (filler; see below). Your **pickaxe** can be in
your inventory or the ender chest: diamond or netherite, Efficiency V is best.

**Carry one extra ender chest** the first time: the mod puts it down in the nether, out of the new portal's way.

**Take off your armour and empty your offhand.** Everything you carry is dropped when you die, so the mod only lets
you carry pickaxes, obsidian, filler, XP bottles and an ender chest, and it puts all of them in the ender chest
before it dies.

Pick a spot where the nether side is solid ground, not over lava.

## Use
Stand by your bed and press **H**. Press **H** again (or `/portalquarry stop`) to stop.

Each cycle:
1. At the bed: takes the pickaxe, filler and obsidian out of the ender chest, puts the obsidian in the shulker box
   (swapping in an empty box when it's full), and mends the pickaxe if it needs it.
2. Walks into the portal.
3. In the nether: mines the 10 frame blocks around it, steps out, mines the 4 floor blocks and fills each hole with
   filler, so the game builds the next portal **in the same place**. (Without filler it leaves the floor, 10 a
   cycle, so the portal doesn't wander.)
4. Picks everything up, puts all of it in the ender chest there, and runs `/kill`.
5. Respawns at the bed.

## Safety
- **Pickaxe.** It never takes a pickaxe with fewer than 60 uses left into the nether. With **auto-mend** (on), if
  the pickaxe has Mending and is below 25%, it moves it to your offhand and throws XP bottles at your feet until
  it's fully mended. No bottles and no spare? It stops at the bed and tells you.
- **Players.** Another player within 48 blocks stops it. In the nether it banks what it has and dies first, so you
  stop at your bed with nothing to lose.
- **Low health** works the same way.
- It won't `/kill` with anything in your inventory. If the ender chest is full, or the kill command doesn't work,
  it stops with everything banked.
- Respawned somewhere other than the bed? It stops.

## Settings
`/portalquarry status` shows this run and all-time totals.

| Command | |
|---|---|
| `/portalquarry kill <command>` | the server's kill command (default `kill`) |
| `/portalquarry players <true/false>` | stop when a player comes near (default on) |
| `/portalquarry range <blocks>` | how near (default 48) |
| `/portalquarry mend <true/false>` | auto-mend with XP bottles (default on) |
| `/portalquarry mendbelow <percent>` | when to mend (default 25) |
| `/portalquarry minuses <uses>` | fewest uses left to take a pickaxe into the nether (default 60) |
| `/portalquarry floor <true/false>` | mine and fill the floor (default on) |
| `/portalquarry hud <true/false>` | the lines at the top left |

The key is **H**; change it in Controls.

## Developing
`./gradlew build` builds the jar. `./gradlew runSelfTest` opens a game, builds the setup in a test world and runs the
loop for real: frame size, the ender chest placed clear of the portal, the floor filled, the portal rebuilt in the
same place, the shulker box swap, auto-mend, the low-health escape, the worn-pickaxe stop, a kill command that
doesn't work, and that every obsidian mined (by the game's own statistic) ends up stored or banked.
