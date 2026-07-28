#!/usr/bin/env python3
"""Format a frosted GemCutStudio .gcs from a FrostDump intermediate.

FrostDump.java does the expensive edge-frosting (via Sean O'Neil's tool
algorithm on a verbatim-loaded gem) and writes a fast text dump of every
resulting facet:  lines "F <0|1 frosted> <tierName> nx ny nz" each followed
by its "V x y z" vertices.  This script just re-emits that as a valid .gcs,
preserving the original P1/G1/.../T tier names for polished facets and putting
all frosted bevel facets in an "FR" tier with frosting="0.5".

Usage: python format_frosted.py <original.gcs> <frost.dump> <out.gcs>
"""
import re
import sys


def read_block(text, tag):
    """Return the raw '<tag ...>...</tag>' or '<tag .../>' substring, or ''."""
    m = re.search(r"<%s\b[^>]*/>" % tag, text)
    if m:
        return m.group(0)
    m = re.search(r"<%s\b.*?</%s>" % (tag, tag), text, re.S)
    return m.group(0) if m else ""


def parse_dump(path):
    facets = []
    cur = None
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            p = line.split()
            if p[0] == "F":
                cur = {"frosted": p[1] == "1", "tier": p[2],
                       "n": (p[3], p[4], p[5]), "verts": []}
                facets.append(cur)
            elif p[0] == "V":
                cur["verts"].append((p[1], p[2], p[3]))
    return facets


def main():
    orig, dump, out = sys.argv[1], sys.argv[2], sys.argv[3]
    otext = open(orig, "r", encoding="utf-8").read()

    index_block = read_block(otext, "index")
    render_block = read_block(otext, "render")
    info_block = read_block(otext, "info")

    # original tier order (names, in file order)
    tier_order = re.findall(r'<tier\b[^>]*\bname="([^"]*)"', otext)
    seen, order = set(), []
    for t in tier_order:
        if t not in seen:
            seen.add(t); order.append(t)

    facets = parse_dump(dump)
    by_tier = {}
    for fc in facets:
        key = "FR" if fc["frosted"] else fc["tier"]
        by_tier.setdefault(key, []).append(fc)

    # emit polished tiers in original order, then any leftovers, then FR last
    emit_order = [t for t in order if t in by_tier and t != "FR"]
    emit_order += [t for t in by_tier if t not in emit_order and t != "FR"]
    if "FR" in by_tier:
        emit_order.append("FR")

    out_lines = ['<GemCutStudio version="1000">']
    if index_block:
        out_lines.append("\t" + index_block)
    for tname in emit_order:
        fr = (tname == "FR")
        out_lines.append(
            '\t<tier angle="%s" depth="1" name="%s" instructions="" '
            'visible="true" guide="false">' % ("90" if fr else "0", tname))
        for fc in by_tier[tname]:
            nx, ny, nz = fc["n"]
            fattr = ' frosting="0.5"' if fr else ""
            out_lines.append('\t\t<facet nx="%s" ny="%s" nz="%s" '
                             'index_angle="0"%s>' % (nx, ny, nz, fattr))
            for (x, y, z) in fc["verts"]:
                out_lines.append('\t\t\t<vertex x="%s" y="%s" z="%s"/>' % (x, y, z))
            out_lines.append("\t\t</facet>")
        out_lines.append("\t</tier>")
    if render_block:
        out_lines.append("\t" + render_block)
    if info_block:
        out_lines.append("\t" + info_block)
    out_lines.append("</GemCutStudio>")

    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(out_lines) + "\n")

    npol = sum(len(v) for k, v in by_tier.items() if k != "FR")
    nfr = len(by_tier.get("FR", []))
    print("wrote %s: %d facets (%d polished, %d frosted) across %d tiers"
          % (out, npol + nfr, npol, nfr, len(emit_order)))


if __name__ == "__main__":
    main()
