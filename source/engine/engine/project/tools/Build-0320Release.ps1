$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$out="$root/release-work/0.3.20"
$assets="$out/dos/assets"
$bt='E:/Dev/Android/Sdk/build-tools/35.0.0'
$gradle='E:/Dev/Caches/Gradle/wrapper/dists/gradle-8.14-bin/38aieal9i53h9rfe7vjup95b9/gradle-8.14/bin/gradle.bat'
if (!(Test-Path -LiteralPath "$assets/seed/manifest.json")) { throw 'Stage the repaired D: seed first.' }
$env:GRADLE_USER_HOME='E:/Dev/Caches/Gradle'
Push-Location $root
try {
    & $gradle --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' "-PfdpUniversalNative=$out/verified-jni" "-PfdpPreinstalledAssets=$assets" :app:assemblePersonalCommunityUniversalRelease
    if ($LASTEXITCODE) { throw 'Build failed.' }
    & "$bt/zipalign.exe" -f -P 16 4 'app/build/outputs/apk/personalCommunityUniversalRelease/app-personalCommunityUniversalRelease-unsigned.apk' "$out/aligned.apk"
    if ($LASTEXITCODE) { throw 'APK alignment failed.' }
    $cred=[pscredential]::new('release',(ConvertTo-SecureString (Get-Content -LiteralPath 'private/signing/release-password.dpapi' -Raw)))
    try {
        $env:FDP_0320_SIGN=$cred.GetNetworkCredential().Password
        & "$bt/apksigner.bat" sign --ks 'private/signing/freedos-pocket-release.p12' --ks-type PKCS12 --ks-key-alias freedos-pocket-release --ks-pass env:FDP_0320_SIGN --key-pass env:FDP_0320_SIGN --min-sdk-version 21 --out "$out/FreeDOS-Pocket-0.3.20.apk" "$out/aligned.apk"
        if ($LASTEXITCODE) { throw 'APK signing failed.' }
    } finally { $env:FDP_0320_SIGN=$null }
    & "$bt/zipalign.exe" -c -P 16 4 "$out/FreeDOS-Pocket-0.3.20.apk"
    if ($LASTEXITCODE) { throw 'APK alignment check failed.' }
    & "$bt/apksigner.bat" verify "$out/FreeDOS-Pocket-0.3.20.apk"
    if ($LASTEXITCODE) { throw 'APK signature check failed.' }
} finally { Pop-Location }
