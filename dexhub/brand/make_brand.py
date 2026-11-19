#!/usr/bin/env python3
"""Dex Hub's launcher icons and TV banners, in place of Arc's.

    python3 dexhub/brand/make_brand.py FONT.ttf

FONT is League Spartan ExtraBold (SIL OFL 1.1), the face of Dex Hub's wordmark;
it is not kept here, only what it draws. mark.png is Dex Hub's infinity mark
(skin.dexhub media/dexhub/brand_mark_lavender.png), 382 pixels wide: it is
enlarged four times with its edges kept sharp (a flat logo) before anything is
drawn from it.

Every image Arc ships for its launcher (app/src/main/res) is drawn again at
its own size and under its own name in app/src/full/res, which Android takes
over the main one: Arc's files stay as they are, so its updates merge, and the
manifest and the icon picker need nothing else:

* mipmap-*/ic_launcher[_colour].png        the mark on Dex Hub's navy, rounded
* drawable-nodpi/arc_launcher_foreground[_colour].webp
                                           the adaptive icon's mark, in its safe zone
* drawable-nodpi/dexhub_monochrome.webp    the themed icon's silhouette
* drawable-nodpi/app_logo_wordmark[_glass[_small]].png
                                           the logo inside the app (ArcBrandArt): the mark in the
                                           lockup's left 318/1209, DEX HUB beside it; glass in silver
* drawable-nodpi/dexhub_intro_mark.png, dexhub_intro_lettering.png
                                           the entrance's art (DexHubIntroScene), sized for a 4K stage
* mipmap-*/banner[_colour].webp            mark over DEX HUB
* mipmap-*/banner_card[_clear]_colour.webp mark beside DEX HUB
* mipmap-*/banner_lettering[_clear]_colour.webp
                                           DEX HUB in the colour, a small mark over it

The colour of each of Arc's sixteen icons becomes the mark's colour; "original"
(and the names without a colour) keep Dex Hub's lavender. A banner keeps the
background Arc gave it (navy, grey, or clear).
"""
import argparse
import functools
import glob
import os
import re

import numpy

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
NAVY = (15, 20, 39)
COLOURS = {
    'original': None, 'amber': (234, 155, 54), 'arctic_blue': (67, 148, 231), 'copper': (199, 138, 100),
    'crimson': (214, 84, 81), 'emerald': (71, 195, 164), 'emerald_green': (99, 173, 96),
    'glass': (190, 196, 206), 'gold': (213, 171, 69), 'graphite': (150, 158, 170), 'jade': (62, 205, 131),
    'ocean': (65, 150, 217), 'rose': (212, 72, 124), 'rose_gold': (222, 127, 164), 'violet': (155, 80, 185),
    'white': (236, 238, 242),
}
LAVENDER = (139, 124, 246)
NAME = re.compile(r'^(ic_launcher|arc_launcher_foreground|banner_card_clear|banner_card|'
                  r'banner_lettering_clear|banner_lettering|banner)(?:_(%s))?\.(png|webp)$'
                  % '|'.join(sorted(COLOURS, key=len, reverse=True)))


@functools.lru_cache(maxsize=None)
def _source():
    """mark.png four times larger: colours resampled, the edge of the alpha steepened."""
    small = Image.open(os.path.join(HERE, 'mark.png')).convert('RGBA')
    big = small.resize((small.width * 4, small.height * 4), Image.LANCZOS)
    px = numpy.asarray(big).astype(numpy.float32)
    alpha = numpy.clip((px[..., 3] - 128.0) * 2.6 + 128.0, 0, 255)
    px[..., 3] = alpha
    return Image.fromarray(px.astype(numpy.uint8), 'RGBA')


@functools.lru_cache(maxsize=None)
def _mark(colour):
    src = _source()
    if colour is None:
        return src
    px = numpy.asarray(src).astype(numpy.float32)
    rgb = px[..., :3]
    tinted = (rgb.max(axis=2) - rgb.min(axis=2)) >= 40      # not the white triangle
    shade = (rgb @ numpy.array([0.299, 0.587, 0.114])) / (0.299 * 139 + 0.587 * 124 + 0.114 * 246)
    rgb[tinted] = numpy.minimum(255, shade[tinted, None] * numpy.array(colour, numpy.float32))
    return Image.fromarray(px.astype(numpy.uint8), 'RGBA')


def mark(colour):
    """The infinity mark in a colour; its play triangle stays white."""
    return _mark(colour).copy()


def fit(img, width):
    return img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)


