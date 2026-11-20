#!/usr/bin/env python3
"""校验 OpenCode native ELF 的结构，拒绝 bash 包装脚本。

规格要求 bin/opencode 是真正可直接 execve 的 ELF（§9.2），不能是脚本包装。
这里只读文件头，不加载整个文件，199 MB 也能瞬间完成。
"""
import os
import sys

MIN_SIZE = 100 * 1024 * 1024  # 规格称单 ELF 约 180 MB，低于 100 MB 基本可判为错误产物

ET_EXEC = 2
ET_DYN = 3
EM_AARCH64 = 0xB7


def main() -> int:
    if len(sys.argv) != 2:
        print("用法: verify_elf.py <path>", file=sys.stderr)
        return 2

    path = sys.argv[1]
    if not os.path.isfile(path):
        print(f"[FAIL] 文件不存在: {path}", file=sys.stderr)
        return 1

    size = os.path.getsize(path)
    with open(path, "rb") as fh:
        head = fh.read(64)

    if len(head) < 52:
        print(f"[FAIL] 文件过短，读不到 ELF 头: {len(head)} 字节", file=sys.stderr)
        return 1

    problems = []
    if head[:4] != b"\x7fELF":
        problems.append(f"不是 ELF（前 4 字节 {head[:4]!r}）")
    else:
        if head[4] != 2:
            problems.append("不是 64 位")
        if head[5] != 1:
            problems.append("不是小端")
        machine = head[18] | (head[19] << 8)
        if machine != EM_AARCH64:
            problems.append(f"架构不是 aarch64（e_machine=0x{machine:x}）")
        e_type = head[16]
        if e_type not in (ET_EXEC, ET_DYN):
            problems.append(f"类型既非 EXEC 也非 PIE（e_type={e_type}）")

    if size < MIN_SIZE:
        problems.append(f"体积异常偏小: {size / 1048576:.1f} MB < {MIN_SIZE / 1048576:.0f} MB")

    if problems:
        print("[FAIL] " + "；".join(problems), file=sys.stderr)
        return 1

    kind = "PIE" if head[16] == ET_DYN else "EXEC"
    print(f"[OK] ELF aarch64 {kind}, {size / 1048576:.1f} MB, {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
