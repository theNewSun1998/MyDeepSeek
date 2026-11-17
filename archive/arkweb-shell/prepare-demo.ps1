<#
.SYNOPSIS
    把官方开源的 DeepSeek 前端（deepseek-ai/chat-demo）构建好，放进 HAP 的 rawfile，
    让鸿蒙客户端可以离线加载、也方便你自己改页面。

.DESCRIPTION
    需要在**有网络**的机器上跑（第一次会克隆仓库并安装依赖，约 1~2 分钟）。
    产物会拷到 entry/src/main/resources/rawfile/web/，只要那里出现 index.html，
    App 下次启动就会优先加载本地版，失败自动回退在线版。

.EXAMPLE
    pwsh -File scripts/prepare-demo.ps1

.EXAMPLE
    pwsh -File scripts/prepare-demo.ps1 -Refresh     # 强制重新拉取并构建
#>
[CmdletBinding()]
param(
    [switch]$Refresh,
    [string]$Repo = 'https://github.com/deepseek-ai/chat-demo.git'
)

$ErrorActionPreference = 'Stop'

$root      = Split-Path -Parent $PSScriptRoot
$cacheDir  = Join-Path $root '.cache'
$srcDir    = Join-Path $cacheDir 'chat-demo'
$targetDir = Join-Path $root 'entry\src\main\resources\rawfile\web'

function Write-Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn2([string]$msg){ Write-Host "    $msg" -ForegroundColor Yellow }

# ---------- 0. 工具检查 ----------
Write-Step '检查工具链'
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw '找不到 git，请先安装 Git for Windows。' }
Write-Ok "git  $(git --version)"

$pm = $null
foreach ($candidate in @('pnpm', 'npm')) {
    if (Get-Command $candidate -ErrorAction SilentlyContinue) { $pm = $candidate; break }
}
if (-not $pm) { throw '找不到 pnpm 或 npm。装了 Node.js 之后执行 corepack enable 即可获得 pnpm。' }
Write-Ok "$pm  $(& $pm --version)"

# ---------- 1. 拉源码 ----------
Write-Step '准备 chat-demo 源码'
if (-not (Test-Path $srcDir)) {
    New-Item -ItemType Directory -Path $cacheDir -Force | Out-Null
    git clone --depth 1 $Repo $srcDir
} elseif ($Refresh) {
    git -C $srcDir fetch --depth 1 origin
    git -C $srcDir reset --hard origin/HEAD
} else {
    Write-Warn2 '已存在 .cache/chat-demo，跳过克隆（要更新加 -Refresh）'
}
Write-Ok $srcDir

# ---------- 2. 看它到底暴露了哪些脚本 ----------
$pkgPath = Join-Path $srcDir 'package.json'
if (-not (Test-Path $pkgPath)) { throw "没有找到 $pkgPath，仓库结构可能变了。" }
$pkg = Get-Content -Raw $pkgPath | ConvertFrom-Json
$scripts = @()
if ($pkg.PSObject.Properties.Name -contains 'scripts' -and $pkg.scripts) {
    $scripts = $pkg.scripts.PSObject.Properties.Name
}
Write-Step "可用脚本：$(if ($scripts) { $scripts -join ', ' } else { '(无)' })"

$buildScript = $null
foreach ($name in @('build', 'dist', 'bundle', 'prod')) {
    if ($scripts -contains $name) { $buildScript = $name; break }
}

# ---------- 3. 安装依赖 ----------
Write-Step "安装依赖（$pm）"
Push-Location $srcDir
try {
    if ($pm -eq 'pnpm') {
        & pnpm install --prefer-offline
    } else {
        & npm install --no-audit --no-fund
    }
    if ($LASTEXITCODE -ne 0) { throw "依赖安装失败（exit $LASTEXITCODE）。" }
    Write-Ok '依赖就绪'

    # ---------- 4. 构建 ----------
    if ($buildScript) {
        Write-Step "构建（$pm run $buildScript）"
        & $pm run $buildScript
        if ($LASTEXITCODE -ne 0) { throw "构建失败（exit $LASTEXITCODE）。" }
        Write-Ok '构建完成'
    } else {
        Write-Warn2 'package.json 里没有 build/dist 脚本，假设仓库里已有可直接用的静态产物。'
    }
}
finally {
    Pop-Location
}

# ---------- 5. 找到产物目录 ----------
Write-Step '定位构建产物'
$candidates = @('dist', 'build', 'out', 'web-build', 'dist\web') | ForEach-Object { Join-Path $srcDir $_ }
$distRoot = $null
foreach ($c in $candidates) {
    if (Test-Path (Join-Path $c 'index.html')) { $distRoot = $c; break }
}
if (-not $distRoot) {
    # 兜底：仓库根目录就有 index.html（官方 README 也提到双击 index.html 能用）
    if (Test-Path (Join-Path $srcDir 'index.html')) {
        $distRoot = $srcDir
    }
}
if (-not $distRoot) {
    $found = Get-ChildItem -Path $srcDir -Recurse -Filter index.html -File -Depth 3 -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch 'node_modules' } |
        Select-Object -First 1
    if ($found) { $distRoot = $found.DirectoryName }
}
if (-not $distRoot) { throw '没找到含 index.html 的产物目录，请手动指定或检查构建输出。' }
Write-Ok $distRoot

# ---------- 6. 拷进 rawfile ----------
Write-Step '同步到 rawfile/web'
if (Test-Path $targetDir) {
    Get-ChildItem -Path $targetDir -Force | Where-Object { $_.Name -ne 'README.md' } |
        Remove-Item -Recurse -Force
} else {
    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
}
Get-ChildItem -Path $distRoot -Force |
    Where-Object { $_.Name -notin @('node_modules', '.git') } |
    Copy-Item -Destination $targetDir -Recurse -Force

$count   = (Get-ChildItem -Path $targetDir -Recurse -File).Count
$sizeMb  = [math]::Round(((Get-ChildItem -Path $targetDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 2)
Write-Ok "已同步 $count 个文件，共 $sizeMb MB"

Write-Host "`n完成。用 DevEco Studio 重新构建/运行，App 就会加载本地前端（离线可用）。" -ForegroundColor Green
Write-Host "想改 UI 的话，改 .cache/chat-demo 里的源码，然后重跑本脚本。" -ForegroundColor Green
