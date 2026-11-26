#!/usr/bin/env python3
"""Draws every pose of sprites.txt as a PNG sheet, to look at while drawing them.

    python3 -I tools/pixelpeople/preview.py out.png [look...]

A look is key=value pairs: skin= hair= haircolor= acc=a,b letter= shirt= agent= (the
option and agent ids in sprites.txt; no shirt= means the agent's colours). Each person
stands on its agent's bar. The Kotlin renderer (core/.../people/PixelPeople.kt) draws the
same way.
"""
import sys
from PIL import Image

SRC = "core/src/main/resources/com/droidacp/core/people/sprites.txt"

def parse(path):
    secs, cur, grid = [], None, None
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip("\n")
        if grid is not None:
            if line.strip() == "":
                grid = None
                continue
            grid["rows"].append(line.strip())
            continue
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        w = s.split()
        if w[0] in ("palette", "option", "agent", "face", "hair", "accessory", "part", "pose", "sign", "glyph"):
            cur = {"kind": w[0], "name": w[1] if len(w) > 1 else "", "args": w[2:], "lines": [], "grids": []}
            secs.append(cur)
            if w[0] == "glyph":
                grid = {"label": "glyph", "x": 0, "y": 0, "rows": []}
                cur["grids"].append(grid)
        elif w[0] == "grid":
            grid = {"label": w[1], "x": int(w[2]), "y": int(w[3]), "rows": []}
            cur["grids"].append(grid)
            cur["lines"].append(("grid", grid))
        else:
            cur["lines"].append(tuple(w))
    return secs

def hexc(h):
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)

def shade(c, f):
    return (int(c[0] * f), int(c[1] * f), int(c[2] * f), 255)

def mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3)) + (255,)

def build(secs):
    d = {"palette": {}, "options": {}, "agents": {}, "hair": {}, "acc": {}, "part": {}, "pose": {}, "effect": {}, "glyph": {}, "head": None}
    for s in secs:
        k = s["kind"]
        if k == "palette":
            for l in s["lines"]:
                d["palette"][l[0]] = hexc(l[1])
        elif k == "option":
            d["options"].setdefault(s["name"], {})
            for l in s["lines"]:
                d["options"][s["name"]][l[0]] = l[1:]
        elif k == "agent":
            d["agents"][s["name"]] = s["args"]
        elif k == "face":
            d["head"] = s
        elif k == "hair":
            d["hair"][s["name"]] = s
        elif k == "accessory":
            d["acc"][s["name"]] = s
        elif k == "part":
            d["part"][s["name"]] = s["grids"][0]
        elif k == "pose":
            d["pose"][s["name"]] = s
        elif k == "sign":
            d["effect"][s["name"]] = s["grids"][0]
        elif k == "glyph":
            d["glyph"][s["name"]] = s["grids"][0]["rows"]
    return d

W = H = 32

def render(d, pose, look):
    px = [[None] * W for _ in range(H)]
    skin = hexc(d["options"]["skin"][look["skin"]][0])
    hairc = hexc(d["options"]["haircolor"][look["haircolor"]][0])
    chosen = d["options"].get("shirt", {}).get(look.get("shirt") or "")
    shirt = [hexc(chosen[0])] if chosen else [hexc(c) for c in d["agents"].get(look["agent"], d["agents"]["unknown"])]
    pal = dict(d["palette"])
    pal.update({"S": skin, "s": shade(skin, 0.85), "r": mix(skin, (224, 112, 122), 0.35), "H": hairc, "h": shade(hairc, 0.75)})
    lum = sum(0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2] for c in shirt) / len(shirt)
    pal["L"] = pal["k"] if lum > 150 else pal["W"]
    def color(ch, x, y):
        if ch == "T":
            return shirt[y % len(shirt)] if len(shirt) > 1 else shirt[0]
        if ch == "t":
            return shade(shirt[y % len(shirt)] if len(shirt) > 1 else shirt[0], 0.8)
        return pal.get(ch)
    def stamp(rows, ox, oy, extra=None):
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch == ".":
                    continue
                x, y = ox + i, oy + j
                if 0 <= x < W and 0 <= y < H:
                    c = (extra or {}).get(ch) or color(ch, x, y)
                    if c is None:
                        raise SystemExit(f"no colour for {ch!r} in {pose}")
                    px[y][x] = c
    p = d["pose"][pose]
    hx = hy = None
    eyes = "open"
    for l in p["lines"]:
        if l[0] == "head":
            hx, hy = (None, None) if l[1] == "none" else (int(l[1]), int(l[2]))
        elif l[0] == "eyes":
            eyes = l[1]
    def draw_head():
        if hx is None:
            return
        hs = d["hair"][look["hair"]]
        h = d["head"]
        stamp(h["grids"][0]["rows"], hx, hy)
        for l in h["lines"]:
            if l[0] == "eyes" and l[1] == eyes:
                for xy in l[2:]:
                    ex, ey = map(int, xy.split(","))
                    px[hy + ey][hx + ex] = pal["E"]
        for g in hs["grids"]:
            if g["label"] == "front":
                stamp(g["rows"], hx + g["x"], hy + g["y"])
        for a in look["acc"]:
            acc = d["acc"][a]
            extra = {l[0]: hexc(l[1]) for l in acc["lines"] if l[0] in ("A", "a")}
            for g in acc["grids"]:
                stamp(g["rows"], hx + g["x"], hy + g["y"], extra)
    # The hair's back part goes first, under everything.
    if hx is not None:
        for g in d["hair"][look["hair"]]["grids"]:
            if g["label"] == "back":
                stamp(g["rows"], hx + g["x"], hy + g["y"])
    for l in p["lines"]:
        if l[0] == "use":
            g = d["part"][l[1]]
            stamp(g["rows"], int(l[2]), int(l[3]))
        elif l[0] == "grid":
            stamp(l[1]["rows"], l[1]["x"], l[1]["y"])
        elif l[0] == "@head":
            draw_head()
        elif l[0] == "letter" and look.get("letter"):
            g = d["glyph"].get(look["letter"].upper())
            if g:
                stamp([r.replace("#", "L") for r in g], int(l[1]), int(l[2]))
        elif l[0] == "effect":
            stamp(d["effect"][l[1]]["rows"], int(l[2]), int(l[3]))
    return px

