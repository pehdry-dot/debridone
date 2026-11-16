#!/usr/bin/env bash
# Regenerates the tiny audio files used by the Music tests. They are synthetic sine tones made
# with ffmpeg, tagged and (where the container allows) carrying embedded cover art, so they can be
# committed freely. Requires ffmpeg and python3 with Pillow.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

out="debridone/modules/music/src/test/resources/audio-fixtures"
rm -rf "${out}"
first="${out}/Test Artist/First Album"
second="${out}/Other Artist/Second Album"
loose="${out}/Loose Files"
mkdir -p "${first}" "${second}" "${loose}"

python3 - "${out}" <<'PY'
import sys
from PIL import Image, ImageDraw
out = sys.argv[1]
def cover(path, a, b):
    image = Image.new("RGB", (96, 96))
    draw = ImageDraw.Draw(image)
    for y in range(96):
        t = y / 95
        draw.line([(0, y), (95, y)], fill=tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)))
    draw.ellipse([30, 30, 66, 66], fill=(255, 255, 255))
    image.save(path, quality=80)
cover(f"{out}/cover-first.jpg", (124, 92, 255), (40, 20, 120))
cover(f"{out}/cover-second.jpg", (255, 140, 60), (120, 30, 30))
PY

tone() { # <frequency> <seconds>
  printf -- "-f lavfi -i sine=frequency=%s:sample_rate=44100:duration=%s" "$1" "$2"
}
tags() { # <title> <track> <artist> <album> <genre> <year>
  printf -- '-metadata\ntitle=%s\n-metadata\ntrack=%s\n-metadata\nartist=%s\n-metadata\nalbum_artist=%s\n-metadata\nalbum=%s\n-metadata\ngenre=%s\n-metadata\ndate=%s\n-metadata\ncomposer=Test Composer\n-metadata\ndisc=1\n' "$1" "$2" "$3" "$3" "$4" "$5" "$6"
}
run() { ffmpeg -hide_banner -loglevel error -y "$@"; }

A="Test Artist"; B="First Album"; G="Electronic"; Y="2021"
readarray -t t1 < <(tags "Opening" "1/7" "$A" "$B" "$G" "$Y")
readarray -t t2 < <(tags "Second Light" "2/7" "$A" "$B" "$G" "$Y")
readarray -t t3 < <(tags "Third Wave" "3/7" "$A" "$B" "$G" "$Y")
readarray -t t4 < <(tags "Fourth Wall" "4/7" "$A" "$B" "$G" "$Y")
readarray -t t5 < <(tags "Fifth Element" "5/7" "$A" "$B" "$G" "$Y")
readarray -t t6 < <(tags "Sixth Sense" "6/7" "$A" "$B" "$G" "$Y")
readarray -t t7 < <(tags "Seventh Heaven" "7/7" "$A" "$B" "$G" "$Y")
readarray -t o1 < <(tags "Elsewhere" "1/1" "Other Artist" "Second Album" "Jazz" "1999")

# MP3 with embedded cover (ID3v2.3).
run $(tone 440 2) -i "${out}/cover-first.jpg" -map 0:a -map 1:v -c:a libmp3lame -b:a 64k -c:v copy \
  -id3v2_version 3 -metadata:s:v title="Album cover" -metadata:s:v comment="Cover (front)" \
  "${t1[@]}" "${first}/01 Opening.mp3"
# FLAC with embedded cover.
run $(tone 494 2) -i "${out}/cover-first.jpg" -map 0:a -map 1:v -c:a flac -c:v copy -disposition:v attached_pic \
  "${t2[@]}" "${first}/02 Second Light.flac"
# AAC in M4A with embedded cover.
run $(tone 523 2) -i "${out}/cover-first.jpg" -map 0:a -map 1:v -c:a aac -b:a 64k -c:v copy -disposition:v attached_pic \
  "${t3[@]}" "${first}/03 Third Wave.m4a"
# ALAC in M4A.
run $(tone 587 2) -c:a alac "${t4[@]}" "${first}/04 Fourth Wall.m4a"
# WAV (PCM 16-bit, 22.05 kHz mono to keep it small).
run -f lavfi -i "sine=frequency=659:sample_rate=22050:duration=2" -c:a pcm_s16le "${t5[@]}" "${first}/05 Fifth Element.wav"
# Ogg Vorbis and Ogg Opus.
run $(tone 698 2) -c:a libvorbis -q:a 2 "${t6[@]}" "${first}/06 Sixth Sense.ogg"
run -f lavfi -i "sine=frequency=784:sample_rate=48000:duration=2" -c:a libopus -b:a 48k "${t7[@]}" "${first}/07 Seventh Heaven.opus"
# A second album by another artist, with its cover as a folder image instead of embedded art.
run $(tone 330 2) -c:a libmp3lame -b:a 64k -id3v2_version 3 "${o1[@]}" "${second}/01 Elsewhere.mp3"
mv "${out}/cover-second.jpg" "${second}/cover.jpg"
rm -f "${out}/cover-first.jpg"

# Loose files outside any album folder: an AIFF with accented tags (ID3 chunk) and a raw ADTS AAC
# stream that carries no tags at all, so the library has to fall back to the file name.
run -f lavfi -i "sine=frequency=880:sample_rate=22050:duration=2" -c:a pcm_s16be -write_id3v2 1 -metadata title="Canção Nona" -metadata artist="João Só" \
  -metadata album="Singles" -metadata genre="Fado" -metadata date="2010" "${loose}/Cancao Nona.aiff"
run $(tone 988 2) -c:a aac -b:a 64k -f adts "${loose}/12 - Untagged Tone.aac"

# Not audio: must be ignored by the scanner.
printf 'not audio\n' > "${first}/notes.txt"
# Ogg files get a random stream serial on every run; regenerating them is not a real change.
find "${out}" -type f | sort
du -sh "${out}"
