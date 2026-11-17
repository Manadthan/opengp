#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Name functions and data using a decompilation of another release of the game.

naari3/mkgp2-decomp (CC0) names Mario Kart Arcade GP 2's functions and
globals in the Japanese release, GNLJ82. Its code and data are the same as
GGPE02's apart from a handful of functions and objects of other sizes, which
shift everything after them. This lines up the two symbol lists by the sizes
of their functions (in address order) and of their objects (section by
section), and prints the decomp's names for this game's. With --apply it
renames symbols that still have generated names (fn_XXXXXXXX, dtor_XXXXXXXX,
lbl_XXXXXXXX):

  * runs of symbols whose sizes agree are paired in order; between two runs,
    the symbols left over are paired in order when both releases have the
    same number (the ones whose size differs);
  * a pair of objects is kept only if the distance between its two
    addresses is the one between the releases there: that of the run of at
    least 8 pairs before it or after it. Where one release has merged or
    split objects the other has not (two 4-byte halves of an 8-byte time),
    pairing by size slips, and this drops the pairs that slipped. An object
    left unpaired is then paired with ours at its address plus that
    distance, if that one has the same size, or (after the splits below)
    if the runs on both sides are at the same distance;
  * where the decomp has split an object that decomp-toolkit found as one
    (to name a field of it, such as g_DebugPrintfEnable in the settings
    block), and its pieces cover exactly one of this game's objects, that
    object is split the same way;
  * a name is skipped if another symbol already has it, or if hand-written
    code (games/, runtime/) defines a function or hook of that name;
  * an address at the end of a decomp name (Stub_NoOp_80121c34) is the
    other release's: it becomes this release's address when it is a
    symbol's, and is dropped otherwise. An address inside a name
    (CGameCoin_8012BF5C_rodata_80317060) becomes this release's too; a name
    with one that is no symbol's is skipped;
  * the decomp's placeholder names for data no one has named yet
    (rodata_803175A0_rodata_80317060, pad_802EE580) are skipped, and so are
    the few names it has wrong (DECOMP_FIXES).

    python tools/decompnames.py gp2decomp/mkgp2-decomp/config/GNLJ82/symbols.txt \\
                                games/mkgp2/config/symbols.txt [--apply]

