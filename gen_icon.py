#!/usr/bin/env python3

from PIL import Image
import os, sys

SIZES = {
    'mipmap-mdpi':    48,
    'mipmap-hdpi':    72,
    'mipmap-xhdpi':   96,
    'mipmap-xxhdpi':  144,
    'mipmap-xxxhdpi': 192,
}
SRC = 'icon.jpg'
BASE = 'android/app/src/main/res'

if not os.path.exists(SRC):
    print(f'[!] {SRC} not found. Put icon.jpg in project root.'); sys.exit(1)

img = Image.open(SRC).convert('RGBA')
for folder, size in SIZES.items():
    out_dir = os.path.join(BASE, folder)
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, 'ic_launcher.png')
    img.resize((size, size), Image.LANCZOS).save(out)
    print(f'[+] {out} ({size}x{size})')

print('[✓] Icon generated!')
