#!/usr/bin/env bash
# CI 失败时把测试与检查失败摘要输出为 GitHub 注解，便于不下载日志也能定位。
set -uo pipefail
root="${1:-platform}"
count=0
emit() { [ "$count" -lt 40 ] && echo "::error::$1"; count=$((count + 1)); }
while IFS= read -r file; do
  while IFS= read -r line; do emit "$(basename "$file" .txt): ${line:0:300}"; done \
    < <(grep -E '<<< (FAILURE|ERROR)|^[a-zA-Z.]+(Exception|Error)|Caused by' "$file" | head -6)
done < <(grep -lE 'FAILURE|ERROR' $(find "$root" -path '*-reports/*.txt' 2>/dev/null) 2>/dev/null)
while IFS= read -r file; do
  while IFS= read -r line; do emit "checkstyle: ${line:0:300}"; done \
    < <(grep -o '<error [^>]*' "$file" | head -5)
done < <(find "$root" -name 'checkstyle-*result*.xml' 2>/dev/null)
[ -f "${2:-}" ] && grep -E '^\[ERROR\]' "$2" | grep -v '^\[ERROR\] *$' | head -15 | while IFS= read -r line; do echo "::error::${line:0:300}"; done
exit 0
