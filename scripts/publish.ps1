<#
.SYNOPSIS
    把当前仓库发布到 GitHub：创建仓库 → 推送 main + tag → 建 Release 并挂上 APK。

.DESCRIPTION
    需要一个 GitHub Personal Access Token（PAT）：
      - Fine-grained token：Repository access 选 "All repositories"，
        权限勾 Contents: Read and write、Administration: Read and write（建仓库用）；
      - 或 classic token：勾 repo 范围。

    用法：
      $env:GH_TOKEN = 'github_pat_xxx'
      & .\scripts\publish.ps1 -Owner theNewSun1998 -Repo hmdeepseek -Visibility public

    说明：
      * 本机 .gitconfig 里有个失效代理（127.0.0.1:17890），git 调用都带
        -c "http.https://github.com/.proxy=" 覆盖，避免 "Failed to connect ... over proxy"。
      * 仓库已存在时会跳过创建直接推送。
      * 推送用的 token 写在一次性 URL 里，推完立刻把 remote 换回干净地址。
#>
[CmdletBinding()]
param(
    [string]$Owner = 'theNewSun1998',
    [string]$Repo = 'MyDeepSeek',
    [ValidateSet('public', 'private')]
    [string]$Visibility = 'public',
    [string]$Tag = 'v0.1.0',
    [string]$Token = $env:GH_TOKEN
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "缺少 token：先设置 `$env:GH_TOKEN，或用 -Token 传入。"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot
$cleanUrl = "https://github.com/$Owner/$Repo.git"

function Invoke-Git {
    param([string[]]$GitArgs)
    & git -c "http.https://github.com/.proxy=" -c "https://github.com/.proxy=" @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArgs -join ' ') 失败（exit $LASTEXITCODE）" }
}

function Invoke-GitHub {
    param(
        [string]$Method,
        [string]$Uri,
        [string]$Body,
        [string]$InFile,
        [string]$ContentType = 'application/json'
    )
    $curlArgs = @(
        '-sS', '-X', $Method,
        '-H', "Authorization: Bearer $Token",
        '-H', 'Accept: application/vnd.github+json',
        '-H', 'User-Agent: hmdeepseek-publish',
        '-H', "Content-Type: $ContentType"
    )
    if ($Body) { $curlArgs += @('-d', $Body) }
    if ($InFile) { $curlArgs += @('--data-binary', "@$InFile") }
    $curlArgs += $Uri
    return ((& curl.exe @curlArgs 2>&1) -join "`n")
}

Write-Host "== 校验 token ==" -ForegroundColor Cyan
$me = Invoke-GitHub -Method GET -Uri 'https://api.github.com/user'
$login = ($me | ConvertFrom-Json).login
if (-not $login) { throw "token 无效，GitHub 返回：$me" }
Write-Host "   已登录：$login"

Write-Host "== 创建/确认仓库 ==" -ForegroundColor Cyan
$createBody = @{
    name        = $Repo
    private     = ($Visibility -eq 'private')
    description = 'DeepSeek 第三方客户端：Android（Kotlin + Compose）+ HarmonyOS（ArkTS），用自己的 API Key 直连官方接口'
    has_issues  = $true
    has_wiki    = $false
} | ConvertTo-Json
$created = Invoke-GitHub -Method POST -Uri 'https://api.github.com/user/repos' -Body $createBody
if ($created -match '"full_name"') {
    Write-Host "   已创建：$Owner/$Repo"
} else {
    Write-Host "   返回：$created"
    Write-Host "   （若提示 already exists 属正常，继续推送）" -ForegroundColor Yellow
}

Write-Host "== 推送 main 与 tag ==" -ForegroundColor Cyan
$remotes = git remote 2>$null
if ($remotes -contains 'origin') {
    Invoke-Git @('remote', 'set-url', 'origin', $cleanUrl)
} else {
    Invoke-Git @('remote', 'add', 'origin', $cleanUrl)
}
$pushUrl = "https://${login}:$Token@github.com/$Owner/$Repo.git"
Invoke-Git @('push', $pushUrl, 'HEAD:refs/heads/main', '--force')
Invoke-Git @('push', $pushUrl, '--tags')
Invoke-Git @('remote', 'set-url', 'origin', $cleanUrl)
Write-Host "   已推送"

Write-Host "== 创建 Release 并上传 APK ==" -ForegroundColor Cyan
$apk = Join-Path $repoRoot 'dist\hmdeepseek-debug.apk'
if (-not (Test-Path $apk)) { throw "找不到 $apk" }

$notes = @"
用 DeepSeek 官方 App 全套 API 自建的客户端：自己的 Key、不被官方 App 限额。

**功能**：流式回答、深度思考折叠、联网搜索卡片、参考来源、读链接、传文件（PDF/Word/PPT/图）、按住说话（ASR）、会话管理、内容安全开关。

**安装**：下载下面的 app-debug.apk 直接装（Android 8.0+，debug 签名，需允许未知来源）。
和官方 DeepSeek App 包名不同可共存；鸿蒙手机可用卓易通安装（麦克风可能被容器限制）。

详见 [README](https://github.com/$Owner/$Repo#readme) 与 [CHANGELOG](https://github.com/$Owner/$Repo/blob/main/CHANGELOG.md)。
"@

$releaseBody = @{
    tag_name         = $Tag
    target_commitish = 'main'
    name             = "$Tag — 首个可安装版本"
    body             = $notes
    draft            = $false
    prerelease       = $false
} | ConvertTo-Json -Depth 4

$rel = Invoke-GitHub -Method POST -Uri "https://api.github.com/repos/$Owner/$Repo/releases" -Body $releaseBody
$relObj = $rel | ConvertFrom-Json
if (-not $relObj.upload_url) { throw "创建 release 失败：$rel" }
$uploadUrl = ($relObj.upload_url -replace '\{.*\}$', '') + '?name=app-debug.apk'
Write-Host "   release: $($relObj.html_url)"

$assetResp = Invoke-GitHub -Method POST -Uri $uploadUrl -InFile $apk -ContentType 'application/vnd.android.package-archive'
if ($assetResp -match '"browser_download_url"') {
    $assetUrl = ($assetResp | ConvertFrom-Json).browser_download_url
    Write-Host "`n完成！" -ForegroundColor Green
    Write-Host "  仓库   : https://github.com/$Owner/$Repo" -ForegroundColor Green
    Write-Host "  Release: $($relObj.html_url)" -ForegroundColor Green
    Write-Host "  APK    : $assetUrl" -ForegroundColor Green
} else {
    Write-Host "`nRelease 建好了，但 APK 上传失败：$assetResp" -ForegroundColor Yellow
    Write-Host "可以到 $($relObj.html_url) 手动拖入 dist/hmdeepseek-debug.apk" -ForegroundColor Yellow
}
