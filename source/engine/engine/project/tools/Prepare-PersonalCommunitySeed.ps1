[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [Parameter(Mandatory)][string]$SystemVdi,
    [Parameter(Mandatory)][string]$SoftwareVdi,
    [string]$AssetDirectory
)

$ErrorActionPreference = 'Stop'
$assets = if ($AssetDirectory) { $AssetDirectory } else { Join-Path $ProjectRoot 'app\src\personalCommunityUniversalRelease\assets\seed' }
New-Item -ItemType Directory -Force -Path $assets | Out-Null
$manifest = [ordered]@{}
foreach ($item in @(@{Name='system'; Path=$SystemVdi}, @{Name='software'; Path=$SoftwareVdi})) {
    $source = (Resolve-Path -LiteralPath $item.Path).Path
    $destination = Join-Path $assets ($item.Name + '.vdi.bin')
    if (Test-Path -LiteralPath $destination) { throw "Refusing to overwrite existing seed: $destination" }
    $input = [IO.File]::OpenRead($source)
    try {
        $output = [IO.File]::Create($destination)
        try {
            $gzip = [IO.Compression.GZipStream]::new($output, [IO.Compression.CompressionLevel]::Optimal, $true)
            try { $input.CopyTo($gzip) } finally { $gzip.Dispose() }
        } finally { $output.Dispose() }
    } finally { $input.Dispose() }
    $file = Get-Item -LiteralPath $source
    $manifest[$item.Name] = [ordered]@{bytes=$file.Length; sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$manifest | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $assets 'manifest.json') -Encoding utf8NoBOM
