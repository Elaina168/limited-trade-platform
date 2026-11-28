"""下载本实验锁定版本的官方 Nacos 便携包并核对 SHA-256。"""
from hashlib import file_digest
from pathlib import Path
from shutil import copyfileobj
from urllib.request import urlopen
from zipfile import ZipFile

VERSION = "3.0.3"
SHA256 = "801b694fed684daef926915a1632b7df056debbf48449a3ce140a3f4243d4978"
ROOT = Path(__file__).resolve().parents[1] / "runtime" / f"nacos-{VERSION}"
ARCHIVE = ROOT / f"nacos-server-{VERSION}.zip"
URL = f"https://download.nacos.io/nacos-server/{ARCHIVE.name}"


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    if not ARCHIVE.exists():
        partial = ARCHIVE.with_suffix(".zip.part")
        with urlopen(URL, timeout=60) as response, partial.open("wb") as output:
            copyfileobj(response, output)
        with partial.open("rb") as source:
            if file_digest(source, "sha256").hexdigest() != SHA256:
                raise ValueError("下载的 Nacos 包 SHA-256 不匹配")
        partial.replace(ARCHIVE)
    with ARCHIVE.open("rb") as source:
        if file_digest(source, "sha256").hexdigest() != SHA256:
            raise ValueError(f"本地 Nacos 缓存校验失败：{ARCHIVE}")
    jar = ROOT / "nacos" / "target" / "nacos-server.jar"
    if not jar.is_file():
        with ZipFile(ARCHIVE) as bundle:
            for entry in bundle.infolist():
                if not (ROOT / entry.filename).resolve().is_relative_to(ROOT):
                    raise ValueError(f"Nacos 包含越界路径：{entry.filename}")
            bundle.extractall(ROOT)
    print(jar.resolve(strict=True))


if __name__ == "__main__":
    main()
