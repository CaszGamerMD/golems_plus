package com.caszgamermd.golemsplus.worker;

/** Stable identifiers for station ownership, saved work orders and future mobs. */
public enum WorkerSpecialty {
    COOKING, BEEKEEPER, BUILDER, FISHING, TRADER;

    public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
}
