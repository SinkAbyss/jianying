param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string[]]$Paths = @(),
    [switch]$IncludeHistory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $RepoRoot).Path
if ($Paths.Count -eq 0) {
    $Paths = @('AGENTS.md', 'docs/README.md', 'docs/开发约定.md',
        'docs/当前/当前进度与边框验收.md', 'docs/参考/证据索引.md', 'docs/交付', 'android/README.md')
    if ($IncludeHistory) { $Paths = @('AGENTS.md', 'docs', 'android/README.md', 'artifacts') }
}
$files = @($Paths | ForEach-Object {
    $candidate = Join-Path $root $_
    if (-not (Test-Path -LiteralPath $candidate)) { throw "检查入口不存在：$_" }
    $item = Get-Item -LiteralPath $candidate
    if ($item.PSIsContainer) {
        Get-ChildItem -LiteralPath $candidate -Recurse -File -Filter *.md |
            Where-Object { $_.FullName -notmatch '[\\/](workspace|source-before|source-final|接手基线|build|\.git|node_modules)[\\/]' }
    } else { $item }
} | Sort-Object FullName -Unique)
$problems = @()
$links = 0
foreach ($file in $files) {
    $fence = $null
    $lines = @(Get-Content -LiteralPath $file.FullName -Encoding UTF8)
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match '^\s*(`{3,}|~{3,})') {
            $marker = $Matches[1].Substring(0, 1)
            if ($null -eq $fence) { $fence = $marker } elseif ($fence -eq $marker) { $fence = $null }
            continue
        }
        if ($null -ne $fence) { continue }
        foreach ($match in [regex]::Matches($line, '!?\[[^\]]*\]\(\s*(?:<(?<target>[^>]+)>|(?<target>[^\s)]+))(?:\s+"[^"]*")?\s*\)')) {
            $target = $match.Groups['target'].Value
            if ($target -match '^(https?|mailto|tel|data|app|codex|plugin)://' -or $target -match '^(mailto|tel|data):' -or $target.StartsWith('#')) { continue }
            $path = [uri]::UnescapeDataString(($target -split '#', 2)[0])
            if ([string]::IsNullOrWhiteSpace($path)) { continue }
            $links++
            if (-not [IO.Path]::IsPathRooted($path)) { $path = Join-Path $file.DirectoryName $path }
            if (-not (Test-Path -LiteralPath $path)) {
                $problems += '{0}:{1} -> {2}' -f $file.FullName.Substring($root.Length + 1), ($i + 1), $target
            }
        }
    }
}
Write-Output "文档 $($files.Count)；本地内联链接 $links；不存在 $($problems.Count)。未检查锚点、引用式链接及外网。"
$problems | ForEach-Object { Write-Output $_ }
if ($problems.Count -gt 0) { exit 1 }