The decomp's names are its authors' reading of the code. Where a function
already has a name and the decomp's differs, the name is kept, unless
--rename-differing is given: then the decomp's is taken (except for names
hand-written code uses). Its code is matched against the original, so its
names for SDK functions are better than tools/sigmatch.py's signature matches,
and it renames its own functions as it learns more about them.
"""

from __future__ import annotations

import argparse
import bisect
import difflib
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
from naming import handwritten_names  # noqa: E402
SYMBOL_LINE = re.compile(r'^(?P<name>"[^"]*"|\S+) = (?P<sec>[^:;\s]+):0x(?P<addr>[0-9A-Fa-f]+);(?P<rest>.*)$')
SIZE = re.compile(r"size:0x([0-9A-Fa-f]+)")
DATA_KIND = re.compile(r"\bdata:\S+")
# Names decomp-toolkit (or Ghidra, in the decomp) makes up.
GENERATED = re.compile(r"^(fn|dtor|ctor|lbl|func|FUN|thunk_FUN|jumptable|DAT|PTR)_[0-9A-Fa-f]{8}$")
IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
# A trailing address: 5 to 8 hex digits, at least one of them a digit (so
# words such as "_Added" are left alone).
ADDRESS_SUFFIX = re.compile(r"^(?P<base>\w+?)_(?P<hex>(?=[0-9A-Fa-f]*[0-9])[0-9A-Fa-f]{5,8})$")
# An address inside a name (CGameCoin_8012BF5C_rodata_80317060, a piece of
# the data of the unit the decomp named after its first function).
EMBEDDED_ADDRESS = re.compile(r"(?<=[A-Za-z_])(8[0-9A-Fa-f]{7})(?=_|$)")
# The decomp's names for pieces of data no one has named yet: a section and
# an address (rodata_803175A0_rodata_80317060, data_80491988_data_...).
# The same for padding between objects (pad_802EE580, gap_07_804ECA4C_data).
PLACEHOLDER = re.compile(r"^(_?(s|ro)?(data|bss)2?_[0-9A-Fa-f]{8}(_|$)|pad_[0-9A-Fa-f]{8}$|gap_\d+_)")
# A run of this many pairs of the same sizes fixes the distance between the
# two releases' addresses around it.
ANCHOR = 8
# Corrections to the decomp, by its (GNLJ82) address.
DECOMP_FIXES = {
    # It names both FIFO getters GXGetGPFifo; this one returns CPUFifo.
    0x802652F0: "GXGetCPUFifo",
}


class Symbol:
    def __init__(self, line: int, name: str, section: str, address: int, size: int, rest: str):
        self.line, self.name, self.section, self.address, self.size, self.rest = line, name, section, address, size, rest


class SymbolList:
    def __init__(self, path: Path):
        self.lines = path.read_text(encoding="utf-8").splitlines()
        self.functions: list[Symbol] = []
        self.objects: dict[str, list[Symbol]] = defaultdict(list)  # by section
        self.names: set[str] = set()
        for i, line in enumerate(self.lines):
            m = SYMBOL_LINE.match(line)
            if not m:
                continue
            name = m["name"].strip('"')
            self.names.add(name)
            size = SIZE.search(m["rest"])
            if not size:
                continue
            sym = Symbol(i, name, m["sec"], int(m["addr"], 16), int(size[1], 16), m["rest"])
            if "type:function" in m["rest"]:
                self.functions.append(sym)
            elif "type:object" in m["rest"]:
                self.objects[sym.section].append(sym)
        self.functions.sort(key=lambda s: s.address)
        for objects in self.objects.values():
            objects.sort(key=lambda s: s.address)


class Alignment:
    """The two lists' symbols paired by size, then checked against the
    distance between the releases' addresses (see the top of the file)."""

    def __init__(self, other: list[Symbol], ours: list[Symbol], check: bool = True):
        matcher = difflib.SequenceMatcher(None, [s.size for s in other], [s.size for s in ours], autojunk=False)
        blocks = matcher.get_matching_blocks()  # ends with a zero-size block at both ends
        # The long runs: (first index in other, last, distance at the first, at the last).
        self.anchors = [(b.a, b.a + b.size - 1, ours[b.b].address - other[b.a].address,
                         ours[b.b + b.size - 1].address - other[b.a + b.size - 1].address)
                        for b in blocks if b.size >= ANCHOR]
        self.starts = [a[0] for a in self.anchors]
        candidates: list[tuple[int, int, str]] = []  # index in other, in ours, how
        self.gaps: list[tuple[int, int, int, int]] = []  # other[a:b] and ours[c:d] left over
        a_end = b_end = 0
        for block in blocks:
            gap_a, gap_b = block.a - a_end, block.b - b_end
            if gap_a and gap_a == gap_b:
                candidates += [(a_end + k, b_end + k, "position") for k in range(gap_a)]
            elif gap_a or gap_b:
                self.gaps.append((a_end, block.a, b_end, block.b))
            how = "run" if block.size >= ANCHOR else "size"
            candidates += [(block.a + k, block.b + k, how) for k in range(block.size)]
            a_end, b_end = block.a + block.size, block.b + block.size

        paired: dict[int, tuple[int, str]] = {}  # index in other -> index in ours, how
        self.dropped = 0
        for i, j, how in candidates:
            if how == "run" or not check or ours[j].address - other[i].address in self.distances(i):
                paired[i] = (j, how)
            else:
                self.dropped += 1
        self.other, self.ours, self.paired = other, ours, paired
        self.taken = {j for j, _ in paired.values()}
        self.index_of = {s.address: j for j, s in enumerate(ours)}
        self.recover(same_size=True)

    def recover(self, same_size: bool, skip: set[int] = frozenset(), skip_ours: set[int] = frozenset()) -> None:
        """Pairs the other release's unpaired symbols with ours at their
        address plus the distance there. Of another size only where the runs
        on both sides agree (decomp-toolkit split an 8-byte time into halves
        here, or left a vtable's padding in it)."""
        for i, sym in enumerate(self.other):
            if i in self.paired or i in skip:
                continue
            distances = self.distances(i)
            for distance in distances:
                j = self.index_of.get(sym.address + distance)
                if j is None or j in self.taken or j in skip_ours:
                    continue
                if self.ours[j].size == sym.size if same_size else len(distances) == 1:
                    self.paired[i] = (j, "distance")
                    self.taken.add(j)
                    break
        self.pairs = [(self.other[i], self.ours[j]) for i, (j, _) in sorted(self.paired.items())]
        self.by_position = sum(1 for _, how in self.paired.values() if how == "position")
        self.by_distance = sum(1 for _, how in self.paired.values() if how == "distance")

    def distances(self, i: int) -> set[int]:
        """The distances between the releases in the runs before and after the
        other release's symbol number `i`."""
        k = bisect.bisect_right(self.starts, i)
        result = set()
        if k and self.anchors[k - 1][1] < i:
            result.add(self.anchors[k - 1][3])
        if k < len(self.anchors):
            result.add(self.anchors[k][2])
        return result


