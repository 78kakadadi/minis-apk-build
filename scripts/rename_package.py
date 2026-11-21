#!/usr/bin/env python3
"""应用包名整体迁移：com.openminis.app → pro.openminis.app。

必须一次做完这四件事，少一件就是「装得上、点开就崩」：

1. 源码目录与 package 声明同步改。只改 package 不改目录，AGP 会因为
   namespace 与源集路径不匹配报错；只改目录不改 package 直接编不过。
2. 文本里的包名远不止 package/import：BuildConfig 与 R 的引用、
   Class.forName 的字符串、广播与 intent 的 action、ACRA 的
   META-INF/services 文件、shortcuts.xml 的 targetPackage/targetClass、
   gradle 的 namespace/applicationId。漏一处都是运行期才炸。
3. native 侧用 JNI 符号名硬绑定 Java 类，例如
   Java_com_openminis_app_sandbox_PtyBridge_forkExec。包名变了符号名不跟着变，
   就是 UnsatisfiedLinkError——这是最容易漏、也最难定位的一环。
4. 改完必须复查残留，残留一处等于没改。

**不动** com.openminis.rclone：那是 gomobile 从 Go 生成的绑定包，包名由
native 侧决定，改了之后 RcloneBridge 首次调用就会 UnsatisfiedLinkError。

用法：
    python3 rename_package.py <工程根目录>            # 默认 com.openminis.app → pro.openminis.app
    python3 rename_package.py overlay --new x.y.z      # 目录/包名换别的
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
from pathlib import Path

# 这些目录里没有源码，扫了只是浪费时间；build/ 里还可能留着上一轮的旧产物，
# 改了反而骗过下面的残留检查。
SKIP_DIRS = {".git", "build", ".gradle", ".idea", "node_modules", "Pods"}
# 单文件上限：超过这个体积的一定是二进制资源，不可能含包名字符串。
MAX_SIZE = 24 * 1024 * 1024


def iter_files(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            p = Path(dirpath) / name
            try:
                if p.stat().st_size > MAX_SIZE:
                    continue
            except OSError:
                continue
            yield p


def replace_in_files(root: Path, pairs: list[tuple[bytes, bytes]]) -> tuple[int, int]:
    """按字节替换。UTF-8 下 ASCII 子串替换不会破坏其他字节，
    所以不需要判断编码，也就不会误伤含 NUL 的 Kotlin 文件
    （上游 MountedFoldersStore.kt / MarkdownClipboard.kt 里就有）。"""
    files = 0
    hits = 0
    for p in iter_files(root):
        try:
            data = p.read_bytes()
        except OSError:
            continue
        changed = False
        for old, new in pairs:
            n = data.count(old)
            if n:
                data = data.replace(old, new)
                hits += n
                changed = True
        if changed:
            try:
                p.write_bytes(data)
                files += 1
            except OSError as e:
                print(f"  写入失败 {p}: {e}", file=sys.stderr)
    return files, hits


def move_packages(root: Path) -> list[tuple[Path, Path]]:
    """把 .../com/openminis/app 整体搬到 .../pro/openminis/app。

    先收集再移动：main / test / androidTest 三个源集互不嵌套，
    但移动过程中 rglob 结果会失效，所以必须一次性取全。
    """
    srcs = [
        p
        for p in root.rglob("app")
        if p.is_dir() and p.parent.name == "openminis" and p.parent.parent.name == "com"
    ]
    moved: list[tuple[Path, Path]] = []
    for src in sorted(srcs):
        if not src.exists():
            continue
        com_dir = src.parent.parent
        dst = com_dir.parent / "pro" / "openminis" / "app"
        if dst.exists():
            shutil.rmtree(dst)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(src), str(dst))
        moved.append((src, dst))
        # 顺手清掉搬空的 com/openminis、com；非空就留着（比如 com/openminis/rclone）
        for d in (src.parent, com_dir):
            try:
                d.rmdir()
            except OSError:
                pass
    return moved


def find_leftovers(root: Path, needles: list[bytes]) -> list[tuple[Path, bytes]]:
    bad: list[tuple[Path, bytes]] = []
    for p in iter_files(root):
        try:
            data = p.read_bytes()
        except OSError:
            continue
        for needle in needles:
            if needle in data:
                bad.append((p, needle))
                break
    return bad


def main() -> int:
    ap = argparse.ArgumentParser(description="迁移 Android 应用包名")
    ap.add_argument("root", nargs="?", default=".", help="工程根目录（含 app/ 的那层）")
    ap.add_argument("--old", default="com.openminis.app", help="旧包名")
    ap.add_argument("--new", default="pro.openminis.app", help="新包名")
    a = ap.parse_args()

    root = Path(a.root).resolve()
    if not root.is_dir():
        print(f"目录不存在：{root}", file=sys.stderr)
        return 2

    old_jni = a.old.replace(".", "_") + "_"
    new_jni = a.new.replace(".", "_") + "_"

    print(f"包名迁移：{a.old} → {a.new}")
    print(f"根目录：{root}")

    files, hits = replace_in_files(
        root,
        [(old_jni.encode(), new_jni.encode()), (a.old.encode(), a.new.encode())],
    )
    print(f"  文本替换：{files} 个文件，{hits} 处（含 {old_jni}* 这类 JNI 符号）")

    moved = move_packages(root)
    for src, dst in moved:
        print(f"  目录：{src.relative_to(root)} → {dst.relative_to(root)}")
    if not moved:
        # 允许幂等重跑：已经改过的树再跑一次不该报错
        print("  目录：无需移动（可能已改过）")

    leftovers = find_leftovers(
        root,
        [a.old.encode(), old_jni.encode()],
    )
    if leftovers:
        print("\n残留检查未通过，以下位置仍含旧包名：", file=sys.stderr)
        for p, needle in leftovers[:20]:
            print(f"  {p.relative_to(root)}  ← {needle.decode()}", file=sys.stderr)
        print(
            "\n这类残留通常意味着它在二进制文件里（>24MB 被跳过）或被 .git 过滤。"
            "请确认后再构建，否则运行期才会暴露。",
            file=sys.stderr,
        )
        return 1

    print("残留检查通过：树中已无旧包名。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
