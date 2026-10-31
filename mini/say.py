#!/usr/bin/env python3
"""Renders one announcement to a WAV with Kokoro, the same engine Jarvis uses.

Run with the Jarvis venv's Python (it has kokoro_onnx and soundfile):
  say.py "<text>" <voice> <out.wav>
Prints the duration in seconds. The models live beside Jarvis in ~/Documents/moonkiln/jarvis/models.
"""
import os
import sys

JARVIS = os.path.expanduser(os.environ.get("MK_JARVIS_DIR", "~/Documents/moonkiln/jarvis"))
MODEL = os.path.join(JARVIS, "models", "kokoro-v1.0.onnx")
VOICES = os.path.join(JARVIS, "models", "voices-v1.0.bin")


def main() -> None:
    text, voice, out = sys.argv[1], sys.argv[2], sys.argv[3]
    import soundfile as sf
    from kokoro_onnx import Kokoro
    kokoro = Kokoro(MODEL, VOICES)
    samples, sr = kokoro.create(text, voice=voice, speed=0.95)
    sf.write(out, samples, sr)
    print(f"{len(samples) / float(sr):.2f}")


if __name__ == "__main__":
    main()
