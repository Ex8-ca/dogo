# Dogo brand assets

The dog-logo artwork and portrait wallpaper were supplied by Marc for Dogo.
The full square lockup is `dogo_brand_logo.webp`; the portrait composition is
fitted to a 1440 × 2880 canvas as `dogo_brand_wallpaper.webp` without cropping
its side panels. A quiet bottom strip is reserved for launcher controls. This
does not add detail beyond the supplied source. On the Pixel, the existing dock
still overlaps part of the low wordmark; the face stays clear.

The adaptive app icon uses an extracted face from the square illustration,
with extra padding for circular masks. Its background is a separate navy
layer; the small wordmark/frame are deliberately omitted for readability.
The monochrome themed-icon fallback is a simplified dog silhouette rather
than a recolouring of the neon illustration.

Regenerate with Pillow:

    python tools/dev/prepare_brand_assets.py square-logo.png portrait-wallpaper.png

Assets live only in the `dogo` source set; upstream launcher variants retain
their existing branding. Preview: `docs/assets/dogo-icon-preview.png`.

Wallpaper matching uses Android's reported wallpaper colours, not unrestricted
access to the wallpaper bitmap. When enabled, Dogo's launcher/chat/settings
follow that palette and retain readable tonal surfaces in dark/light modes.
Manual accents remain available. Changes to other apps, Quick Settings and
system controls depend on Android's own Wallpaper & style palette setting
and each app's dynamic-colour support; Dogo does not force arbitrary third-party
apps to change colours.
