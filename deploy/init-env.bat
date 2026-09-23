@echo off
chcp 65001 >nul
REM ---------------------------------------------------------------------------
REM 单独生成 .env（不启动服务）
REM
REM 双击本文件，或命令行运行，即可从 .env.example 生成带随机密钥的 .env。
REM 已有 .env 时不会覆盖（幂等）。
REM ---------------------------------------------------------------------------
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0init-env.ps1"
echo.
pause
