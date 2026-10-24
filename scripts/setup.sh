#!/bin/zsh
# Build Nexus, install it on the phone over wireless adb, and copy the on-device voice models.
#
#   scripts/setup.sh              # everything
#   scripts/setup.sh --models     # only (re)push models
#   scripts/setup.sh --voice f.wav [start-seconds]   # make your voice from another recording
#
# Phone: same Wi-Fi, Developer options › Wireless debugging on, paired once with `adb pair`.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=com.ramesh.nexus
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download
REMOTE=/sdcard/Android/data/$PKG/files

fetch() {  # fetch <url> <dest-dir>; extracts .tar.bz2
  local url=$1 dest=$2 name=${1:t}
  mkdir -p $dest
  echo "↓ $name"
  curl -fSL --progress-bar -o "$dest/$name" "$url"
  if [[ $name == *.tar.bz2 ]]; then tar xjf "$dest/$name" -C $dest && rm "$dest/$name"; fi
}

CB=https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX/resolve/main
CB_FILES=(tokenizer.json onnx/embed_tokens_q4.onnx onnx/embed_tokens_q4.onnx_data
          onnx/language_model_q4.onnx onnx/language_model_q4.onnx_data
          onnx/conditional_decoder_q4.onnx onnx/conditional_decoder_q4.onnx_data
          onnx/speech_encoder_q4.onnx onnx/speech_encoder_q4.onnx_data)

download() {
  [[ -f app/libs/sherpa-onnx-static-2.0.0.aar ]] ||
    { mkdir -p app/libs; curl -fSL --progress-bar -o app/libs/sherpa-onnx-static-2.0.0.aar \
        $REL/v2.0.0/sherpa-onnx-static-link-onnxruntime-2.0.0.aar; }
  for f in $CB_FILES; do
    [[ -f models/chatterbox-turbo/$f ]] || fetch $CB/$f models/chatterbox-turbo/${f:h}
  done
  [[ -d models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8 ]] ||
    fetch $REL/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2 models
  [[ -f models/silero_vad_v5.onnx ]] || fetch $REL/asr-models/silero_vad_v5.onnx models
  [[ -f models/irx-mini-Q8_0.gguf ]] ||
    fetch https://huggingface.co/ikppramesh/irx-mini-GGUF/resolve/main/irx-mini-Q8_0.gguf models
  [[ -f third_party/llama.cpp/CMakeLists.txt ]] || git submodule update --init --depth 1
}

device() {
  if ! adb get-state >/dev/null 2>&1; then
    echo "No phone connected. On the phone: Settings › Developer options › Wireless debugging."
    echo "Then run:  adb pair <ip:pair-port>   and   adb connect <ip:port>"
    exit 1
  fi
  echo "📱 $(adb shell getprop ro.product.model)"
}

voice() {
  # Turns a recording of your voice into app/src/main/assets/voice/voice.cond (personal: not in git).
  # Default: 10 s from 2 s into your original recording, the stretch that matched your voice best.
  local src=${1:-} start=${2:-2}
  if [[ -z $src ]]; then
    for c in voices/Ramesh/samples/*.wav reference.wav; do [[ -f $c ]] && src=$c && break; done
  fi
  [[ -n $src ]] || { echo "Put a recording of your voice at ./reference.wav (10–30 s, clean speech)"; exit 1; }
  download
  [[ -x .venv/bin/python ]] || { uv venv -q .venv && uv pip install -q --python .venv/bin/python onnxruntime soundfile numpy; }
  .venv/bin/python tools/make_voice.py "$src" --start $start --seconds 10
}

push_models() {
  adb shell mkdir -p $REMOTE/models
  # Skip files already on the phone with the same size.
  adb shell rm -rf $REMOTE/models/pocket   # replaced by Chatterbox
  mkdir -p models/.phone-chatterbox
  for f in $CB_FILES; do [[ $f == *speech_encoder* ]] || ln -sf ../chatterbox-turbo/$f models/.phone-chatterbox/${f:t}; done
  for pair in "models/.phone-chatterbox:chatterbox" \
              "models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8:parakeet"; do
    local src=${pair%%:*} dst=$REMOTE/models/${pair##*:}
    adb shell mkdir -p $dst
    for f in $src/*(-.); do
      local size=$(stat -L -f%z $f) remote=$(adb shell stat -c%s "$dst/${f:t}" 2>/dev/null | tr -d '\r' || true)
      [[ $size == $remote ]] && continue
      echo "→ ${pair##*:}/${f:t} ($((size / 1048576)) MB)"
      adb push -q ${f:A} $dst/${f:t}
    done
  done
  adb push -q models/silero_vad_v5.onnx $REMOTE/models/
  local gguf=models/irx-mini-Q8_0.gguf
  if [[ $(stat -f%z $gguf) != $(adb shell stat -c%s $REMOTE/models/${gguf:t} 2>/dev/null | tr -d '\r' || true) ]]; then
    echo "→ IRx-mini ($(( $(stat -f%z $gguf) / 1048576 )) MB)"
    adb push -q $gguf $REMOTE/models/
  fi
  echo "✓ models on phone"
}

case ${1:-} in
  --models) device; download; push_models; exit ;;
  --voice) voice "$2" "${3:-0}" ;;
  *) [[ -f app/src/main/assets/voice/voice.cond ]] || voice ;;
esac

download
device
./gradlew -q assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n $PKG/.MainActivity >/dev/null   # creates the app's storage folder
sleep 2
push_models
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity >/dev/null
echo "✓ Nexus is ready. Finish the steps in the app's Setup tab."
