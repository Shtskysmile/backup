# 答辩演示数据准备脚本（Windows PowerShell）
# 用法:  powershell -ExecutionPolicy Bypass -File prepare-demo-data.ps1 [-RootDir D:\demo-data] [-BigGB 2]
# 生成四套数据: demo-small / demo-complex / demo-special / demo-big
param(
    [string]$RootDir = ".\demo-data",
    [int]$BigGB = 2
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $RootDir | Out-Null
$RootDir = (Resolve-Path $RootDir).Path

function Write-TextFile([string]$Path, [string]$Content) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Path) | Out-Null
    Set-Content -Path $Path -Value $Content -Encoding UTF8
}

# ---------- 1. 简单样本 ----------
$small = Join-Path $RootDir "demo-small"
Write-TextFile (Join-Path $small "readme.txt") "Backup 演示数据 - 简单样本"
Write-TextFile (Join-Path $small "notes.md") "# 会议纪要`n- 备份软件答辩准备"
Write-TextFile (Join-Path $small "data.csv") "id,name,bytes`n1,alpha,1024`n2,beta,2048"
Write-Output "demo-small: 3 files"

# ---------- 2. 复杂样本（深嵌套/中文/空目录/多文件） ----------
$complex = Join-Path $RootDir "demo-complex"
foreach ($d in @("项目资料/2026学期/答辩材料/最终版", "项目资料/2026学期/会议记录", "实验数据/raw/session-001", "实验数据/raw/session-002", "实验数据/processed", "个人文件/照片/2026-春游", "代码备份/src/main/java")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $complex $d) | Out-Null
}
Write-TextFile (Join-Path $complex "项目资料/2026学期/答辩材料/最终版/演示文稿大纲.txt") "1. 需求分析 2. 系统设计 3. 演示"
Write-TextFile (Join-Path $complex "项目资料/2026学期/答辩材料/最终版/分工表.csv") "角色,模块,成员"
Write-TextFile (Join-Path $complex "实验数据/raw/session-001/传感器.log") ("[2026-11-05 09:00:00] sample ok`n" * 200)
Write-TextFile (Join-Path $complex "代码备份/src/main/java/Main.java") "public class Main { }"
for ($i = 1; $i -le 120; $i++) {
    $sub = "实验数据/raw/session-00" + ($i % 2 + 1)
    Write-TextFile (Join-Path $complex "$sub/measurement-$i.dat") ("record $i, value=$($i * 3.14), checksum=$($i % 7)")
}
New-Item -ItemType Directory -Force -Path (Join-Path $complex "实验数据/tmp-empty") | Out-Null
Write-Output "demo-complex: 4+ dirs, 120+ files, unicode names, 1 empty dir"

# ---------- 3. 特殊文件样本 ----------
$special = Join-Path $RootDir "demo-special"
New-Item -ItemType Directory -Force -Path $special | Out-Null
$base = Join-Path $special "report-body.txt"
Write-TextFile $base "这份内容被两个名字共享（硬链接演示）"
# 硬链接：b-link.txt 与 report-body.txt 同一文件 ID
cmd /c mklink /h "`"$special\b-link.txt`"" "`"$base`"" | Out-Null
# 软链接（mklink 支持开发者模式；失败降级为警告，不中断）
function New-Symlink([string]$Link, [string]$Target, [switch]$Directory) {
    $flag = if ($Directory) { "/d" } else { "" }
    $out = cmd /c mklink $flag "`"$Link`"" "`"$Target`"" 2>&1
    if ($LASTEXITCODE -ne 0) { Write-Warning "symlink failed ($Link): $out —— 请用管理员或开发者模式重跑" }
}
New-Symlink (Join-Path $special "s-to-file.lnk") $base
New-Symlink (Join-Path $special "s-to-complex") (Join-Path $RootDir "demo-complex") -Directory
New-Symlink (Join-Path $special "s-broken.lnk") (Join-Path $special "missing-target.bin")
Write-Output "demo-special: 1 hardlink pair, 3 symlinks (file/dir/broken)"

# ---------- 4. 大数据样本（GB 级，fsutil 秒级生成） ----------
$big = Join-Path $RootDir "demo-big"
New-Item -ItemType Directory -Force -Path (Join-Path $big "media"), (Join-Path $big "logs") | Out-Null
$bytesTotal = [long]$BigGB * 1GB
$perFile = [long]($bytesTotal / 4)
1..4 | ForEach-Object { fsutil file createnew (Join-Path $big "media/video-part$_.bin") $perFile | Out-Null }
# 一个小的高熵文件用于展示压缩差异
$rng = [System.Random]::new(42); $rand = New-Object byte[] (4MB); $rng.NextBytes($rand)
[System.IO.File]::WriteAllBytes((Join-Path $big "logs/random-sample.bin"), $rand)
Write-Output "demo-big: $BigGB GB across 4 video parts + 4MB random"

Write-Output "DONE. Root = $RootDir"
Write-Output "注意: Windows 上无法创建 FIFO 管道文件；管道演示请在 macOS/Linux 用 prepare-demo-data.sh。"
