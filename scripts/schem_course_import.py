#!/usr/bin/env python3
"""Lossless Sponge v3 .schem (gzip NBT) -> timed ground navigation course.

Uses Minecraft block palette and real 3D occupancy to conservatively mark
1.6-high, 0.65-wide walkable columns. NO chunk loading, breaking, flight,
teleporting or scaffold placement. Requires only the Python standard library.

A floor of green wool marks valid start cells (may be multiple); red wool
marks ANY valid finish. Source can be .schem or its lossless .base64 archive.
"""
from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import json
import math
import struct
from collections import deque
from pathlib import Path

MAX_BYTES = 8_000_000
BOT_HEIGHT = 1.6
BOT_WIDTH = .65
MAX_TICKS = 400


class NbtReader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def take(self, n: int) -> bytes:
        if n < 0 or self.pos + n > len(self.data):
            raise ValueError("Truncated NBT or invalid array length")
        result = self.data[self.pos:self.pos + n]
        self.pos += n
        return result

    def integer(self, width: int, signed: bool = True) -> int:
        return int.from_bytes(self.take(width), byteorder="big", signed=signed)

    def string(self) -> str:
        return self.take(self.integer(2, signed=False)).decode("utf-8")

    def value(self, tag: int):
        if tag == 1:
            return self.integer(1)
        if tag == 2:
            return self.integer(2)
        if tag == 3:
            return self.integer(4)
        if tag == 4:
            return self.integer(8)
        if tag == 5:
            return struct.unpack(">f", self.take(4))[0]
        if tag == 6:
            return struct.unpack(">d", self.take(8))[0]
        if tag == 7:
            return self.take(self.integer(4))
        if tag == 8:
            return self.string()
        if tag == 9:
            inner = self.integer(1, signed=False)
            count = self.integer(4)
            if count < 0 or count > 1_000_000:
                raise ValueError("NBT array count out of range")
            return [self.value(inner) for _ in range(count)]
        if tag == 10:
            result = {}
            while True:
                child = self.integer(1, signed=False)
                if child == 0:
                    return result
                name = self.string()
                result[name] = self.value(child)
        if tag == 11:
            length = self.integer(4)
            if length < 0 or length > MAX_BYTES // 4:
                raise ValueError("NBT int array length out of range")
            return [self.integer(4) for _ in range(length)]
        if tag == 12:
            length = self.integer(4)
            if length < 0 or length > MAX_BYTES // 8:
                raise ValueError("NBT long array length out of range")
            return [self.integer(8) for _ in range(length)]
        raise ValueError(f"Unsupported NBT tag {tag}")


def read_schematic(data: bytes):
    if not data.startswith(b"\x1f\x8b"):
        raise ValueError("Expected gzip-compressed Sponge schematic")
    if len(data) > MAX_BYTES:
        raise ValueError("Compressed schematic exceeds size cap")
    decompressed = gzip.decompress(data)
    if len(decompressed) > MAX_BYTES:
        raise ValueError("Decompressed schematic exceeds size cap")
    reader = NbtReader(decompressed)
    if reader.integer(1) != 10:
        raise ValueError("Sponge NBT must have compound root")
    reader.string()
    root = reader.value(10)
    schematic = root.get("Schematic", root)
    if schematic.get("Version") not in (2, 3):
        raise ValueError("Sponge schematics v2 and v3 are supported")
    w = schematic["Width"]
    h = schematic["Height"]
    l = schematic["Length"]
    if not (0 < w <= 128 and 0 < h <= 128 and 0 < l <= 128
            and w * h * l <= 1_000_000):
        raise ValueError("Invalid schematic dimensions")
    section = schematic.get("Blocks", schematic)
    palette = section["Palette"]
    reverse = {i: state for state, i in palette.items()}
    raw = section.get("Data", schematic.get("BlockData"))
    if not isinstance(raw, bytes):
        raise ValueError("Missing Sponge Blocks.Data byte array")
    # Minecraft/Sponge variable-length palette integers. Index order:
    # (y * length + z) * width + x (X is the fastest dimension).
    indices = []
    value = 0
    shift = 0
    for byte in raw:
        value |= (byte & 0x7f) << shift
        if byte & 0x80:
            shift += 7
            if shift > 28:
                raise ValueError("Palette VarInt exceeds 32 bits")
        else:
            indices.append(value)
            value = 0
            shift = 0
    if shift or len(indices) != w * h * l:
        raise ValueError("Schematic palette length does not match dimensions")

    def block(x: int, y: int, z: int) -> str:
        if not (0 <= x < w and 0 <= y < h and 0 <= z < l):
            return "minecraft:air"
        idx = indices[(y * l + z) * w + x]
        if idx not in reverse:
            raise ValueError(f"Unknown palette ID {idx}")
        return reverse[idx]

    return schematic, (w, h, l), block


