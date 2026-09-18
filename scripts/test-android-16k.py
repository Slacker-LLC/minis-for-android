"""Exercise the APK ELF/ABI gate without an Android SDK."""
import contextlib
import io
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SOURCE = Path(__file__).with_name('verify-android-16k.sh').read_text().split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]


def elf(align):
    data = bytearray(120)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 1)
    struct.pack_into('<I', data, 64, 1)
    struct.pack_into('<Q', data, 112, align)
    return data


class NativeGateTest(unittest.TestCase):
    def check(self, alignment=16384, missing=False):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'fixture.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                for abi in ('arm64-v8a', 'x86_64'):
                    for name in ('gojni', 'pty_bridge', 'jieba_jni', 'minis_crash_handler'):
                        if missing and abi == 'x86_64' and name == 'gojni':
                            continue
                        archive.writestr(f'lib/{abi}/lib{name}.so', elf(alignment))
            with patch.object(sys, 'argv', ['verify', str(apk)]), contextlib.redirect_stdout(io.StringIO()):
                exec(compile(SOURCE, 'verify-android-16k.sh', 'exec'), {})

    def test_aligned_both_abis(self):
        self.check()

    def test_reject_4k(self):
        with self.assertRaisesRegex(SystemExit, 'LOAD alignments=0x1000'):
            self.check(alignment=4096)

    def test_reject_missing_binding(self):
        with self.assertRaisesRegex(SystemExit, 'x86_64: missing required libgojni.so'):
            self.check(missing=True)


if __name__ == '__main__':
    unittest.main()
