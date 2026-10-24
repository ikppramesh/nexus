# Nexus

A private voice assistant for Android that talks back **in your own cloned voice**, all on the phone.
Inspired by Block's [Dessa](https://block.xyz/dessa) (natural spoken conversation) and
[Wispr Flow](https://wisprflow.ai/) (dictate anywhere). Built for the Samsung Galaxy Z Fold7.

| | What it does |
|---|---|
| **Talk** | Hands-free conversation. Interrupt it any time by talking over it. Replies stream sentence by sentence in your voice. |
| **Read with my voice** | Select text in any app, then ⋮ › *Read with my voice*, or share text to Nexus. |
| **System voice** | Registers as an Android text-to-speech engine, so Samsung Read aloud, Select to Speak, TalkBack and browsers can all use your voice. |
| **Dictation keyboard** | Wispr Flow-style: tap the mic, talk naturally, tap again. It transcribes on the phone, cleans up filler words and self-corrections, and types into any app. |
| **Assistant** | Can be set as the digital-assistant app (side key), or opened with your phone's wake word ("Ramesh, open Nexus"). |

Everything runs offline on the phone: no account, and no audio or text leaves the device.
Claude can optionally be used as the AI brain instead, with your own API key.

## How it works

```
mic ─► Silero VAD ─► Parakeet TDT 0.6B (speech → text)
                         │
                         ▼
              IRx-mini (Gemma 3 1B, llama.cpp)  or  Claude API
                         │  streamed text, split into sentences
                         ▼
     Chatterbox Turbo q4: language model ─► decoder ─► speaker
              (sentence N+1 is generated while sentence N is decoded)
```

| Part | Model | Runs on |
|---|---|---|
| Your voice | [Chatterbox Turbo](https://huggingface.co/ResembleAI/chatterbox-turbo-ONNX) q4 ONNX, ONNX Runtime | CPU |
| Speech recognition | NVIDIA Parakeet TDT 0.6B v2 int8, [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | CPU |
| Voice detection | Silero VAD v5 | CPU |
| AI | [IRx-mini](https://huggingface.co/ikppramesh/irx-mini-GGUF) Q8_0, [llama.cpp](https://github.com/ggml-org/llama.cpp) via JNI | CPU |

**Choosing the voice model.** Candidates were scored by how closely they match real recordings, using
two speaker-verification models (WeSpeaker ResNet34 and NeMo TitaNet; mean cosine similarity):

| Voice | Similarity |
|---|---|
| Pocket TTS (Kyutai, 100M) | 0.42 |
| Chatterbox (500M) on a Mac | 0.66 |
| **Chatterbox Turbo q4, 10 s reference (Nexus)** | **0.70** |

The voice is conditioned once on a computer (`tools/make_voice.py`), so the phone only runs the
text-to-speech parts. The voice prefix's KV cache is also computed once per launch.

## Setup

Requirements: macOS or Linux with the Android SDK (platform 37, NDK 28.2, CMake 3.22.1), JDK 17,
`ffmpeg` and [`uv`](https://github.com/astral-sh/uv); an arm64 Android 12+ phone with about 3 GB free.

```bash
git clone --recursive https://github.com/ikppramesh/nexus && cd nexus

# Your voice: 10–30 s of clean, natural speech (stays local, never committed).
cp ~/my-voice.wav reference.wav

# Phone: same Wi-Fi, Developer options › Wireless debugging, then pair once:
adb pair <ip:pairing-port> && adb connect <ip:port>

scripts/setup.sh    # downloads about 2 GB of models, builds, installs, copies models to the phone
```

Then open **Nexus › Setup** on the phone:

1. Allow the microphone.
2. **AI brain:** IRx-mini (on the phone, the default) or Claude (paste an API key from console.anthropic.com).
3. **Read everything in my voice:** in text-to-speech settings, choose *Nexus (Ramesh's voice)*.
4. **Dictation keyboard:** enable *Nexus Dictation*, then switch to it from any text box.
5. **Assistant:** optionally make Nexus the default digital assistant app.

Other commands:

```bash
scripts/setup.sh --voice other.wav 5   # remake the voice from another recording, starting 5 s in
scripts/setup.sh --models              # copy the models to the phone again
```

## Privacy

- The voice recording and the voice file made from it (`app/src/main/assets/voice/`) are git-ignored.
  Anyone with them could clone the voice, so keep them out of public places.
- A Claude API key, if you use one, is stored only in the app's private storage on the phone.
- Clone only your own voice, or a voice you have explicit permission to use.

## Project layout

```
app/src/main/java/com/ramesh/nexus/
  Conversation.kt     listen → think → speak loop, barge-in, echo guard
  Engines.kt          model loading, two-stage sentence pipeline
  ChatterboxTts.kt    Chatterbox Turbo on ONNX Runtime (cached voice prefix, greedy + repetition penalty)
  Gpt2Tokenizer.kt    byte-level BPE matching Chatterbox's tokenizer.json
  LocalLlm.kt         IRx-mini through llama.cpp (app/src/main/cpp/nexus_llm.cpp)
  Brain.kt            conversation memory, dictation cleanup, local/Claude routing
  NexusTtsService.kt  Android text-to-speech engine
  DictationIme.kt     voice keyboard
  ReadActivity.kt / ReadService.kt   "Read with my voice"
tools/make_voice.py   recording → voice conditioning file
scripts/setup.sh      download, build, install, push models
```

## Licenses

Models and libraries keep their own licenses: Chatterbox Turbo (MIT), Parakeet TDT
(CC-BY-4.0), Silero VAD (MIT), IRx-mini (Apache-2.0); also sherpa-onnx (Apache-2.0),
llama.cpp (MIT) and ONNX Runtime (MIT).
