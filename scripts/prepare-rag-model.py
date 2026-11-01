"""下载已固定修订的BGE ONNX模型，核对官方文件摘要后保存在项目tools目录。"""
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request

root = Path(__file__).resolve().parents[1]
target = root / "tools/models/bge-small-zh-v1.5"
target.mkdir(parents=True, exist_ok=True)
revision = "75c43b069aac4d136ba6bc1122f995fedcfd2781"
files = {
    "onnx/model.onnx": ("model.onnx", "69a0b846f4f116b5e6aabf9546ea6754d02264f3211a13a1bd69b31b8040749a", 94851877),
    "tokenizer.json": ("tokenizer.json", "cdb3043fc938fc918c06e66cf704c2ba58f88747", 439125),
}
manifest = {"repository": "Xenova/bge-small-zh-v1.5", "revision": revision, "files": {}}
for remote, (name, expected, length) in files.items():
    destination = target / name
    if not destination.exists():
        temporary = destination.with_suffix(destination.suffix + ".part")
        request = urllib.request.Request(
            f"https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/{revision}/{remote}",
            headers={"User-Agent": "limited-trade-rag/1.0"},
        )
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        temporary.replace(destination)
    if destination.stat().st_size != length:
        raise ValueError(f"模型文件长度不匹配：{name}")
    with destination.open("rb") as source:
        sha256 = hashlib.file_digest(source, "sha256").hexdigest()
    # 大文件校验LFS SHA256，小文件校验官方Git blob ID。
    actual = sha256 if name.endswith("onnx") else hashlib.sha1(
        b"blob " + str(length).encode() + b"\0" + destination.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError(f"模型文件摘要不匹配：{name}")
    manifest["files"][name] = {"bytes": length, "sha256": sha256}
    print(f"已核验 {name}: {length} bytes")
(target / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"模型目录：{target}")
