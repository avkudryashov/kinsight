#!/usr/bin/env python3
"""Cuts the ChatGPT mascot sheets (one PNG per animal, 3x2 grid of moods, see PROMPTS.md) into app drawables.

Usage: python3 scripts/brand/mascots/slice_mascots.py <folder with cat.png, dog.png, ...>
Writes core/designsystem/src/main/res/drawable-nodpi/mascot_<animal>_<mood>.webp (256x256, transparent). Needs Pillow.
"""
import os
import sys

from PIL import Image

ANIMALS = ["cat", "dog", "fox", "panda", "rabbit", "bear", "owl", "penguin", "frog", "raccoon"]  # core/model Animal order
MOODS = ["happy", "content", "calm", "worried", "upset", "unwell"]  # core/model Mood order; grid left-right, top-bottom
COLS, ROWS = 3, 2
OUT_SIZE = 256
PADDING = 0.06  # empty margin around the head inside the square, as a share of the side
ALPHA_MIN = 16  # pixels this transparent or less count as background when trimming

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
                   "core", "designsystem", "src", "main", "res", "drawable-nodpi")


def trimmed_square(cell: Image.Image) -> Image.Image | None:
    """The cell's visible content, centred on a transparent square with PADDING; None for an empty cell."""
    alpha = cell.getchannel("A").point(lambda a: 255 if a > ALPHA_MIN else 0)
    box = alpha.getbbox()
    if box is None:
        return None
    content = cell.crop(box)
    side = int(max(content.size) / (1 - 2 * PADDING))
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(content, ((side - content.width) // 2, (side - content.height) // 2), content)
    return square.resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS)


def slice_sheet(path: str, animal: str) -> list[str]:
    sheet = Image.open(path).convert("RGBA")
    if sheet.getchannel("A").getextrema()[0] == 255:
        return [f"{animal}: no transparency — ask ChatGPT for a transparent PNG background"]
    w, h = sheet.width // COLS, sheet.height // ROWS
    cells = []
    for i, mood in enumerate(MOODS):
        col, row = i % COLS, i // COLS
        square = trimmed_square(sheet.crop((col * w, row * h, (col + 1) * w, (row + 1) * h)))
        if square is None:
            return [f"{animal}: cell {i + 1} ({mood}) is empty"]
        cells.append((mood, square))
    os.makedirs(OUT, exist_ok=True)
    for mood, square in cells:
        square.save(os.path.join(OUT, f"mascot_{animal}_{mood}.webp"), "WEBP", quality=90, method=6)
    return []


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    folder = os.path.expanduser(sys.argv[1])
    problems, done = [], 0
    for animal in ANIMALS:
        path = os.path.join(folder, f"{animal}.png")
        if not os.path.isfile(path):
            problems.append(f"{animal}: {path} not found")
            continue
        errors = slice_sheet(path, animal)
        problems += errors
        done += 0 if errors else 1
    print(f"written: {done} of {len(ANIMALS)} animals ({done * len(MOODS)} images) into {os.path.normpath(OUT)}")
    for p in problems:
        print("  ! " + p)
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
