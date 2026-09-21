"""Packs the Theatre face's films into the zip the unit fetches.

    python tools/faces/pack_faces.py

Every .mp4 in this folder goes into faces.zip, which Settings > Developer > Fetch backdrops
pulls over the Wi-Fi. See PROMPTS.md for what makes a film work behind the numbers.
"""
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    films = sorted(f for f in os.listdir(HERE) if f.lower().endswith(".mp4"))
    if not films:
        print("no .mp4 in " + HERE + " - generate one from PROMPTS.md and drop it here")
        return
    out = os.path.join(HERE, "faces.zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:       # already compressed
        for f in films:
            z.write(os.path.join(HERE, f), f)
            mb = os.path.getsize(os.path.join(HERE, f)) / 1e6
            print("  %-34s %6.1f MB%s" % (f, mb, "   (heavy - see PROMPTS.md)" if mb > 40 else ""))
    print("wrote %s - %d film%s, %.1f MB" % (out, len(films), "" if len(films) == 1 else "s", os.path.getsize(out) / 1e6))


if __name__ == "__main__":
    main()
