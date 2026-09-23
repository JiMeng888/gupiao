@echo off
setlocal
chcp 65001 >nul
REM ---------------------------------------------------------------------------
REM 一键停止服务（保留数据卷，数据不丢失）
REM
REM 用法：
REM   stop.bat            停止全部容器
REM   stop.bat --volumes  停止并删除数据卷（会清空数据库！慎用）
REM ---------------------------------------------------------------------------

cd /d "%~dp0"

if "%1"=="--volumes" (
  echo 警告：将删除数据卷 mysql-data / redis-data，数据库数据会清空！
  set /p ans=确认继续？(输入 yes 继续) 
  if /i not "!ans!"=="yes" (
    echo 已取消。
    goto :eof
  )
  docker compose down -v
  echo 已停止并删除数据卷。
) else (
  docker compose down
  echo 已停止服务（数据卷已保留）。
)

echo.
pause
