#!/usr/bin/env bash
# Japanese / English test speech for the English transcription check (#170, SpeechTests): macOS's
# own text-to-speech (`say`) with a Japanese and an English voice, joined with pauses and converted
# to the watch's chunk format (AAC-LC in .m4a, 16 kHz, mono, 24 kbps). Nothing is downloaded.
#
# Writes to <out>:
#   mixed.m4a     Japanese → English → Japanese (one sentence each, 1.5 s pauses)
#   japanese.m4a  the two Japanese sentences only (same pause)
#   english.m4a   the English sentence only
#   mixed.txt     one line per sentence of mixed.m4a: "<lang><TAB><start ms><TAB><end ms><TAB><text>"
# Without both voices it only warns: the tests then skip and say why.
#
# Usage: make-mixed-speech.sh [out dir]   (default build/mixed-speech)
set -euo pipefail

out=${1:-build/mixed-speech}
ja1="おはようございます。今日は午後から会議があるので、資料を確認しておきます。"
en1="Thank you for joining the meeting today. Let's review the schedule for next week and decide who will present the results."
ja2="それでは、三時に駅前で待ち合わせをしましょう。"
mkdir -p "$out"

# Lines look like: "Kyoko               ja_JP    # こんにちは、私の名前はKyokoです。"
pick_voice() { # <locale> <preferred voices...>
  local locale=$1
  shift
  local voices
  voices=$(say -v '?' | grep -E "[[:space:]]$locale[[:space:]]" || true)
  echo "$locale voices: $(echo "$voices" | awk '{print $1}' | tr '\n' ' ')" >&2
  for preferred in "$@"; do
    if echo "$voices" | grep -qE "^$preferred[[:space:]]"; then
      echo "$preferred"
      return
    fi
  done
  [ -n "$voices" ] && echo "$voices" | head -n 1 | sed -E "s/[[:space:]]+$locale.*\$//"
}
ja_voice=$(pick_voice ja_JP Kyoko Otoya O-ren Hattori || true)
en_voice=$(pick_voice en_US Samantha Alex Allison Ava Tom Fred || true)
if [ -z "$ja_voice" ] || [ -z "$en_voice" ]; then
  echo "::warning::no Japanese or English voice for say (ja: '${ja_voice}', en: '${en_voice}'); the English transcription check will be skipped"
  exit 0
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
say -v "$ja_voice" --file-format=WAVE --data-format=LEI16@16000 -o "$work/ja1.wav" "$ja1"
say -v "$en_voice" --file-format=WAVE --data-format=LEI16@16000 -o "$work/en1.wav" "$en1"
say -v "$ja_voice" --file-format=WAVE --data-format=LEI16@16000 -o "$work/ja2.wav" "$ja2"

# Joins the WAV files with 1.5 s of silence between them (and 0.5 s around) and records where each
# sentence lies.
python3 - "$work" "$out/mixed.txt" "$ja1" "$en1" "$ja2" <<'EOF'
import sys, wave

work, listing, ja1, en1, ja2 = sys.argv[1:]
RATE, LEAD, PAUSE = 16000, 0.5, 1.5

def frames(name):
    with wave.open(f"{work}/{name}.wav") as w:
        assert w.getframerate() == RATE and w.getnchannels() == 1 and w.getsampwidth() == 2, name
        return w.readframes(w.getnframes())

def join(target, names, texts=None):
    silence = lambda seconds: b"\0\0" * int(RATE * seconds)
    data, lines = silence(LEAD), []
    for i, name in enumerate(names):
        if i:
            data += silence(PAUSE)
        start = len(data) // 2 * 1000 // RATE
        data += frames(name)
        end = len(data) // 2 * 1000 // RATE
        if texts:
            lines.append(f"{name[:2]}\t{start}\t{end}\t{texts[i]}")
    data += silence(LEAD)
    with wave.open(f"{work}/{target}.wav", "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data)
    return lines

lines = join("mixed", ["ja1", "en1", "ja2"], [ja1, en1, ja2])
join("japanese", ["ja1", "ja2"])
join("english", ["en1"])
with open(listing, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
EOF

for name in mixed japanese english; do
  afconvert -f m4af -d aac@16000 -c 1 -b 24000 "$work/$name.wav" "$out/$name.m4a"
done
cat "$out/mixed.txt"
afinfo "$out/mixed.m4a" | grep -E "duration|Data format" || true
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "- Japanese/English test speech for #170: voices **$ja_voice** / **$en_voice** (ja → en → ja, AAC 16 kHz mono)" >> "$GITHUB_STEP_SUMMARY"
fi
