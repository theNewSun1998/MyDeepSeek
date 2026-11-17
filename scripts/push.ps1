<#
.SYNOPSIS
    把本地仓库推到 GitHub（支持 HTTPS 弹窗登录 / SSH key 两种方式）。

.DESCRIPTION
    为什么需要这个脚本：
      本机通过 127.0.0.1:17890 的代理访问 GitHub（直连经常被重置），
      脚本默认所有 git 调用都走代理，传 -Proxy '' 可强制直连。
      -c "http.https://github.com/.proxy=" 覆盖掉。

    HTTPS 方式：系统级 credential.helper=manager，推送时会弹浏览器让你登录 GitHub，
              不需要手搓 PAT。
    SSH 方式： 需要先把 ~/.ssh/arkham_deploy.pub 加到 GitHub（Settings → SSH and GPG keys），
              脚本会用 GIT_SSH_COMMAND 指定这把 key。

.EXAMPLE
    & .\scripts\push.ps1
    & .\scripts\push.ps1 -Method ssh
    & .\scripts\push.ps1 -Owner yourname -Repo hmdeepseek
#>
[CmdletBinding()]
param(
    [string]$Owner = 'theNewSun1998',
    [string]$Repo = 'MyDeepSeek',
    [ValidateSet('https', 'ssh')]
    [string]$Method = 'https',
    [string]$Proxy = 'http://127.0.0.1:17890',
    [string]$SshKey = "$env:USERPROFILE\.ssh\arkham_deploy",
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if ($Method -eq 'ssh') {
    if (-not (Test-Path $SshKey)) { throw "找不到 SSH key：$SshKey" }
    $remoteUrl = "git@github.com:$Owner/$Repo.git"
    $env:GIT_SSH_COMMAND = "ssh -i `"$SshKey`" -o StrictHostKeyChecking=accept-new"
} else {
    $remoteUrl = "https://github.com/$Owner/$Repo.git"
}

# 走本地代理 17890 访问 GitHub（直连经常被重置）；-Proxy '' 则强制直连
$gitProxyArgs = @()
if ([string]::IsNullOrWhiteSpace($Proxy)) {
    $gitProxyArgs = @('-c', 'http.https://github.com/.proxy=')
} else {
    $gitProxyArgs = @('-c', "http.https://github.com/.proxy=$Proxy", '-c', "http.proxy=$Proxy")
}

function Invoke-Git {
    param([string[]]$GitArgs)
    & git @($gitProxyArgs + $GitArgs)
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "git $($GitArgs -join ' ') 失败（exit $LASTEXITCODE）" -ForegroundColor Red
        Write-Host "常见原因：" -ForegroundColor Yellow
        Write-Host "  * 仓库还没建：先去 https://github.com/new 建一个空仓库 $Repo（不要勾 README/gitignore/license）"
        Write-Host "  * HTTPS 登录被取消：重跑一次，弹出浏览器后用你的 GitHub 账号授权"
        Write-Host "  * 提示 refusing to allow a Personal Access Token to create or update workflow："
        Write-Host "    改用 SSH 方式（& .\scripts\push.ps1 -Method ssh），或给 token 加 workflow 权限"
        Write-Host "  * SSH 报 Permission denied (publickey)：把 $SshKey.pub 的内容加到 GitHub SSH keys"
        throw "推送失败"
    }
}

Write-Host "== 检查仓库状态 ==" -ForegroundColor Cyan
$branch = git branch --show-current
if ([string]::IsNullOrWhiteSpace($branch)) { throw "当前不在任何分支上" }
$dirty = git status --porcelain
if ($dirty) {
    Write-Host "   有未提交改动，先提交：" -ForegroundColor Yellow
    $dirty | Select-Object -First 10 | ForEach-Object { Write-Host "     $_" }
    throw "工作区不干净"
}
Write-Host "   分支：$branch"
Write-Host "   远程：$remoteUrl（$Method）"

Write-Host "== 配置 remote ==" -ForegroundColor Cyan
$remotes = git remote 2>$null
if ($remotes -contains 'origin') {
    Invoke-Git @('remote', 'set-url', 'origin', $remoteUrl)
} else {
    Invoke-Git @('remote', 'add', 'origin', $remoteUrl)
}
Write-Host "   origin -> $remoteUrl"

Write-Host "== 推送 $branch ==" -ForegroundColor Cyan
$pushArgs = @('push', '-u', 'origin', $branch)
if ($Force) { $pushArgs += '--force' }
Invoke-Git $pushArgs

Write-Host "== 推送 tag（会触发 CI 打包并自动发 Release）==" -ForegroundColor Cyan
Invoke-Git @('push', 'origin', '--tags')

Write-Host "`n完成！" -ForegroundColor Green
Write-Host "  仓库   : https://github.com/$Owner/$Repo" -ForegroundColor Green
Write-Host "  Actions: https://github.com/$Owner/$Repo/actions" -ForegroundColor Green
Write-Host "  Releases: https://github.com/$Owner/$Repo/releases" -ForegroundColor Green
Write-Host "`nCI 跑完（约 3~5 分钟）后，Releases 里会有 app-debug.apk，手机上点链接就能装。" -ForegroundColor Green
