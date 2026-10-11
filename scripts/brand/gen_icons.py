#!/usr/bin/env python3
"""Brand mark generator: launcher foreground/monochrome, notification icon, Play icon. Run from anywhere; needs pycairo."""
import math, cairo, os
R = os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'res')
TEAL=(0,0x6A/255,0x60/255); MINT=(0x9E/255,0xF2/255,0xE4/255)
G=16  # gap half-angle
SEGS=[(-60+G, 180-G, 'FFFFFFFF', MINT, 'parent'), (180+G, 300-G, 'FF9EF2E4', MINT, 'child')]
def arc_path(cx, cy, r, a0, a1):
    x0, y0 = cx + r*math.cos(math.radians(a0)), cy + r*math.sin(math.radians(a0))
    x1, y1 = cx + r*math.cos(math.radians(a1)), cy + r*math.sin(math.radians(a1))
    large = 1 if (a1-a0) > 180 else 0
    return f"M{x0:.2f},{y0:.2f} A{r},{r} 0 {large},1 {x1:.2f},{y1:.2f}"
def vector(size, r, w, colors, comment):
    s = 108/size
    paths = "\n".join(
        f'    <path\n        android:pathData="{arc_path(size/2, size/2, r, a0, a1)}"\n        android:strokeWidth="{w}"\n        android:strokeColor="#{c}"\n        android:strokeLineCap="round" />'
        for (a0, a1, _, _, _), c in zip(SEGS, colors))
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="{size}"
    android:viewportHeight="{size}">
{paths}
</vector>
'''
open(f'{R}/drawable/ic_launcher_foreground.xml','w').write(vector(108, 24, 10, ['FFFFFFFF','FF9EF2E4'],
  'Brand mark: the ring of a day in two segments, the parent (long, white) and the child (short, mint). Fits the 66dp safe zone.'))
open(f'{R}/drawable/ic_launcher_monochrome.xml','w').write(vector(108, 24, 10, ['FF000000','FF000000'],
  'Themed-icon layer (Android 13+): the same mark in one colour; the system tints it.'))
open(f'{R}/drawable/ic_stat_kinsight.xml','w').write(vector(24, 6.5, 2.6, ['FFFFFFFF','FFFFFFFF'],
  'Notification small icon: the brand mark in white only (the system tints it).'))
# Play icon 512x512: the visible 72dp area maps to the full square.
S=512; k=S/72
surf=cairo.ImageSurface(cairo.FORMAT_ARGB32,S,S); cr=cairo.Context(surf)
cr.set_source_rgb(*TEAL); cr.paint()
cr.set_line_cap(cairo.LINE_CAP_ROUND); cr.set_line_width(10*k)
for (a0,a1,_,_,_),col in zip(SEGS,[(1,1,1),MINT]):
    cr.set_source_rgb(*col); cr.arc(S/2,S/2,24*k,math.radians(a0),math.radians(a1)); cr.stroke()
os.makedirs(os.path.join(os.path.dirname(__file__), '..', '..', 'docs', 'play'),exist_ok=True)
surf.write_to_png(os.path.join(os.path.dirname(__file__), '..', '..', 'docs', 'play', 'icon-512.png'))
