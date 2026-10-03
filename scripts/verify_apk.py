#!/usr/bin/env python3
"""Inspect packaged permissions/ABIs separately via aapt; validate native ELF LOAD alignment here."""
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

apk = Path(sys.argv[1])
rows = []
with zipfile.ZipFile(apk) as archive:
    for name in sorted(archive.namelist()):
        if not name.startswith('lib/') or not name.endswith('.so'):
            continue
        data = archive.read(name)
        assert data[:4] == b'\x7fELF', name
        bits = data[4]
        endian = '<' if data[5] == 1 else '>'
        if bits == 2:
            phoff = struct.unpack_from(endian + 'Q', data, 32)[0]
            size, count = struct.unpack_from(endian + 'HH', data, 54)
            fmt = endian + 'IIQQQQQQ'
        else:
            phoff = struct.unpack_from(endian + 'I', data, 28)[0]
            size, count = struct.unpack_from(endian + 'HH', data, 42)
            fmt = endian + 'IIIIIIII'
        alignments = []
        for idx in range(count):
            header = struct.unpack_from(fmt, data, phoff + idx * size)
            if header[0] == 1:
                alignments.append(header[-1])
        rows.append({'library': name, 'load_alignments': alignments, 'supports_16k_alignment': bool(alignments) and all(a >= 16384 for a in alignments)})
result = {'apk': apk.name, 'bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'native_libraries': rows}
print(json.dumps(result, ensure_ascii=False, indent=2))
if not all(r['supports_16k_alignment'] for r in rows):
    sys.exit(1)
