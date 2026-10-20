package com.caszgamermd.golemsplus.builder;

/**
 * Decodes a Litematica region's dense block-state long array.
 * States are packed least-significant-bit first and may span two longs.
 * This class deliberately does not read files or apply anything to a world.
 */
public final class LitematicPaletteDecoder {
    private LitematicPaletteDecoder() {}

    public static int bitsPerEntry(int paletteSize) {
        if (paletteSize < 1 || paletteSize > 65_536)
            throw new IllegalArgumentException("Invalid palette size");
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    public static int[] decode(long[] words, int paletteSize, int entryCount) {
        if (words == null || entryCount < 0 || entryCount > 16_777_216)
            throw new IllegalArgumentException("Invalid region");
        int bits = bitsPerEntry(paletteSize);
        long requiredBits = (long) entryCount * bits;
        if ((long) words.length * 64 < requiredBits)
            throw new IllegalArgumentException("Truncated block-state array");
        int[] entries = new int[entryCount];
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < entryCount; i++) {
            long bitIndex = (long) i * bits;
            int wordIndex = (int) (bitIndex >>> 6);
            int shift = (int) (bitIndex & 63);
            long value = words[wordIndex] >>> shift;
            if (shift + bits > 64)
                value |= words[wordIndex + 1] << (64 - shift);
            int paletteIndex = (int) (value & mask);
            if (paletteIndex >= paletteSize)
                throw new IllegalArgumentException("Out-of-range palette index at " + i);
            entries[i] = paletteIndex;
        }
        return entries;
    }
}