def is_name(name: str) -> bool:
    return bool(IDENTIFIER.match(name)) and not GENERATED.match(name) and not PLACEHOLDER.match(name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("decomp_symbols", type=Path, help="the decomp's symbols.txt (the other release)")
    parser.add_argument("symbols", type=Path, help="this game's symbols.txt")
    parser.add_argument("--apply", action="store_true", help="rename generated names in place")
    parser.add_argument("--rename-differing", action="store_true",
                        help="also give functions that already have other names the decomp's")
    parser.add_argument("--verbose", action="store_true", help="list every new name and every disagreement")
    args = parser.parse_args()

    other = SymbolList(args.decomp_symbols)
    for sym in other.functions:
        sym.name = DECOMP_FIXES.get(sym.address, sym.name)
    ours = SymbolList(args.symbols)
    used = ours.names
    reserved = handwritten_names()

    # Functions are not checked: their sizes are distinctive, and between two
    # functions of other sizes there can be a short run at a distance of its own.
    functions = Alignment(other.functions, ours.functions, check=False)
    function_pairs = functions.pairs
    object_pairs: list[tuple[Symbol, Symbol]] = []
    object_by_position = object_by_distance = object_dropped = 0
    splits: list[tuple[list[Symbol], Symbol]] = []  # the decomp's pieces, the object they cover
    for section, objects in other.objects.items():
        mine = ours.objects.get(section, [])
        alignment = Alignment(objects, mine)
        split_pieces, split_targets = set(), set()
        for a, b, c, d in alignment.gaps:
            indices = [i for i in range(a, b) if i not in alignment.paired]
            if len(indices) < 2 or d - c != 1 or c in alignment.taken:
                continue
            pieces, target = [objects[i] for i in indices], mine[c]
            span = pieces[-1].address + pieces[-1].size - pieces[0].address
            if (span == target.size and target.address - pieces[0].address in alignment.distances(indices[0])
                    and GENERATED.match(target.name) and any(is_name(p.name) for p in pieces)):
                splits.append((pieces, target))
                split_pieces.update(indices)
                split_targets.add(c)
        alignment.recover(same_size=False, skip=split_pieces, skip_ours=split_targets)
        object_pairs += alignment.pairs
        object_by_position += alignment.by_position
        object_by_distance += alignment.by_distance
        object_dropped += alignment.dropped

    address_map = {a.address: b.address for a, b in function_pairs + object_pairs}
    for pieces, target in splits:
        address_map.update({p.address: p.address - pieces[0].address + target.address for p in pieces})

    def translate(name: str) -> str | None:
        """The decomp's name with this release's addresses, or None if an
        address inside it is no symbol's here."""
        m = ADDRESS_SUFFIX.match(name)
        stem, suffix = (m["base"], int(m["hex"], 16)) if m else (name, None)
        # Addresses inside the name are the other release's too.
        inside = [int(e[1], 16) for e in EMBEDDED_ADDRESS.finditer(stem)]
        if any(value not in address_map for value in inside):
            return None
        stem = EMBEDDED_ADDRESS.sub(lambda e: f"{address_map[int(e[1], 16)]:08X}", stem)
        if suffix is None:
            return stem
        return f"{stem}_{address_map[suffix]:08X}" if suffix in address_map else stem

    def new_name(name: str, address: int) -> str | None:
        """The decomp's name for the symbol at `address` here, or None if it cannot be used."""
        name = translate(name)
        if name is None:
            return None
        if name in used or name in reserved:
            # Two decomp symbols can share a name once an address is dropped
            # (DeadCounter_Set_ced48 and _806d0fb8): tell them apart by this
            # release's address.
            name = f"{name}_{address:08X}"
        if name in used or name in reserved:
            return None
        used.add(name)
        return name

    replacements: dict[int, list[str]] = {}  # line of ours -> its new lines
    counts = {"functions": [0, 0], "objects": [0, 0]}  # new names, already named the same
    differ, skipped = [], []

    pinned: list[Symbol] = []  # hand-written code uses their names: not renamed
    renamed: list[tuple[str, str]] = []  # --rename-differing: (old name, new name)

    def base(name: str) -> str:
        m = ADDRESS_SUFFIX.match(name)
        return m["base"] if m else name

    def rename(kind: str, a: Symbol, b: Symbol) -> None:
        if not is_name(a.name):
            return
        if not GENERATED.match(b.name):
            translated = translate(a.name)
            if translated is None:
                differ.append((b, a.name))
            elif b.name in (translated, f"{translated}_{b.address:08X}"):
                counts[kind][1] += 1
            elif (args.rename_differing and kind == "functions" and b.name not in reserved
                    and base(b.name) != base(translated)):
                # Objects are left alone: their names are more often the
                # decomp's own guesses, and few were named another way.
                name = new_name(a.name, b.address)
                if name is None:
                    skipped.append((b, a.name))
                    return
                renamed.append((b.name, name))
                replacements[b.line] = [f"{name} = {b.section}:0x{b.address:08X};{b.rest}"]
            else:
                differ.append((b, a.name))
            return
        if b.name in reserved:
            pinned.append(b)
            return
        name = new_name(a.name, b.address)
        if name is None:
            skipped.append((b, a.name))
            return
        rest = b.rest if "scope:" in b.rest else b.rest + " scope:global"
        replacements[b.line] = [f"{name} = {b.section}:0x{b.address:08X};{rest}"]
        counts[kind][0] += 1

    for a, b in function_pairs:
        rename("functions", a, b)
    for a, b in object_pairs:
        rename("objects", a, b)
    split_names = 0
    for pieces, target in list(splits):
        if target.name in reserved:
            pinned.append(target)
            splits.remove((pieces, target))
            continue
        new_lines = []
        for p in pieces:
            address = p.address - pieces[0].address + target.address
            name = new_name(p.name, address) if is_name(p.name) else None
            kind = DATA_KIND.search(p.rest)
            attrs = f" // type:object size:0x{p.size:X}" + (f" {kind[0]}" if kind else "")
            if name:
                split_names += 1
                attrs += " scope:global"
            else:
                name = f"lbl_{address:08X}"
            new_lines.append(f"{name} = {target.section}:0x{address:08X};{attrs}")
        replacements[target.line] = new_lines

    print(f"functions: {len(other.functions)} and {len(ours.functions)}; {len(function_pairs)} paired "
          f"({functions.by_position} by position, {functions.by_distance} by address; {functions.dropped} "
          f"dropped); new names {counts['functions'][0]}, already named the same {counts['functions'][1]}")
    print(f"objects: {sum(map(len, other.objects.values()))} and {sum(map(len, ours.objects.values()))}; "
          f"{len(object_pairs)} paired ({object_by_position} by position, {object_by_distance} by address; "
          f"{object_dropped} dropped); new names {counts['objects'][0]}, "
          f"already named the same {counts['objects'][1]}; {len(splits)} objects split for {split_names} more")
    print(f"named differently (kept): {len(differ)}; name in use (skipped): {len(skipped)}; "
          f"used by hand-written code (not renamed): {len(pinned)}")
    if renamed:
        print(f"renamed (--rename-differing): {len(renamed)}")
        for old, new in renamed:
            print(f"  renamed: {old} -> {new}")
    if args.verbose:
        for b, name in differ:
            print(f"  note: {b.address:08X} is named {b.name}, the decomp says {name}")
        for b, name in skipped:
            print(f"  skipped: {b.address:08X} {name}")
        for line, new_lines in sorted(replacements.items()):
            print(f"  {ours.lines[line].split(';')[0]} ->")
            for text in new_lines:
                print(f"      {text.split(';')[0]}")

    if args.apply and replacements:
        out = []
        for i, line in enumerate(ours.lines):
            out += replacements.get(i, [line])
        # newline="\n": keep LF line endings on Windows (decomp-toolkit writes LF).
        args.symbols.write_text("\n".join(out) + "\n", encoding="utf-8", newline="\n")
        print(f"updated {args.symbols}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
