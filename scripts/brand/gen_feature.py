#!/usr/bin/env python3
"""Play feature graphic 1024x500 (en and ru): the brand mark, the wordmark and one line. Needs pycairo + Noto Sans."""
import math, os, cairo
OUT = os.path.join(os.path.dirname(__file__), '..', '..', 'docs', 'play')
TEAL = (0, 0x6A/255, 0x60/255); MINT = (0x9E/255, 0xF2/255, 0xE4/255)
G = 16
SEGS = [(-60+G, 180-G, (1, 1, 1)), (180+G, 300-G, MINT)]
def mark(cr, cx, cy, r, w):
    cr.set_line_cap(cairo.LINE_CAP_ROUND); cr.set_line_width(w)
    for a0, a1, col in SEGS:
        cr.set_source_rgb(*col); cr.arc(cx, cy, r, math.radians(a0), math.radians(a1)); cr.stroke()
def render(lang, tagline):
    W, H = 1024, 500
    s = cairo.ImageSurface(cairo.FORMAT_ARGB32, W, H); cr = cairo.Context(s)
    cr.set_source_rgb(*TEAL); cr.paint()
    mark(cr, 215, 250, 100, 44)
    cr.select_font_face("Noto Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    cr.set_font_size(96); cr.set_source_rgb(1, 1, 1)
    cr.move_to(380, 262); cr.show_text("Kinsight")
    cr.select_font_face("Noto Sans", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_NORMAL)
    cr.set_source_rgb(*MINT)
    size = 32
    while size > 18:  # shrink until the line fits with a right margin
        cr.set_font_size(size)
        if 384 + cr.text_extents(tagline).x_advance <= W - 40: break
        size -= 1
    cr.move_to(384, 318); cr.show_text(tagline)
    s.write_to_png(os.path.join(OUT, f'feature-graphic-{lang}.png'))
render('en', 'Family screen time, phone to phone')
render('ru', 'Экранное время семьи, телефон к телефону')
