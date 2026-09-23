# ---------------------------------------------------------------------------
# 生成 .env（一键启动的前置步骤，可独立调用）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File init-env.ps1
#   或双击 init-env.bat（已封装）
#
# 行为：
#   - 若 .env 已存在：不做任何改动，直接退出（幂等，不会覆盖你的手工配置）
#   - 若 .env 不存在：从 .env.example 复制，并把 JWT_SECRET 与
#     MYSQL_ROOT_PASSWORD 替换为随机值
# ---------------------------------------------------------------------------
$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

$envFile = Join-Path $PSScriptRoot '.env'
$example  = Join-Path $PSScriptRoot '.env.example'

if (Test-Path $envFile) {
    Write-Host '==> .env already exists, skip generation.' -ForegroundColor Yellow
    exit 0
}

if (-not (Test-Path $example)) {
    Write-Error 'Cannot find .env.example, unable to generate .env.'
    exit 1
}

# 随机值：JWT_SECRET = 2 个 GUID 拼 64 位 hex（32 字节，HMAC-SHA256 推荐长度）
#         MYSQL_ROOT_PASSWORD = 1 个 GUID 32 位 hex
$secret = -join ((1..2) | ForEach-Object { [guid]::NewGuid().ToString('N') })
$pwd    = [guid]::NewGuid().ToString('N')

# 逐行读取（自动处理编码），只替换「以 KEY= 开头」的行，避免正则跨行/锚点问题
$lines = [System.IO.File]::ReadAllLines($example, [System.Text.Encoding]::UTF8)
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    if ($line -match '^JWT_SECRET=') {
        $lines[$i] = 'JWT_SECRET=' + $secret
    }
    elseif ($line -match '^MYSQL_ROOT_PASSWORD=') {
        $lines[$i] = 'MYSQL_ROOT_PASSWORD=' + $pwd
    }
}

# 以 UTF-8 无 BOM 写回
[System.IO.File]::WriteAllLines($envFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

Write-Host '==> .env generated (random JWT_SECRET + MYSQL_ROOT_PASSWORD).' -ForegroundColor Green
