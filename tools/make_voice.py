"""Turn a recording of your voice into the conditioning file Nexus speaks with.

Runs Chatterbox Turbo's speech encoder once, on the Mac, so the phone never needs it:
    python tools/make_voice.py voices/Ramesh/samples/sample.wav --start 2 --seconds 10

Writes app/src/main/assets/voice/voice.cond. Needs models/chatterbox-turbo (scripts/setup.sh).
A clean ~10 s stretch of natural speech works best (measured: closest speaker match).
"""
import argparse, struct, subprocess, tempfile
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
ENCODER = ROOT / "models/chatterbox-turbo/onnx/speech_encoder_q4.onnx"
OUT = ROOT / "app/src/main/assets/voice/voice.cond"


def load(path, start, seconds):
    with tempfile.NamedTemporaryFile(suffix=".wav") as tmp:
        cmd = ["ffmpeg", "-loglevel", "error", "-y", "-ss", str(start), "-i", str(path)]
        if seconds:
            cmd += ["-t", str(seconds)]
        cmd += ["-ac", "1", "-ar", "24000", "-af", "highpass=f=70,loudnorm", tmp.name]
        subprocess.run(cmd, check=True)
        audio, _ = sf.read(tmp.name, dtype="float32")
    return audio[None, :]


def write(tensors, path):
    """NXC1 | count | per tensor: name, dtype (0=f32, 1=i64), dims, little-endian data."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"NXC1" + struct.pack("<i", len(tensors)))
        for name, arr in tensors.items():
            arr = np.ascontiguousarray(arr)
            dtype = {np.float32: 0, np.int64: 1}[arr.dtype.type]
            f.write(struct.pack("<i", len(name)) + name.encode())
            f.write(struct.pack("<ii", dtype, arr.ndim) + struct.pack(f"<{arr.ndim}q", *arr.shape))
            f.write(arr.astype(arr.dtype.newbyteorder("<")).tobytes())


def main():
    p = argparse.ArgumentParser()
    p.add_argument("audio")
    p.add_argument("--start", type=float, default=0)
    p.add_argument("--seconds", type=float, default=10)
    args = p.parse_args()

    enc = ort.InferenceSession(str(ENCODER), providers=["CPUExecutionProvider"])
    cond_emb, prompt_tokens, spk_emb, spk_feat = enc.run(None, {"audio_values": load(args.audio, args.start, args.seconds)})
    write({
        "cond_emb": cond_emb.astype(np.float32),
        "prompt_tokens": prompt_tokens.astype(np.int64),
        "speaker_embeddings": spk_emb.astype(np.float32),
        "speaker_features": spk_feat.astype(np.float32),
    }, OUT)
    print(f"{OUT.relative_to(ROOT)}: cond {cond_emb.shape}, prompt {prompt_tokens.shape}, "
          f"speaker {spk_emb.shape}, features {spk_feat.shape}")


if __name__ == "__main__":
    main()
