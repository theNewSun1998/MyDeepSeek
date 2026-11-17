<#
.SYNOPSIS
    本机一键打 APK（用仓库里 .build/ 下已经准备好的 JDK 17 + Android SDK + Gradle）。

.DESCRIPTION
    不依赖 Android Studio、也不会去写 C:\Users\<you>\.android（某些环境没权限）。
    签名用 .build/keystore/debug.jks，通过 AGP 的注入式签名参数传进去。
    第一次跑会下载依赖（约 300 MB），之后增量构建约 1 分钟。

.EXAMPLE
    pwsh -File android/build.ps1
#>
[CmdletBinding()]
param(
    [string]$Task = 'assembleDebug'
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot          # 仓库根
$root = Join-Path $repo '.build'
$jdk = Join-Path $root 'jdk\jdk-17.0.21+9'
$sdk = Join-Path $root 'sdk'
$gradle = Join-Path $root 'gradle\gradle-8.7\bin\gradle.bat'
$keystore = Join-Path $root 'keystore\debug.jks'

foreach ($p in @($jdk, $sdk, $gradle, $keystore)) {
    if (-not (Test-Path $p)) {
        throw "缺少 $p —— 先按 README 的「本机构建」准备工具链（JDK 17 / Android SDK 34 / Gradle 8.7 / debug keystore）。"
    }
}

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:GRADLE_USER_HOME = Join-Path $root 'gradle-home'
$env:TEMP = Join-Path $root 'tmp'
$env:TMP = $env:TEMP
$env:PATH = "$jdk\bin;$env:PATH"

Push-Location $PSScriptRoot
try {
    & $gradle $Task --console=plain `
        "-Dorg.gradle.native.dir=$root\native" `
        "-Djava.io.tmpdir=$root\tmp" `
        "-Pandroid.injected.signing.store.file=$keystore" `
        "-Pandroid.injected.signing.store.password=android" `
        "-Pandroid.injected.signing.key.alias=androiddebugkey" `
        "-Pandroid.injected.signing.key.password=android"
    if ($LASTEXITCODE -ne 0) { throw "构建失败（exit $LASTEXITCODE）" }
}
finally {
    Pop-Location
}

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    $f = Get-Item $apk
    Write-Host "`nAPK: $($f.FullName)" -ForegroundColor Green
    Write-Host ("     {0:N2} MB" -f ($f.Length / 1MB)) -ForegroundColor Green
} else {
    Write-Host "`n构建结束但没找到 app-debug.apk" -ForegroundColor Yellow
}
