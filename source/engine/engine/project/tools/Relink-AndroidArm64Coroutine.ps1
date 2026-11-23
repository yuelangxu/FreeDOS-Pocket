param([string]$Ndk = 'E:/Dev/Android/Sdk/ndk/28.0.13004108')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$work = "$root/native-build/work" -replace '\\','/'
$qemu = "$work/qemu"
$libs = "$root/native-build/obj/local/arm64-v8a"
$bin = "$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
$out = "$root/release-work/0.3.20/native"
New-Item -ItemType Directory -Path $out -Force | Out-Null
$config = Get-Content -LiteralPath "$qemu/config-host.mak"
$flags = foreach ($key in 'QEMU_CFLAGS','QEMU_INCLUDES') {
    $line = $config | Where-Object { $_.StartsWith("$key=") }
    $line.Substring($key.Length+1).Replace('$(SRC_PATH)', $qemu).Replace('$(ARCH)', 'aarch64').Replace('/d/Virtualization/Android-FreeDOS/native-build/work', $work).Replace('/e/Dev/', 'E:/Dev/') -split '\s+' | Where-Object { $_ }
}
Push-Location $qemu
try {
    & "$bin/clang.exe" @flags -c util/coroutine-sigaltstack.c -o "$out/coroutine-sigaltstack.o"
    if ($LASTEXITCODE) { throw 'Coroutine compilation failed.' }
    Copy-Item -LiteralPath "$qemu/libqemuutil.a" -Destination "$out/libqemuutil.a"
    & "$bin/llvm-ar.exe" rcs "$out/libqemuutil.a" "$out/coroutine-sigaltstack.o"
    if ($LASTEXITCODE) { throw 'Updating QEMU utility archive failed.' }
    & "$bin/clang.exe" --target=aarch64-linux-android21 -shared '-Wl,--no-undefined,-z,noexecstack,-z,relro,-z,now,-soname,qemu-system-x86_64,-z,max-page-size=16384,-z,common-page-size=16384' '-Wl,--whole-archive' "$libs/libqemu-system-x86_64.a" '-Wl,--no-whole-archive' "$out/libqemuutil.a" "-L$libs" -lcompat-limbo "$qemu/dtc/libfdt/libfdt.a" "$qemu/slirp/libslirp.a" '-lglib-2.0' -lcompat-musl -lpixman-1 -lSDL2 -lc -lm -ldl -lz -llog -latomic -o "$out/libqemu-system-x86_64.so"
    if ($LASTEXITCODE) { throw 'QEMU relink failed.' }
} finally { Pop-Location }
