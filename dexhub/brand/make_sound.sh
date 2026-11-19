#!/usr/bin/env bash
# Dex Hub TV's entrance sound, in place of Arc's (app/src/full/res/raw/arc_intro_sound.m4a).
# A swell of air for the mark being drawn in, then a soft C major chime at 2.25 s, where
# DexHubIntroScene's sheen crosses the mark. Made from nothing but ffmpeg's own sources.
set -euo pipefail
out="$(dirname "$0")/../../app/src/full/res/raw/arc_intro_sound.m4a"
ffmpeg -v error -y -filter_complex "
anoisesrc=color=pink:amplitude=0.5:duration=3.2:sample_rate=48000,highpass=f=400,lowpass=f=5200,volume=0.32,afade=t=in:st=0:d=2.1:curve=qsin,afade=t=out:st=2.15:d=0.9[air];
sine=f=523.25:d=3.4:sample_rate=48000,volume=0.20[c];sine=f=659.25:d=3.4:sample_rate=48000,volume=0.16[e];sine=f=783.99:d=3.4:sample_rate=48000,volume=0.13[g];sine=f=1046.5:d=3.4:sample_rate=48000,volume=0.07[c2];
[c][e][g][c2]amix=inputs=4:normalize=0,afade=t=in:st=0:d=0.012,afade=t=out:st=0.05:d=3.3:curve=exp,adelay=2250|2250,aecho=0.8:0.6:120|260:0.25|0.15[chime];
[air][chime]amix=inputs=2:normalize=0:duration=longest,pan=stereo|c0=c0|c1=c0,alimiter=limit=0.8,afade=t=out:st=5.0:d=0.6[out]" \
  -map "[out]" -t 5.6 -c:a aac -b:a 128k "$out"
echo "$out"
