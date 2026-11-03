# Golem Break Room

Place Break Room Chairs, Tables, Golem Snack Machines, and Golem Arcades near profession workstations. Idle builders, beekeepers, traders, chefs, and off-duty fishers choose reachable furniture within 12 blocks horizontally and nearby floors. Furniture and approach tiles are reserved so workers do not crowd one another. Blocked routes are abandoned without teleporting.

Golems physically path onto the chair's half-height seat and use a visible seated pose. A snack-machine visit dispenses a transient vanilla snack item; the golem then walks to a chair facing an adjacent table, sits, raises the snack to eat, and consumes it during the break. Snack selection and dispensing are currently simulated by the machine routine; the machine does not yet have a player-facing stock inventory.

Arcade visits animate a hand and play a short sequence of note-block chimes, synth-like notes, clicks, and bass notes. These are positional block sounds emitted from the arcade itself, rather than from the Golem.

Ten seconds spent seated, while eating, or playing arms a rested bonus. When work resumes, Speed I gives 20% more movement speed for 3 minutes. Readiness uses each profession entity's saved data; the running effect uses vanilla effect persistence. Tasks interrupt breaks promptly. The movement boost does not accelerate crafting or placement timers.

Validation includes physical route checks for arcade use, chair-seat occupancy, snack pickup, seated eating, snack consumption, and rest readiness. Client-side visual acceptance in a live world is still recommended for final pose and held-item alignment.
