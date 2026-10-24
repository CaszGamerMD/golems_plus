# Builder / warehouse logistics ownership contract

## Source of truth

A Builder Planning Table stores **job metadata**, a warehouse link,
a per-job OnSite Storage coordinate, and up to eight assigned crew UUIDs.
Physical tools, scaffolding, construction materials and delivered shulkers
must live at OnSite Storage. The Planner's old input slots remain saved for
migration of older worlds, but no longer accept new Builder supplies.

The warehouse computer owns submitted order tickets and outstanding
manifests before a shipment can be assigned. A real output pallet and
Shulker Station are required before a PTP begins picking.

## Crew retirement

On completion or explicit cancel/delete, Builders unload their real
shulker, inventory and tools into OnSite Storage before navigating to a
nearby blue Unemployed Center. The bin saves a 27-slot inventory of reusable
worker items. Empty Builders are unlinked **only after** a physical worker
item is placed inside the bin. If the bin is missing/full, no entity or
cargo is destroyed. The original custom name travels on the worker item.

The return is intentionally not automatic when a Builder is merely idle
before job start. Unsettled warehouse tickets or carried items hold up
retirement until the shipment is recovered.

## In-game test checklist

- Link a warehouse and select an accessible OnSite chest for each job.
- Confirm Planner refuses START until both links are present.
- Stage actual tools and scaffolding in OnSite Storage, with some materials
  inside shulkers. Builder should borrow and return from there, not Planner.
- Place up to eight Builder Worker items near the Planner; check UUIDs and
  assignments persist after save/restart and crossing loaded chunks.
- Sort materials A-Z, quantity, and remaining.
- Build/clear site layer-by-layer and ensure supply courier works alongside
  the clearing/construction crew, without duplicate materials.
- Place a blue Unemployed Center within 24 blocks of the Planner; complete
  or cancel an idle, shipment-free build. Watch each empty Builder walk to
  the bin and become a reusable named worker item. A full bin must never
  discard a Golem. Breaking bin drops all real stored items.
- If a warehouse shipment remains active on cancel, Builders must not be
  converted to items while still holding shulkers.
