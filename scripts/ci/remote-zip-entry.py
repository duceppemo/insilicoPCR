#!/usr/bin/env python3
"""Print one file of a ZIP on the web, reading only the bytes it needs (HTTP range requests).

    remote-zip-entry.py <URL of a ZIP> <file name>

The file is the entry named so, at any depth (VERSIONS.txt finds insilicoPCR-linux-x64/VERSIONS.txt).
The monthly CI run reads the VERSIONS.txt of the latest release this way, a few kilobytes of a
267 MB ZIP, to learn which Java, BLAST+ and BBTools it bundles. Standard library only.
"""
import io
import re
import sys
import urllib.request
import zipfile


class RemoteFile(io.RawIOBase):
    """A file on the web, read by byte ranges."""

    def __init__(self, url):
        # A GET of one byte, not a HEAD: a signed download link may accept only the method it was
        # signed for. The answer gives the size and the address after redirects.
        with urllib.request.urlopen(self._ranged(url, 0, 0)) as answer:
            total = re.fullmatch(r"bytes 0-0/(\d+)", answer.headers.get("Content-Range", ""))
            if answer.status != 206 or not total:
                raise OSError(f"{url}: the server does not serve byte ranges")
            self.url = answer.url
            self.size = int(total.group(1))
        self.pos = 0

    @staticmethod
    def _ranged(url, first, last):
        return urllib.request.Request(url, headers={"Range": f"bytes={first}-{last}"})

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=io.SEEK_SET):
        base = {io.SEEK_SET: 0, io.SEEK_CUR: self.pos, io.SEEK_END: self.size}[whence]
        self.pos = base + offset
        return self.pos

    def read(self, size=-1):
        if size is None or size < 0:
            size = self.size - self.pos
        last = min(self.pos + size, self.size) - 1
        if size == 0 or last < self.pos:
            return b""
        with urllib.request.urlopen(self._ranged(self.url, self.pos, last)) as answer:
            if answer.status != 206:
                raise OSError(f"{self.url}: the server ignored the byte range")
            data = answer.read()
        self.pos += len(data)
        return data


def main(argv):
    if len(argv) != 3:
        sys.exit(__doc__.strip().splitlines()[2].strip())
    url, name = argv[1], argv[2]
    with zipfile.ZipFile(RemoteFile(url)) as archive:
        found = [n for n in archive.namelist() if n == name or n.endswith("/" + name)]
        if len(found) != 1:
            sys.exit(f"{url}: {len(found)} entries named {name}")
        sys.stdout.write(archive.read(found[0]).decode("utf-8"))


if __name__ == "__main__":
    main(sys.argv)
