#!/usr/bin/env python3
# Copyright 2026 The Dogo Authors. SPDX-License-Identifier: Apache-2.0
"""Prepare Dogo-only brand assets from the user's square logo and portrait art.

Usage: python prepare_brand_assets.py square-logo.png portrait-wallpaper.png
Requires Pillow. All generated assets stay in the dogo source set.
"""
from pathlib import Path
import argparse
from PIL import Image, ImageDraw, ImageFilter, ImageOps


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("logo", type=Path)
    parser.add_argument("wallpaper", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    res = root / "dogo/res"
    target = res / "drawable-nodpi"
    target.mkdir(parents=True, exist_ok=True)
    logo = Image.open(args.logo).convert("RGB").resize((1254, 1254), Image.Resampling.LANCZOS)
    logo.save(target / "dogo_brand_logo.webp", quality=95)
    # Trace the mascot rather than retaining the baked-in square frame, text,
    # surrounding orbit decoration and floor reflection in the adaptive icon.
    outline = [(350,150),(384,136),(488,254),(554,282),(668,280),
               (772,254),(866,136),(900,150),(908,318),(908,401),
               (898,450),(905,495),(902,570),(878,625),(830,666),
               (804,709),(740,750),(635,770),(533,758),(455,725),
               (425,678),(380,634),(350,566),(348,492),(352,438),
               (340,370),(342,270)]
    mask = Image.new("L", logo.size)
    ImageDraw.Draw(mask).polygon(outline, fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(3))
    mascot = logo.convert("RGBA")
    mascot.putalpha(mask)
    mascot = mascot.crop((330,125,920,785))
    mascot.thumbnail((186,208), Image.Resampling.LANCZOS)
    foreground = Image.new("RGBA", (432,432))
    foreground.alpha_composite(mascot, ((432-mascot.width)//2,(432-mascot.height)//2))
    foreground.save(target / "dogo_icon_foreground.webp", lossless=True)
    background = Image.new("RGB",(432,432),(17,8,44))
    background.save(target / "dogo_icon_background.webp", lossless=True)
    composite = background.convert("RGBA")
    composite.alpha_composite(foreground)
    # Legacy previews use the same central 72/108 viewport as adaptive icons.
    icon = composite.crop((72,72,360,360))
    for density,size in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)]:
        out=res/f"mipmap-{density}"
        out.mkdir(parents=True,exist_ok=True)
        icon.resize((size,size),Image.Resampling.LANCZOS).save(out/"ic_launcher_home.png")
        circle=Image.new("L",(size,size)); ImageDraw.Draw(circle).ellipse((0,0,size-1,size-1),fill=255)
        rounded=icon.resize((size,size),Image.Resampling.LANCZOS); rounded.putalpha(circle)
        rounded.save(out/"ic_launcher_home_round.png")
    # Fit the 9:16 illustration in a taller phone canvas rather than cropping
    # its edge panels. Reserve a quiet bottom strip for launcher controls.
    art=Image.open(args.wallpaper).convert("RGB")
    art=ImageOps.contain(art, (1440,2560), Image.Resampling.LANCZOS)
    canvas=Image.new("RGB",(1440,2880),art.getpixel((0,0)))
    canvas.paste(art,((1440-art.width)//2,0))
    canvas.save(target/"dogo_brand_wallpaper.webp",quality=95)
    preview=Image.new("RGB",(864,432),(30,30,30))
    for index,shape in enumerate(["circle","round"]):
        tile=icon.resize((360,360),Image.Resampling.LANCZOS)
        m=Image.new("L",tile.size)
        draw=ImageDraw.Draw(m)
        if shape=="circle":draw.ellipse((0,0,359,359),fill=255)
        else:draw.rounded_rectangle((0,0,359,359),radius=80,fill=255)
        preview.paste(tile.convert("RGB"),(index*432+36,36),m)
    preview.save(root/"docs/assets/dogo-icon-preview.png")
    print("Generated Dogo icon layers, legacy icons, logo and 1440x2880 wallpaper")


if __name__ == "__main__":
    main()
