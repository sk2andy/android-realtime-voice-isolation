import argparse
import hashlib
import io
import math
from pathlib import Path
import urllib.request
import wave

import numpy as np
import onnxruntime as ort

SAMPLE_RATE = 16_000
FFT_SIZE = 512
HOP_SIZE = 256
SPEECH_URL = "https://raw.githubusercontent.com/Xiaobin-Rong/gtcrn/3862c44808dca492ea5a8a145d2dc2a1028d08c8/stream/test_wavs/mix.wav"
SPEECH_SHA256 = "8d47e1d03eeb457c2549be79c8ec33a349ccd79f21c3add05f946f8f760c5a99"


class StreamingGtcrn:
    def __init__(self, model_path):
        self.session = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
        self.analysis = np.zeros(FFT_SIZE, dtype=np.float32)
        self.overlap = np.zeros(FFT_SIZE, dtype=np.float32)
        self.window = np.sqrt(
            0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(FFT_SIZE) / FFT_SIZE)
        ).astype(np.float32)
        self.conv = np.zeros((2, 1, 16, 16, 33), dtype=np.float32)
        self.tra = np.zeros((2, 3, 1, 1, 16), dtype=np.float32)
        self.inter = np.zeros((2, 1, 33, 16), dtype=np.float32)

    def process(self, signal):
        padded = np.pad(signal.astype(np.float32), (0, (-len(signal)) % HOP_SIZE))
        output = np.zeros_like(padded)
        for offset in range(0, len(padded), HOP_SIZE):
            self.analysis[:-HOP_SIZE] = self.analysis[HOP_SIZE:]
            self.analysis[-HOP_SIZE:] = padded[offset:offset + HOP_SIZE]
            spectrum = np.fft.rfft(self.analysis * self.window)
            model_input = np.stack((spectrum.real, spectrum.imag), axis=-1)
            model_input = model_input[None, :, None, :].astype(np.float32)
            enh, self.conv, self.tra, self.inter = self.session.run(
                None,
                {
                    "mix": model_input,
                    "conv_cache": self.conv,
                    "tra_cache": self.tra,
                    "inter_cache": self.inter,
                },
            )
            enhanced = enh[0, :, 0, 0] + 1j * enh[0, :, 0, 1]
            frame = np.fft.irfft(enhanced, n=FFT_SIZE).astype(np.float32)
            self.overlap += frame * self.window
            output[offset:offset + HOP_SIZE] = self.overlap[:HOP_SIZE]
            self.overlap[:-HOP_SIZE] = self.overlap[HOP_SIZE:]
            self.overlap[-HOP_SIZE:] = 0
        return output[:len(signal)]


def load_pcm(path):
    if path:
        source_data = Path(path).read_bytes()
    else:
        with urllib.request.urlopen(SPEECH_URL) as response:
            source_data = response.read()
        actual_hash = hashlib.sha256(source_data).hexdigest()
        if actual_hash != SPEECH_SHA256:
            raise ValueError(f"Speech fixture hash mismatch: {actual_hash}")
    with wave.open(io.BytesIO(source_data), "rb") as source:
        assert source.getframerate() == SAMPLE_RATE
        assert source.getnchannels() == 1
        return np.frombuffer(source.readframes(source.getnframes()), dtype="<i2").astype(np.float32) / 32768.0


def make_music(length):
    time = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    chords = ((220.0, 277.18, 329.63), (196.0, 246.94, 293.66), (261.63, 329.63, 392.0))
    music = np.zeros(length, dtype=np.float64)
    chord_samples = SAMPLE_RATE // 2
    for start in range(0, length, chord_samples):
        chord = chords[(start // chord_samples) % len(chords)]
        stop = min(start + chord_samples, length)
        local = time[start:stop] - time[start]
        envelope = np.minimum(local * 20.0, 1.0) * np.exp(-0.35 * local)
        for fundamental in chord:
            for harmonic, weight in ((1, 1.0), (2, 0.35), (3, 0.18)):
                music[start:stop] += weight * envelope * np.sin(2.0 * np.pi * fundamental * harmonic * local)
    # Add deterministic kick and hi-hat transients so this is not merely stationary tones.
    rng = np.random.default_rng(4101)
    for beat in range(0, length, SAMPLE_RATE // 4):
        stop = min(beat + SAMPLE_RATE // 12, length)
        local = np.arange(stop - beat) / SAMPLE_RATE
        music[beat:stop] += 1.4 * np.exp(-35.0 * local) * np.sin(2.0 * np.pi * 70.0 * local)
        music[beat:stop] += 0.18 * np.exp(-80.0 * local) * rng.standard_normal(stop - beat)
    return music.astype(np.float32)


def rms(signal):
    return float(np.sqrt(np.mean(np.square(signal, dtype=np.float64)) + 1e-12))


def si_sdr(estimate, target):
    estimate = estimate.astype(np.float64)
    target = target.astype(np.float64)
    scale = np.dot(estimate, target) / (np.dot(target, target) + 1e-12)
    wanted = scale * target
    residual = estimate - wanted
    return 10.0 * math.log10((np.dot(wanted, wanted) + 1e-12) / (np.dot(residual, residual) + 1e-12))


parser = argparse.ArgumentParser(description="Measure GTCRN speech-over-music selectivity")
parser.add_argument("--speech", help="optional 16 kHz mono PCM16 WAV; defaults to pinned GTCRN fixture")
parser.add_argument(
    "--model",
    default=str(Path(__file__).resolve().parents[1] / "app/src/main/assets/gtcrn.onnx"),
)
args = parser.parse_args()

speech = load_pcm(args.speech)
speech = speech[: SAMPLE_RATE * 8]
music = make_music(len(speech))
music *= rms(speech) * 10 ** (-3.0 / 20.0) / rms(music)
mixture = np.clip(speech + music, -0.98, 0.98)

model = args.model
speech_out = StreamingGtcrn(model).process(speech)
music_out = StreamingGtcrn(model).process(music)
mix_out = StreamingGtcrn(model).process(mixture)

warmup = SAMPLE_RATE
speech = speech[warmup:]
music = music[warmup:]
mixture = mixture[warmup:]
speech_out = speech_out[warmup:]
music_out = music_out[warmup:]
mix_out = mix_out[warmup:]

input_sdr = si_sdr(mixture, speech)
output_sdr = si_sdr(mix_out, speech_out)
speech_gain = 20.0 * math.log10(rms(speech_out) / rms(speech))
music_gain = 20.0 * math.log10(rms(music_out) / rms(music))

print(f"input_si_sdr_db={input_sdr:.2f}")
print(f"output_si_sdr_db={output_sdr:.2f}")
print(f"si_sdr_improvement_db={output_sdr - input_sdr:.2f}")
print(f"speech_gain_db={speech_gain:.2f}")
print(f"music_gain_db={music_gain:.2f}")
print(f"speech_over_music_gain_db={speech_gain - music_gain:.2f}")

if output_sdr - input_sdr < 2.0:
    raise SystemExit("validation failed: SI-SDR improvement below 2 dB")
if speech_gain - music_gain < 6.0:
    raise SystemExit("validation failed: speech/music selectivity below 6 dB")
