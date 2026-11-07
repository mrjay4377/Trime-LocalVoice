#!/usr/bin/env python3
"""
把 assembleDebug 产出的 APK 用最高级别 deflate 重新打包，再 zipalign + 用同一把
Android debug 密钥重签，得到体积更小、可直接覆盖安装的交付包。

背景：AGP/AAPT2 打 ZIP 时用的是较低压缩级别，实测 libonnxruntime.so / librime_jni.so /
classes.dex 等大条目还有约 10% 的压缩空间。重压不改任何文件内容，只改 deflate 流，
resources.arsc 等必须 STORED 的条目保持原样，最后交由 zipalign 做 4 字节对齐。

重签使用 ~/.android/debug.keystore（alias=androiddebugkey / password=android），
与 Gradle debug 签名完全一致，因此签名证书不变，用户可以直接覆盖升级。

用法：
  python scripts/repack_apk.py <input.apk> <output.apk>
环境变量：
  ANDROID_HOME / ANDROID_SDK_ROOT  build-tools 所在目录（用于 zipalign / apksigner）
  JAVA_HOME                       apksigner 需要 java
"""

import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

SIGNATURE_NAMES = {"META-INF/MANIFEST.MF"}
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def is_signature_entry(name: str) -> bool:
    if not name.startswith("META-INF/"):
        return False
    upper = name.upper()
    return upper in {n.upper() for n in SIGNATURE_NAMES} or upper.endswith(SIGNATURE_SUFFIXES)


def find_tool(android_home: str, name: str) -> str:
    root = os.path.join(android_home, "build-tools")
    if not os.path.isdir(root):
        raise RuntimeError(f"找不到 build-tools 目录：{root}")
    versions = sorted(os.listdir(root), reverse=True)
    for version in versions:
        for candidate in (name, name + ".exe", name + ".bat"):
            path = os.path.join(root, version, candidate)
            if os.path.isfile(path):
                return path
    raise RuntimeError(f"在 {root} 下找不到 {name}")


def repack(src: str, dst: str) -> None:
    with zipfile.ZipFile(src, "r") as zin:
        entries = zin.infolist()
        with zipfile.ZipFile(dst, "w", allowZip64=True) as zout:
            for info in entries:
                if is_signature_entry(info.filename):
                    continue
                data = zin.read(info.filename)
                out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                out.compress_type = info.compress_type
                out.external_attr = info.external_attr
                out.internal_attr = info.internal_attr
                out.create_system = info.create_system
                out.comment = info.comment
                # 不使用 data descriptor，保证 zipalign / apksigner 解析稳定
                out.flag_bits = info.flag_bits & ~0x08
                if info.compress_type == zipfile.ZIP_DEFLATED:
                    zout.writestr(out, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
                else:
                    zout.writestr(out, data, compress_type=zipfile.ZIP_STORED)


def run(cmd, env):
    print("+", " ".join(cmd), flush=True)
    subprocess.run(cmd, check=True, env=env)


def main() -> int:
    if len(sys.argv) != 3:
        print("用法：python scripts/repack_apk.py <input.apk> <output.apk>")
        return 2
    src, dst = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    if not os.path.isfile(src):
        print(f"输入 APK 不存在：{src}")
        return 2

    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home:
        print("请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT")
        return 2
    keystore = os.path.join(os.path.expanduser("~"), ".android", "debug.keystore")
    if not os.path.isfile(keystore):
        print(f"找不到 debug.keystore：{keystore}")
        return 2

    env = os.environ.copy()
    java_home = env.get("JAVA_HOME")
    if java_home:
        env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")
    apksigner = find_tool(android_home, "apksigner")
    zipalign = find_tool(android_home, "zipalign")

    with tempfile.TemporaryDirectory(prefix="trime_repack_") as tmp:
        raw = os.path.join(tmp, "repacked.apk")
        aligned = os.path.join(tmp, "aligned.apk")
        repack(src, raw)
        run([zipalign, "-p", "-f", "4", raw, aligned], env)
        run(
            [
                apksigner,
                "sign",
                "--ks", keystore,
                "--ks-key-alias", "androiddebugkey",
                "--ks-pass", "pass:android",
                "--key-pass", "pass:android",
                "--v1-signing-enabled", "true",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true",
                "--out", dst,
                aligned,
            ],
            env,
        )
        run([apksigner, "verify", "--verbose", dst], env)

    before, after = os.path.getsize(src), os.path.getsize(dst)
    print(f"\n原始 APK : {before:,} B")
    print(f"精简 APK : {after:,} B")
    print(f"节省     : {before - after:,} B ({(before - after) / before * 100:.1f}%)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
