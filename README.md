# AE2 Void Factory

> modid `ae2vf` · Minecraft 1.21.1 · NeoForge 21.1.264 · **0.1.0-beta**

An **Applied Energistics 2** add-on that turns "connect machines to the ME network" into terminal
configuration instead of cable spaghetti.

Instead of placing interfaces, running cables and tuning IO faces on every machine, you bind machines
to a **node** with the binding wand and edit the node's IO rules in a terminal. The mod only moves
items, fluids and FE between machines and AE2 storage — recipes, energy and processing time are
untouched.

## Features

- **Nodes** — one node manages 1..N machines, each with its own input/output rules.
- **Passive IO** — rule-driven transfer every tick, with per-rule backoff and face learning.
- **Active crafting** — nodes advertise patterns to AE2 CPUs through a virtual pattern provider and
  feed dispatched jobs into their machines.
- **Observation layer** — a second fact source for "is the block there / what is it / is it working";
  the UI and IO gating read it instead of probing the world on the read path.
- **Two terminals** — passive IO terminal (rule editing, list/tile views) and node terminal (nodes,
  members, blocking, multiplier, dispatch), both with search, six sort keys and direction toggles.
- **Machine binding wand** — right-click to bind/unbind, sneak-right-click to confirm a draft,
  chain-bind neighbouring machines of the same type.
- **JEI integration** — machine list, IO columns, storage, and a dedicated FE ingredient.

## Requirements

| Dependency | Version | Required |
|---|---|---|
| Minecraft | 1.21.1 | yes |
| NeoForge | 21.1.264 | yes |
| Applied Energistics 2 | 19.2.10+ | yes |
| Applied Flux | 1.21-2.1.5-neoforge+ | no (FE support) |
| Applied Mekanistics | matching 1.21.1 build | no (chemical support) |
| JEI | 19.21.1.304+ | no |

Without Applied Flux the mod simply has no FE resource; without Applied Mekanistics it has no
chemical resource. Nothing degrades silently.

## Installation

1. Install the matching NeoForge version for Minecraft 1.21.1.
2. Drop the jar into `mods/`.
3. Start the game. The mod also needs Applied Energistics 2.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/ae2vf-<version>.jar`.

## Compatibility

This is the first public beta. Mod ids, item ids and save data changed from the internal
`idlefactory` builds to `ae2vf`: **old worlds and old items are not compatible** — start a new world.

## License

MIT — see [LICENSE](LICENSE).
