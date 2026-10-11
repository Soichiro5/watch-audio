#!/usr/bin/env bash
# Japanese test speech for the simulator transcription check (#21, SpeechTests): macOS's own
# text-to-speech (`say`) with a Japanese voice, converted to the watch's chunk format
# (AAC-LC in .m4a, 16 kHz, mono, 24 kbps). Writes <out>/speech.m4a and <out>/expected.txt.
# Nothing is downloaded. Without a Japanese voice it only warns: the test then skips and says so.
#
# Usage: make-ja-speech.sh [out dir]   (default build/ja-speech)
set -euo pipefail

out=${1:-build/ja-speech}
text="おはようございます。今日は午後から会議があるので、資料を確認してから駅前で待ち合わせをしましょう。"
mkdir -p "$out"

# Lines look like: "Kyoko               ja_JP    # こんにちは、私の名前はKyokoです。"
voices=$(say -v '?' | grep -E '[[:space:]]ja_JP[[:space:]]' || true)
echo "Japanese voices:"
echo "${voices:-  (none)}"
voice=""
for preferred in Kyoko Otoya O-ren Hattori; do
  if echo "$voices" | grep -qE "^$preferred[[:space:]]"; then
    voice=$preferred
    break
  fi
done
if [ -z "$voice" ] && [ -n "$voices" ]; then
  voice=$(echo "$voices" | head -n 1 | sed -E 's/[[:space:]]+ja_JP.*$//')
fi
if [ -z "$voice" ]; then
  echo "::warning::no Japanese voice for say; the speech transcription check will be skipped"
  exit 0
fi

say -v "$voice" -o "$out/speech.aiff" "$text"
afconvert -f m4af -d aac@16000 -c 1 -b 24000 "$out/speech.aiff" "$out/speech.m4a"
rm -f "$out/speech.aiff"
printf '%s\n' "$text" > "$out/expected.txt"
afinfo "$out/speech.m4a"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  duration=$(afinfo "$out/speech.m4a" | awk '/estimated duration/ {print $3}')
  echo "- Japanese test speech for #21: voice **$voice**, ${duration:-?} s, AAC 16 kHz mono (\`$text\`)" >> "$GITHUB_STEP_SUMMARY"
fi
