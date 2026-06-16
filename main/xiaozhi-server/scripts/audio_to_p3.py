#!/usr/bin/env python3
import argparse
import os
import struct
import tempfile

import numpy as np
import opuslib_next
from opuslib_next import constants
from pydub import AudioSegment


def encode_audio_to_p3(input_file, output_file, sample_rate, bitrate, complexity):
    audio = AudioSegment.from_file(input_file, parameters=["-nostdin"])
    audio = audio.set_channels(1).set_frame_rate(sample_rate).set_sample_width(2)
    raw_data = audio.raw_data

    encoder = opuslib_next.Encoder(sample_rate, 1, constants.APPLICATION_AUDIO)
    encoder.bitrate = bitrate
    encoder.complexity = complexity
    encoder.signal = constants.SIGNAL_MUSIC

    frame_duration_ms = 60
    frame_size = int(sample_rate * frame_duration_ms / 1000)
    frame_bytes = frame_size * 2

    output_dir = os.path.dirname(os.path.abspath(output_file))
    os.makedirs(output_dir, exist_ok=True)

    fd, temp_path = tempfile.mkstemp(prefix=".p3-", suffix=".tmp", dir=output_dir)
    try:
        with os.fdopen(fd, "wb") as out:
            for offset in range(0, len(raw_data), frame_bytes):
                chunk = raw_data[offset : offset + frame_bytes]
                if len(chunk) < frame_bytes:
                    chunk += b"\x00" * (frame_bytes - len(chunk))
                frame = np.frombuffer(chunk, dtype=np.int16)
                opus_data = encoder.encode(frame.tobytes(), frame_size)
                if len(opus_data) > 65535:
                    raise ValueError("encoded opus frame is too large for p3")
                out.write(struct.pack(">BBH", 1, 0, len(opus_data)))
                out.write(opus_data)
        os.replace(temp_path, output_file)
    except Exception:
        try:
            os.remove(temp_path)
        except OSError:
            pass
        raise


def main():
    parser = argparse.ArgumentParser(description="Convert audio to xiaozhi p3 opus cache")
    parser.add_argument("input_file")
    parser.add_argument("output_file")
    parser.add_argument("--sample-rate", type=int, default=24000)
    parser.add_argument("--bitrate", type=int, default=48000)
    parser.add_argument("--complexity", type=int, default=5)
    args = parser.parse_args()

    encode_audio_to_p3(
        args.input_file,
        args.output_file,
        args.sample_rate,
        args.bitrate,
        args.complexity,
    )


if __name__ == "__main__":
    main()