def occupancy(state: str, y: int) -> tuple[float, float] | None:
    """Block's vertical collision extent (relative to schematic Y)."""
    base = state.split("[", 1)[0]
    if base.endswith(":air") or base in ("minecraft:cave_air",
                                            "minecraft:void_air"):
        return None
    if base.endswith("_door"):
        # Doors can be opened in the simulation without mining. A closed
        # door's opening delay is scored separately from the collision.
        return None
    if "_slab" in base:
        if "type=double" in state:
            return float(y), float(y + 1)
        if "type=top" in state:
            return y + .5, y + 1.0
        return float(y), y + .5
    if "_carpet" in base:
        return float(y), y + .0625
    if "_trapdoor" in base and "open=true" in state:
        return None
    return float(y), float(y + 1)


def convert(data: bytes, name: str = "timetrial") -> dict:
    schematic, (width, height, length), block = read_schematic(data)
    green = [(x, y, z) for y in range(height) for z in range(length)
             for x in range(width)
             if block(x, y, z).split("[", 1)[0] == "minecraft:green_wool"]
    red = [(x, y, z) for y in range(height) for z in range(length)
           for x in range(width)
           if block(x, y, z).split("[", 1)[0] == "minecraft:red_wool"]
    if not green or not red:
        raise ValueError("Missing green wool start or red wool finish")
    floor_y = green[0][1]
    if any(y != floor_y for _, y, _ in green + red):
        raise ValueError("Current headless trainer requires one floor elevation")
    if floor_y + 2 >= height:
        raise ValueError("Need a floor, two overhead layers and a collision profile")
    starts = {(x, z) for x, _, z in green}
    goals = {(x, z) for x, _, z in red}
    rows = []
    doors = []
    rejected_slabs = []
    for z in range(length):
        result = ""
        for x in range(width):
            floor_state = block(x, floor_y, z)
            base = floor_state.split("[", 1)[0]
            if (x, z) in starts:
                char = "S"
            elif (x, z) in goals:
                char = "G"
            elif base in ("minecraft:air", "minecraft:cave_air",
                          "minecraft:void_air"):
                char = "#"
            else:
                char = "."
            if char != "#":
                feet_y = floor_y + 1
                top_y = feet_y + BOT_HEIGHT
                for layer in range(floor_y + 1, min(height,
                                                     floor_y + 4)):
                    state = block(x, layer, z)
                    if state.split("[", 1)[0].endswith("_door"):
                        if "half=lower" in state:
                            doors.append((x, z))
                        char = "D" if char == "." else char
                        continue
                    shape = occupancy(state, layer)
                    if shape is not None and shape[0] < top_y - 1e-9 \
                            and shape[1] > feet_y + 1e-9:
                        if "slab" in state:
                            rejected_slabs.append((x, z))
                        char = "#"
                        # Keep examining upper layers so all unsafe
                        # slab positions are retained in diagnostics.
            result += char
        rows.append(result)
    document = {
        "format": "golems-plus-raw-schem-timetrial-v1",
        "name": name,
        "source_sha256": hashlib.sha256(data).hexdigest(),
        "schematic_version": schematic["Version"],
        "schematic_dimensions": [width, height, length],
        "origin_offset": schematic.get("Offset", [0, 0, 0]),
        "rows": rows,
        "spawn_tiles": [list(p) for p in sorted(starts)],
        "finish_tiles": [list(p) for p in sorted(goals)],
        "door_tiles": [list(p) for p in sorted(set(doors))],
        "slab_cells_too_short_for_1_6_block_bot": [
            list(p) for p in sorted(set(rejected_slabs))
        ],
        "constraints": {"width": BOT_WIDTH, "height": BOT_HEIGHT,
                        "speed_m_per_s": 4.0,
                        "limit_ticks": MAX_TICKS,
                        "may_break": False, "may_scaffold": False,
                        "may_fly": False},
    }
    if any(rows[z][x] == "#" for x, z in starts | goals):
        raise ValueError("Start/finish wool has insufficient headroom")
    return document


def load_input(path: Path) -> bytes:
    raw = path.read_bytes()
    if path.suffix.lower() in (".base64", ".b64"):
        return base64.b64decode(raw, validate=False)
    return raw


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("schematic", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    doc = convert(load_input(args.schematic), args.schematic.stem)
    print(f"Course {doc['name']}: {doc['schematic_dimensions']}, "
          f"{len(doc['spawn_tiles'])} green starts, "
          f"{len(doc['finish_tiles'])} red finishes, "
          f"{len(doc['door_tiles'])} door tiles, "
          f"{len(doc['slab_cells_too_short_for_1_6_block_bot'])} blocked slabs")
    print("\n".join(doc["rows"]))
    if args.out is not None:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(doc, indent=2) + "\n",
                            encoding="utf-8")


if __name__ == "__main__":
    main()
