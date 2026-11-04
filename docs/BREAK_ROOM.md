# Golem Break Room

Place Break Room Chairs, Tables, Golem Snack Machines, and Golem Arcades near profession workstations. Idle builders, beekeepers, traders, chefs, and off-duty fishers choose reachable furniture within 12 blocks horizontally and nearby floors. Furniture and approach tiles are reserved so workers do not crowd one another. Blocked routes are abandoned without teleporting.

Golems physically path onto the chair's half-height seat and use a visible seated pose. Snack machines have a persistent nine-slot food inventory. Players restock them by right-clicking with any vanilla or modded item that has Minecraft's food component; the machine transfers as much of the held stack as it can store and preserves item components. Empty-hand use reports current stock, while crouch + empty-hand use removes one snack. Breaking the machine drops its remaining contents.

A golem only reserves a machine that currently contains food. It approaches from the front face, removes exactly one real ItemStack, and carries that same item to a chair facing an adjacent table. The snack is visibly rendered while carried and during a raised-hand eating animation, then consumed from the golem's temporary break state. The head stays relaxed between bites and makes only a brief, subtle nod once per 30-tick bite cycle. If work interrupts the break, the item is returned to its source machine when space remains or dropped beside the golem. Empty machines are skipped; if stock disappears before pickup, the golem abandons that visit without creating food.

Arcade visits animate a hand and play note-block chimes, synth-like notes, clicks, and bass notes as positional block sounds emitted from the arcade.

Ten seconds spent seated, while eating, or playing arms a rested bonus. When work resumes, Speed I gives 20% more movement speed for 3 minutes. Tasks interrupt breaks promptly; the movement boost does not accelerate crafting or placement timers.

Validation covers persistent multi-food stock, rejection of non-food items, one-item dispensing, empty-machine skip behavior, the real item carried/rendered by a golem, chair-seat occupancy, seated eating, snack consumption, arcade use, and rest readiness. Client-side visual acceptance in a live world is still recommended for final held-item alignment.
