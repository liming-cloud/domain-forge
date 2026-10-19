"""薄分派器；参数解析、检查及事实记录由既有入口负责。"""

import os
from pathlib import Path
import sys


def main():
    """转交原始 argv；产品缺省根绑定入口，显式路径仍相对调用者目录。"""
    here = Path(__file__).resolve().parent
    args = sys.argv[1:]
    if args[:1] == ["product"]:
        entry = here / "product_gate/verify.py"
        args = ["--project", str(here.parent), *args[1:]]
    else:
        entry = here / "control_plane/control_plane.py"
        if args in (["--help"], ["-h"]):
            print("dev [控制面参数]：使用下列既有命令；dev product --help：查看真实产品验证参数。", flush=True)
    os.execv(sys.executable, [sys.executable, "-B", str(entry), *args])


if __name__ == "__main__":
    main()
