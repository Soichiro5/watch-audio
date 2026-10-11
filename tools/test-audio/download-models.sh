#!/usr/bin/env bash
# Sets up the offline TTS used to synthesize the test conversations (issue #56):
# a Python venv plus model files in tools/test-audio/cache/models/. Only downloads:
# synthesis runs locally afterwards and nothing is ever uploaded.
#
#   Kokoro-82M v1.0 (Apache-2.0), ONNX fp16 export by thewh1teagle/kokoro-onnx (MIT)
#   HTS voice NIT ATR503 M001 (CC BY 3.0, Nagoya Institute of Technology)
#   Open JTalk dictionary (naist-jdic, BSD), fetched by pyopenjtalk on first use
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv
fi
.venv/bin/pip install -q -r requirements.txt

DIR=cache/models
mkdir -p "$DIR/kokoro" "$DIR/hts"
KOKORO=https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0
for f in kokoro-v1.0.fp16.onnx voices-v1.0.bin; do
  if [ ! -f "$DIR/kokoro/$f" ]; then
    curl -fL --retry 4 -o "$DIR/kokoro/$f.part" "$KOKORO/$f"
    mv -f "$DIR/kokoro/$f.part" "$DIR/kokoro/$f"
  fi
done

M001=hts_voice_nitech_jp_atr503_m001-1.05
if [ ! -f "$DIR/hts/nitech_jp_atr503_m001.htsvoice" ]; then
  curl -fL --retry 4 "https://downloads.sourceforge.net/project/open-jtalk/HTS%20voice/$M001/$M001.tar.gz" \
    | tar xz -C "$DIR/hts" --strip-components=1 "$M001/nitech_jp_atr503_m001.htsvoice" "$M001/COPYING"
fi

# pyopenjtalk downloads its dictionary into the venv the first time it is used; do it now so
# that generate.py (which disables networking) never needs to.
.venv/bin/python -c "import pyopenjtalk; pyopenjtalk.g2p('テスト')"
ls -la "$DIR/kokoro" "$DIR/hts"
