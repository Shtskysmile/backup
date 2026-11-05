#!/usr/bin/env bash
# 答辩演示数据准备脚本（macOS / Linux）
# 用法:  ./prepare-demo-data.sh [输出目录] [GB数]
set -euo pipefail
ROOT="${1:-./demo-data}"
BIG_GB="${2:-2}"
mkdir -p "$ROOT"; ROOT="$(cd "$ROOT" && pwd)"

# ---------- 1. 简单样本 ----------
mkdir -p "$ROOT/demo-small"
printf 'Backup 演示数据 - 简单样本\n' > "$ROOT/demo-small/readme.txt"
printf '# 会议纪要\n- 备份软件答辩准备\n' > "$ROOT/demo-small/notes.md"

# ---------- 2. 复杂样本 ----------
mkdir -p "$ROOT/demo-complex/项目资料/2026学期/答辩材料/最终版" \
         "$ROOT/demo-complex/项目资料/2026学期/会议记录" \
         "$ROOT/demo-complex/实验数据/raw/session-001" \
         "$ROOT/demo-complex/实验数据/processed" \
         "$ROOT/demo-complex/个人文件/照片/2026-春游" \
         "$ROOT/demo-complex/代码备份/src/main/java"
printf '1. 需求分析 2. 系统设计 3. 演示\n' > "$ROOT/demo-complex/项目资料/2026学期/答辩材料/最终版/演示文稿大纲.txt"
yes 'sample ok' | head -200 > "$ROOT/demo-complex/实验数据/raw/session-001/传感器.log"
echo 'public class Main { }' > "$ROOT/demo-complex/代码备份/src/main/java/Main.java"
for i in $(seq 1 120); do
  printf 'record %s, value=%s\n' "$i" "$(awk "BEGIN{print $i*3.14}")" > "$ROOT/demo-complex/实验数据/raw/session-001/measurement-$i.dat"
done
mkdir -p "$ROOT/demo-complex/实验数据/tmp-empty"

# ---------- 3. 特殊文件样本 ----------
mkdir -p "$ROOT/demo-special"
printf '这份内容被两个名字共享（硬链接演示）\n' > "$ROOT/demo-special/report-body.txt"
ln "$ROOT/demo-special/report-body.txt" "$ROOT/demo-special/b-link.txt"                 # 硬链接
ln -s report-body.txt "$ROOT/demo-special/s-to-file.lnk"                                # 相对软链接
ln -s "$ROOT/demo-complex" "$ROOT/demo-special/s-to-complex"                            # 绝对软链接
ln -s "$ROOT/demo-special/missing-target.bin" "$ROOT/demo-special/s-broken.lnk"         # 断链
mkfifo "$ROOT/demo-special/my-pipe"                                                     # 管道

# ---------- 4. 大数据样本 ----------
mkdir -p "$ROOT/demo-big/media" "$ROOT/demo-big/logs"
PER=$(( BIG_GB * 1024 * 1024 / 4 ))   # KB 精度，4 个分片
for i in 1 2 3 4; do dd if=/dev/zero of="$ROOT/demo-big/media/video-part$i.bin" bs=1024 count="$PER" status=none; done
dd if=/dev/urandom of="$ROOT/demo-big/logs/random-sample.bin" bs=1048576 count=4 status=none

echo "DONE. Root = $ROOT"
echo "验证: ls -li '$ROOT/demo-special'  （b-link 与 report-body 应同 inode，my-pipe 应为 p 类型）"