def lettering(font_path, height, colour=(255, 255, 255)):
    """DEX HUB at a cap height, a little tighter than the face, as the wordmark."""
    size = int(height / 0.72)
    font = ImageFont.truetype(font_path, size)
    gap = -size * 0.02
    widths = [font.getlength(ch) for ch in 'DEX HUB']
    canvas = Image.new('RGBA', (int(sum(widths) + gap * 6) + size, size * 2), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    x = size // 2
    for ch, w in zip('DEX HUB', widths):
        draw.text((x, size // 2), ch, font=font, fill=colour + (255,))
        x += w + gap
    return canvas.crop(canvas.getbbox())


def paste_centre(canvas, img, cx, cy):
    canvas.alpha_composite(img, (round(cx - img.width / 2), round(cy - img.height / 2)))


def icon(size, colour):
    scale = 4
    big = Image.new('RGBA', (size * scale, size * scale), (0, 0, 0, 0))
    ImageDraw.Draw(big).rounded_rectangle((0, 0, big.width - 1, big.height - 1), radius=int(big.width * 0.22),
                                          fill=NAVY + (255,))
    paste_centre(big, fit(mark(colour), int(big.width * 0.72)), big.width / 2, big.height / 2)
    return big.resize((size, size), Image.LANCZOS)


def foreground(size, colour, white=False):
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    m = mark(colour)
    if white:
        m = Image.new('RGBA', m.size, (255, 255, 255, 0))
        m.putalpha(_source().getchannel('A'))
    paste_centre(canvas, fit(m, int(size * 0.56)), size / 2, size / 2)   # inside the 66 % safe circle
    return canvas


def banner(kind, size, colour, background, font):
    w, h = size
    canvas = Image.new('RGBA', size, background)
    tint = colour or LAVENDER
    if kind == 'banner':
        m = fit(mark(colour), int(w * 0.36))
        text = lettering(font, int(h * 0.105))
        gap = int(h * 0.09)
        top = (h - m.height - gap - text.height) / 2
        paste_centre(canvas, m, w / 2, top + m.height / 2)
        paste_centre(canvas, text, w / 2, top + m.height + gap + text.height / 2)
    elif kind.startswith('banner_card'):
        m = fit(mark(colour), int(w * 0.25))
        text = lettering(font, int(h * 0.13))
        gap = int(w * 0.04)
        left = (w - m.width - gap - text.width) / 2
        paste_centre(canvas, m, left + m.width / 2, h / 2)
        paste_centre(canvas, text, left + m.width + gap + text.width / 2, h / 2 + text.height * 0.02)
    else:
        m = fit(mark(colour), int(w * 0.17))
        text = lettering(font, int(h * 0.19), tint)
        if text.width > w * 0.84:
            text = fit(text, int(w * 0.84))
        gap = int(h * 0.06)
        top = (h - m.height - gap - text.height) / 2
        paste_centre(canvas, m, w / 2, top + m.height / 2)
        paste_centre(canvas, text, w / 2, top + m.height + gap + text.height / 2)
    return canvas


def lockup(size, colour, font):
    """Arc's lockup layout (ArcBrandArt): the mark alone in the left 318/1209, the lettering after it."""
    w, h = size
    canvas = Image.new('RGBA', size, (0, 0, 0, 0))
    region = w * 318 / 1209
    m = fit(mark(colour), int(region * 0.94))
    if m.height > h * 0.66:
        m = m.resize((int(m.width * h * 0.66 / m.height), int(h * 0.66)), Image.LANCZOS)
    paste_centre(canvas, m, region / 2, h / 2)
    room = int(w - region - h * 0.16)
    text = lettering(font, int(h * 0.6))
    text = fit(text, room) if text.height * room / text.width <= h * 0.5 else \
        text.resize((int(text.width * h * 0.5 / text.height), int(h * 0.5)), Image.LANCZOS)
    paste_centre(canvas, text, region + h * 0.12 + text.width / 2, h / 2)
    return canvas


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('font')
    ap.add_argument('--lockups-only', action='store_true')
    args = ap.parse_args()
    args.res = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res')
    out = os.path.join(HERE, '..', '..', 'app', 'src', 'full', 'res')
    done = 0
    for name, colour in (('app_logo_wordmark', None), ('app_logo_wordmark_glass', COLOURS['glass']),
                         ('app_logo_wordmark_glass_small', COLOURS['glass'])):
        old = Image.open(os.path.join(args.res, 'drawable-nodpi', name + '.png'))
        target = os.path.join(out, 'drawable-nodpi', name + '.png')
        os.makedirs(os.path.dirname(target), exist_ok=True)
        lockup(old.size, colour, args.font).save(target, optimize=True)
        done += 1
    if args.lockups_only:
        print('%d lockups drawn' % done)
        return
    for path in sorted(glob.glob(os.path.join(args.res, 'mipmap-*', '*')) +
                       glob.glob(os.path.join(args.res, 'drawable-nodpi', 'arc_launcher_foreground*'))):
        found = NAME.match(os.path.basename(path))
        if not found:
            continue
        kind, colour_name = found.group(1), found.group(2) or 'original'
        colour = COLOURS[colour_name]
        old = Image.open(path)
        size = old.size
        if kind == 'ic_launcher':
            new = icon(size[0], colour)
        elif kind == 'arc_launcher_foreground':
            new = foreground(size[0], colour)
        else:
            corner = old.convert('RGBA').getpixel((2, 2))
            background = (0, 0, 0, 0) if corner[3] == 0 else (NAVY + (255,) if kind == 'banner' else corner)
            new = banner(kind, size, colour, background, args.font)
        target = os.path.join(out, os.path.relpath(path, args.res))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if path.endswith('.webp'):
            if old.mode == 'RGB':
                new = new.convert('RGB')
            new.save(target, 'WEBP', quality=92, method=6)
        else:
            new.save(target, optimize=True)
        done += 1
    fit(mark(None), 1100).save(os.path.join(out, 'drawable-nodpi', 'dexhub_intro_mark.png'), optimize=True)
    lettering(args.font, 200).save(os.path.join(out, 'drawable-nodpi', 'dexhub_intro_lettering.png'), optimize=True)
    mono = os.path.join(out, 'drawable-nodpi', 'dexhub_monochrome.webp')
    foreground(432, None, white=True).save(mono, 'WEBP', lossless=True)
    print('%d images drawn in %s, and %s' % (done, os.path.relpath(out), os.path.relpath(mono, out)))


if __name__ == '__main__':
    main()
