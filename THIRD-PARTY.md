# Third party work

## WaveyCapes — tr7zw
- Licence: tr7zw Protective License (see `THIRD-PARTY-WaveyCapes-LICENSE.txt`)
- Copyright (c) tr7zw, 2021
- https://github.com/tr7zw/WaveyCapes

The licence permits use, modification and compilation, and forbids using the
work for commercial advantage or monetary compensation. Space Client's cape
motion is its own code; tr7zw's mod was the reference for the approach.

## ItemPhysic — CreativeMD / team.creative
- Licence: LGPL-3.0
- https://github.com/CreativeMD/ItemPhysic

Space Client's own `ItemPhysicsModule` implements the same idea independently.
**No ItemPhysic code is included in Space Client.** LGPL-3.0 source cannot be
copied into a work distributed as all rights reserved, which is how Space
Client is currently licensed (`fabric.mod.json`: `"license": "ARR"`). Any item
physics in this client has to be written independently.

Two lawful alternatives, if the goal is to ship ItemPhysic's actual behaviour:
1. Ship ItemPhysic as a separate mod the user installs alongside, and have
   Space Client detect it. Nothing is copied, so nothing is triggered.
2. Relicense the parts of Space Client that would contain LGPL code, and meet
   the LGPL's source and relinking requirements.

## Minecraft — Mojang Studios
Space Client is a modification and is not affiliated with, endorsed by, or
connected to Mojang Studios or Microsoft.

## Fabric — FabricMC
- Licence: Apache-2.0

## Main menu layout — Chill Client
The shape of the opening sequence was taken from screenshots. No code was
copied; the implementation is entirely Space Client's own.
