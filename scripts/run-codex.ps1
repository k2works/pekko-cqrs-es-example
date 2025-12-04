$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir

$env:CODEX_HOME = Join-Path $RepoRoot ".codex"
& codex --dangerously-bypass-approvals-and-sandbox @args
