#!/usr/bin/env python3
"""Dex Hub TV's name in Arc's texts, every language.

    python3 dexhub/brand/make_strings.py

Each string of app/src/main/res/values*/*.xml that names "Nuvio Arc" is written
again, with "Dex Hub TV" in its place, to app/src/full/res/values*/dexhub_strings.xml,
which Android takes over the main one. Arc's files are not changed, so its
updates merge; run this again after one to bring the names up to date.

What credits Nuvio and Arc keeps their names (KEEP): the licences page's title
for Nuvio Arc, and About's line, which gets Dex Hub TV in front of it.
"""
import glob
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
MAIN = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res')
OUT = os.path.join(HERE, '..', '..', 'app', 'src', 'full', 'res')
OLD, NEW = 'Nuvio Arc', 'Dex Hub TV'
KEEP = {'licenses_attributions_nuvio_title'}
CREDIT = {'about_made_with_love'}               # "Dex Hub TV · " + Arc's own line
STRING = re.compile(r'<(string|plurals|string-array)\s+name="([^"]+)"[^>]*>.*?</\1>', re.S)
HEAD = '<?xml version="1.0" encoding="utf-8"?>\n<!-- dexhub/brand/make_strings.py: Arc\'s texts with Dex Hub TV\'s name -->\n<resources>\n'


def main():
    written = 0
    for folder in sorted(glob.glob(os.path.join(MAIN, 'values*'))):
        found = {}
        for path in sorted(glob.glob(os.path.join(folder, '*.xml'))):
            with open(path, encoding='utf-8') as handle:
                text = handle.read()
            for m in STRING.finditer(text):
                block, name = m.group(0), m.group(2)
                if OLD not in block or name in KEEP:
                    continue
                if name in CREDIT:
                    block = re.sub(r'(<string[^>]*>)', r'\1' + NEW + ' · ', block, count=1)
                else:
                    block = block.replace(OLD, NEW)
                found[name] = block
        target = os.path.join(OUT, os.path.basename(folder), 'dexhub_strings.xml')
        if not found:
            if os.path.exists(target):
                os.remove(target)
            continue
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, 'w', encoding='utf-8') as handle:
            handle.write(HEAD + ''.join('    %s\n' % found[n] for n in sorted(found)) + '</resources>\n')
        written += len(found)
    print('%d strings with %s' % (written, NEW))


if __name__ == '__main__':
    main()
