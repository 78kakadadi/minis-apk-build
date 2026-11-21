#!/usr/bin/env python3
"""改桌面显示的应用名（launcher label）。

改的是 res/values*/strings.xml 里的 app_name，不是 Manifest 里的 android:label：
Manifest 里有 5 处 @string/app_name（application + 3 个 activity + 1 个 service），
改资源一次全部跟随，也避免在 Manifest 上做锚点注入——那里注入点太多，容易失配。

**每个语言目录都要改**：values-zh、values-de … 的语言限定比默认 values 更具体，
只改默认 values 时，中文系统的桌面图标下面仍然显示旧名字。

用法：
    python3 set_app_name.py <app 模块目录> "Minis Pro"
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# <string name="app_name">Minis</string>，允许带 translatable 之类的属性
PATTERN = re.compile(
    r'<string(\s+name="app_name"[^>]*)>(.*?)</string>',
    re.DOTALL,
)


def main() -> int:
    ap = argparse.ArgumentParser(description="设置桌面显示的应用名")
    ap.add_argument("app_dir", help="app 模块目录（含 res/ 的那层）")
    ap.add_argument("name", help="新的应用名，例如 Minis Pro")
    a = ap.parse_args()

    app_dir = Path(a.app_dir).resolve()
    # 允许传 app 模块目录（.../app）或源集目录（.../app/src/main）
    main_dir = app_dir if (app_dir / "res").is_dir() else app_dir / "src" / "main"
    if not (main_dir / "res").is_dir():
        print(f"找不到 {main_dir}/res", file=sys.stderr)
        return 2

    name = a.name
    if "<" in name or "&" in name:
        # XML 里 < 与 & 必须转义；应用名出现这两个字符基本是误传
        print(f"应用名不能含 < 或 &：{name}", file=sys.stderr)
        return 2

    # app_name 若被翻译成别的语言，只改 values 是不够的，必须全量
    files = sorted(p for p in (main_dir / "res").glob("values*/strings.xml") if "app_name" in p.read_text(encoding="utf-8", errors="ignore"))
    if not files:
        print("没有找到任何定义 app_name 的 strings.xml", file=sys.stderr)
        return 2

    changed = 0
    for p in files:
        text = p.read_text(encoding="utf-8")
        new_text, n = PATTERN.subn(lambda m: f'<string{m.group(1)}>{name}</string>', text)
        if n:
            if new_text != text:
                p.write_text(new_text, encoding="utf-8")
                changed += 1
            print(f"  {p.relative_to(main_dir)}  ({n} 处)")

    print(f"应用名 → {name}：{len(files)} 个语言文件命中，{changed} 个实际改动")

    # 复查：每个含 app_name 的文件都必须是新值，漏一个语言就会在对应系统上露馅
    bad = []
    for p in files:
        for m in PATTERN.finditer(p.read_text(encoding="utf-8")):
            if m.group(2) != name:
                bad.append((p, m.group(2)))
    if bad:
        print("\n以下位置的值仍是旧名：", file=sys.stderr)
        for p, v in bad[:10]:
            print(f"  {p.relative_to(main_dir)} = {v!r}", file=sys.stderr)
        return 1

    # Manifest 里若有写死的 label，资源改了也不生效，单独提示
    manifest = main_dir / "AndroidManifest.xml"
    if manifest.is_file():
        text = manifest.read_text(encoding="utf-8")
        for m in re.finditer(r'android:label="([^"@][^"]*)"', text):
            print(f"注意：Manifest 里有写死的 label {m.group(1)!r}，它不会跟随 app_name", file=sys.stderr)

    print(f"复查通过：{len(files)} 个语言文件的 app_name 均为 {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
