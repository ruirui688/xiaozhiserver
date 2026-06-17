import struct

FRAME_DURATION_MS = 60


def _read_next_frame(stream):
    header = stream.read(4)
    if not header:
        return None
    _, _, data_len = struct.unpack('>BBH', header)
    opus_data = stream.read(data_len)
    if len(opus_data) != data_len:
        raise ValueError(f"Data length({len(opus_data)}) mismatch({data_len}) in the p3 stream.")
    return opus_data


def decode_opus_from_file(input_file):
    """
    从p3文件中解码 Opus 数据，并返回一个 Opus 数据包的列表以及总时长。
    """
    opus_datas = []
    total_frames = 0

    with open(input_file, 'rb') as f:
        while True:
            opus_data = _read_next_frame(f)
            if opus_data is None:
                break
            opus_datas.append(opus_data)
            total_frames += 1

    # 计算总时长
    total_duration = (total_frames * FRAME_DURATION_MS) / 1000.0
    return opus_datas, total_duration


def decode_opus_from_file_stream(input_file, callback):
    """
    从p3文件中流式读取 Opus 数据包。
    """
    with open(input_file, 'rb') as f:
        while True:
            opus_data = _read_next_frame(f)
            if opus_data is None:
                break
            callback(opus_data)


def decode_opus_from_bytes(input_bytes):
    """
    从p3二进制数据中解码 Opus 数据，并返回一个 Opus 数据包的列表以及总时长。
    """
    import io
    opus_datas = []
    total_frames = 0

    f = io.BytesIO(input_bytes)
    while True:
        opus_data = _read_next_frame(f)
        if opus_data is None:
            break
        opus_datas.append(opus_data)
        total_frames += 1

    total_duration = (total_frames * FRAME_DURATION_MS) / 1000.0
    return opus_datas, total_duration


def decode_opus_from_bytes_stream(input_bytes, callback):
    """
    从p3二进制数据中流式读取 Opus 数据包。
    """
    import io

    f = io.BytesIO(input_bytes)
    while True:
        opus_data = _read_next_frame(f)
        if opus_data is None:
            break
        callback(opus_data)
