# Golem Break Room

Place Golem Break Room Chairs, Tables, Golem Snack Machines and Golem Arcades near profession workstations. Idle builders, beekeepers, traders, chefs and off-duty fishers randomly choose reachable furniture within 12 blocks horizontally and nearby floors. Each furniture block is reserved for one visitor. Blocked approaches are abandoned without teleporting.

Ten seconds spent at furniture arms a rested bonus. When work resumes, Speed I gives 20% more movement speed for 3 minutes. Another completed break arms a fresh full duration for the next task. Readiness uses each profession entity’s saved data; the running effect uses vanilla effect persistence. Tasks interrupt breaks promptly. Machines are decorative interaction furniture, without consumable inventories in this initial implementation.

Models use vanilla textures. Golems approach and look at furniture; snack/arcade interactions swing a hand. Seated poses are not implemented yet. The movement boost does not accelerate crafting or placement timers.

Validation: compilation and existing regression suite run in PR CI. Dedicated real-entity furniture/rest/boost acceptance testing remains required before release.
