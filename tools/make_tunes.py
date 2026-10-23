"""Synthesizes Calm's five alarm tunes as mono 16-bit WAVs (original, royalty-free).

Usage: python -I make_tunes.py <out_dir>
"""
import math
import random
import struct
import sys
import wave

RATE = 22050


def note(name):
    """'C5' -> frequency in Hz."""
    names = {'C': -9, 'D': -7, 'E': -5, 'F': -4, 'G': -2, 'A': 0, 'B': 2}
    semis = names[name[0]]
    rest = name[1:]
    if rest.startswith('#'):
        semis += 1
        rest = rest[1:]
    octave = int(rest)
    return 440.0 * 2 ** ((semis + (octave - 4) * 12) / 12)


def blank(seconds):
    return [0.0] * int(seconds * RATE)


def add(buf, start, samples, gain=1.0):
    i0 = int(start * RATE)
    for i, s in enumerate(samples):
        if i0 + i < len(buf):
            buf[i0 + i] += s * gain


def tone(freq, seconds, partials, decay, attack=0.005):
    """Sum of (ratio, amplitude) partials with an exponential decay envelope."""
    n = int(seconds * RATE)
    out = []
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / attack) * math.exp(-t * decay)
        out.append(env * sum(a * math.sin(2 * math.pi * freq * r * t) for r, a in partials))
    return out


def bell(f, sec=2.5):
    return tone(f, sec, [(1, 1.0), (2.0, 0.35), (2.76, 0.25), (5.4, 0.08)], decay=1.8)


def marimba(f, sec=0.9):
    return tone(f, sec, [(1, 1.0), (4.0, 0.25), (9.8, 0.05)], decay=7.0, attack=0.002)


def musicbox(f, sec=1.6):
    return tone(f, sec, [(1, 1.0), (2.0, 0.5), (3.0, 0.2), (4.2, 0.1)], decay=3.2, attack=0.001)


def pad(f, sec):
    n = int(sec * RATE)
    out = []
    for i in range(n):
        t = i / RATE
        env = math.sin(math.pi * min(1.0, t / sec))  # swell up and back down
        s = sum(math.sin(2 * math.pi * f * d * t) for d in (1.0, 1.003, 0.997)) / 3
        s += 0.3 * math.sin(2 * math.pi * f * 2 * t)
        out.append(env * s)
    return out


def chimes():
    buf = blank(16)
    seq = ['C5', 'E5', 'G5', 'C6', 'G5', 'E5', 'D5', 'G5', 'A5', 'G5', 'E5', 'C5']
    for i, n in enumerate(seq):
        add(buf, i * 0.5, bell(note(n)), 0.5)
    for i, n in enumerate(['C5', 'E5', 'G5', 'C6']):
        add(buf, 7.0 + i * 0.25, bell(note(n), 4), 0.4)
    return buf


def sunrise():
    buf = blank(20)
    chords = [['C4', 'E4', 'G4'], ['A3', 'C4', 'E4'], ['F3', 'A3', 'C4'], ['G3', 'B3', 'D4']]
    for i, ch in enumerate(chords):
        for n in ch:
            add(buf, i * 4.5, pad(note(n), 6), 0.22)
    melody = [('E5', 1), ('G5', 2), ('A5', 4.5), ('G5', 6), ('E5', 9), ('C5', 10.5), ('D5', 13.5), ('G5', 15)]
    for n, t in melody:
        add(buf, t, bell(note(n), 3), 0.3)
    return buf


def marimba_tune():
    buf = blank(12)
    pattern = ['G4', 'C5', 'E5', 'G5', 'E5', 'C5', 'A4', 'C5', 'F5', 'A5', 'F5', 'C5',
               'B4', 'D5', 'G5', 'B5', 'G5', 'D5', 'C5', 'E5', 'G5', 'C6']
    for i, n in enumerate(pattern):
        add(buf, i * 0.22, marimba(note(n)), 0.6)
    for i, n in enumerate(pattern):
        add(buf, 6 + i * 0.22, marimba(note(n)), 0.6)
    return buf


def music_box():
    buf = blank(14)
    melody = ['E6', 'D6', 'C6', 'D6', 'E6', 'E6', 'E6', None, 'D6', 'D6', 'E6', 'G6', 'G6', None,
              'E6', 'D6', 'C6', 'D6', 'E6', 'G6', 'E6', 'D6', 'C6']
    for i, n in enumerate(melody):
        if n:
            add(buf, i * 0.42, musicbox(note(n)), 0.45)
            add(buf, i * 0.42, musicbox(note(n) / 2), 0.15)
    return buf


def classic_beep():
    buf = blank(4)
    for group in range(2):
        for b in range(4):
            start = group * 2 + b * 0.18
            n = int(0.1 * RATE)
            add(buf, start, [math.sin(2 * math.pi * 2000 * i / RATE) * (1 if i < n - 200 else (n - i) / 200)
                             for i in range(n)], 0.35)
    return buf


def write(path, buf):
    peak = max(abs(s) for s in buf) or 1.0
    scale = 0.85 / peak
    # Short fade at both ends so looping never clicks.
    fade = int(0.02 * RATE)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        frames = bytearray()
        for i, s in enumerate(buf):
            g = min(1.0, i / fade, (len(buf) - i) / fade)
            frames += struct.pack('<h', int(max(-1, min(1, s * scale * g)) * 32767))
        w.writeframes(bytes(frames))


if __name__ == '__main__':
    out = sys.argv[1]
    random.seed(1)
    for name, fn in [('tune_chimes', chimes), ('tune_sunrise', sunrise), ('tune_marimba', marimba_tune),
                     ('tune_musicbox', music_box), ('tune_beep', classic_beep)]:
        write(f'{out}/{name}.wav', fn())
        print('wrote', name)
