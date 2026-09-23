@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
REM ---------------------------------------------------------------------------
REM 一键启动：MySQL 8 + Redis 7 + 后端 + 前端(Nginx)
REM
REM 用法：双击本文件，或命令行运行 start.bat
REM 首次启动会自动生成 .env（随机密钥）+ 构建镜像 + 拉起
REM 启动完成后浏览器打开 http://localhost:8088
REM ---------------------------------------------------------------------------

cd /d "%~dp0"

REM ---------- 1. 准备 .env（委托给 init-env.ps1，幂等：已有 .env 则跳过） ----------
if exist .env (
  echo ==^> 使用已有 .env
) else (
  echo ==^> 未找到 .env，正在生成（随机 JWT_SECRET + 库密码）...
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0init-env.ps1"
  if errorlevel 1 (
    echo 错误：.env 生成失败，请手动执行 copy .env.example .env 并填写 JWT_SECRET / MYSQL_ROOT_PASSWORD。
    pause
    exit /b 1
  )
)

REM ---------- 2. 检查 Docker ----------
where docker >nul 2>nul
if errorlevel 1 (
  echo 错误：未检测到 docker，请先安装 Docker Desktop。
  pause
  exit /b 1
)
docker compose version >nul 2>nul
if errorlevel 1 (
  echo 错误：未检测到 docker compose 插件。
  pause
  exit /b 1
)

REM ---------- 3. 拉起服务 ----------
echo ==^> 启动服务 ...
docker compose up -d --build
if errorlevel 1 (
  echo.
  echo 错误：docker compose up 失败，请查看上方日志。
  pause
  exit /b 1
)

REM ---------- 4. 健康检查 ----------
echo.
echo ==^> 等待服务就绪 ...
set "ready=0"
for /l %%i in (1,1,40) do (
  powershell -NoProfile -Command "try { (Invoke-WebRequest -Uri http://localhost:8080/actuator/health -UseBasicParsing -TimeoutSec 3).StatusCode | Out-Null; exit 0 } catch { exit 1 }" >nul 2>nul
  if !errorlevel!==0 (
    set "ready=1"
    goto :done
  )
  timeout /t 3 /nobreak >nul
)
:done

echo.
echo ==================================================
if "!ready!"=="1" (
  echo   启动成功！
  echo   前端入口：http://localhost:8088
  echo   后端健康：http://localhost:8080/actuator/health
  echo   停止服务：stop.bat
) else (
  echo   容器已启动，但后端健康检查尚未通过。
  echo   请查看日志：docker compose logs -f backend
)
echo ==================================================
echo.
pause