BAR_W, BAR_H = 22, 4

def bar(d, agent):
    """The bar under a person: the agent's colours side by side, outlined, corners left out."""
    colors = [hexc(c) for c in d["agents"].get(agent, d["agents"]["unknown"])]
    dark = sum(0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2] for c in colors) / len(colors) < 60
    rows = []
    for y in range(BAR_H):
        row = []
        for x in range(BAR_W):
            ex, ey = x in (0, BAR_W - 1), y in (0, BAR_H - 1)
            if ex and ey:
                row.append(None)
            elif ex or ey:
                row.append(d["palette"]["G" if dark else "k"])
            else:
                c = colors[(x - 1) * len(colors) // (BAR_W - 2)]
                row.append(shade(c, 0.8) if y == BAR_H - 2 else c)
        rows.append(row)
    return rows

def main():
    out = sys.argv[1]
    look = {"skin": "light", "hair": "short", "haircolor": "brown", "acc": [], "letter": "D", "agent": "claude"}
    for kv in sys.argv[2:]:
        k, v = kv.split("=", 1)
        look[k] = [a for a in v.split(",") if a] if k == "acc" else v
    d = build(parse(SRC))
    poses = list(d["pose"])
    if look.get("poses"):
        poses = look["poses"].split(":")
    looks = [dict(look)] * len(poses)
    if look.get("sheet") == "looks":
        # One pose in every hair style, then every accessory, then the shirts.
        pose = look.get("pose", "helper")
        looks, poses = [], []
        colors = list(d["options"]["haircolor"])
        skins = list(d["options"]["skin"])
        for i, h in enumerate(d["hair"]):
            looks.append(dict(look, hair=h, haircolor=colors[i % len(colors)], skin=skins[i % len(skins)])); poses.append(pose)
        for a in d["acc"]:
            looks.append(dict(look, acc=[a])); poses.append(pose)
        for ag in d["agents"]:
            looks.append(dict(look, agent=ag, letter=ag[0])); poses.append(pose)
        for sh in d["options"].get("shirt", {}):
            looks.append(dict(look, shirt=sh, letter=sh[0])); poses.append(pose)
    scale, cols = int(look.get("scale", 5)), int(look.get("cols", 8))
    rows = (len(poses) + cols - 1) // cols
    ch = H + BAR_H + 2  # a cell: the person, its bar, and a pixel's gap below
    img = Image.new("RGBA", (cols * W * scale, rows * ch * scale), (14, 17, 22, 255))
    def put(pixels, ox, oy):
        for y, row in enumerate(pixels):
            for x, c in enumerate(row):
                if c:
                    img.paste(c, (ox + x * scale, oy + y * scale, ox + (x + 1) * scale, oy + (y + 1) * scale))
    for n, name in enumerate(poses):
        ox, oy = (n % cols) * W * scale, (n // cols) * ch * scale
        put(render(d, name, looks[n]), ox, oy)
        put(bar(d, looks[n]["agent"]), ox + (W - BAR_W) // 2 * scale, oy + H * scale)
    img.save(out)
    print(" ".join(poses))

main()
