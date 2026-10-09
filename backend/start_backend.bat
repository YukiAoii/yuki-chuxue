@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
title Yuki 初雪 - 后端一键启动

REM =========================================================================
REM  backend backend launcher v4
REM  Full story: see README-运维.md   (keep this file's REM lines ASCII/short:
REM  cmd.exe mis-parses long non-ASCII REM lines in a UTF-8 .bat)
REM =========================================================================

REM ---------- tunables ----------
set "PORT=11446"
set "HOST=127.0.0.1"
set "PUBLIC=https://your-server.example.com/"
set "READY_TRIES=20"
set "READY_WAIT=2"

REM ---------- use System32 tools by ABSOLUTE path ----------
REM (an unattended run must not depend on PATH: a stray MSYS/coreutils
REM  `timeout` earlier shadowed Windows' one and broke the wait loop)
set "SYS=%SystemRoot%\System32"

set "QUIET="
if /i "%~1"=="/quiet" set "QUIET=1"

set "DIR=%~dp0"
if "%DIR:~-1%"=="\" set "DIR=%DIR:~0,-1%"
set "LOGDIR=%DIR%\logs"
set "RUNDIR=%DIR%\run"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
if not exist "%RUNDIR%" mkdir "%RUNDIR%"

REM ---------- resolve python as an ABSOLUTE path ----------
REM (the watchdog runs as SYSTEM: no user PATH, and `py` may not exist there)
set "PYEXE="
for /f "delims=" %%P in ('%SYS%\where.exe python 2^>nul') do if not defined PYEXE set "PYEXE=%%P"
if not defined PYEXE if exist "%LOCALAPPDATA%\Programs\Python\Python314\python.exe" set "PYEXE=%LOCALAPPDATA%\Programs\Python\Python314\python.exe"
if not defined PYEXE if exist "C:\Users\<用户名>\AppData\Local\Programs\Python\Python314\python.exe" set "PYEXE=C:\Users\<用户名>\AppData\Local\Programs\Python\Python314\python.exe"
if not defined PYEXE (
    echo [错误] 没找到 python.exe。
    echo        请安装 Python 3.10+，或改本脚本里的 python 绝对路径。
    if not defined QUIET pause
    exit /b 1
)
echo %PYEXE%> "%RUNDIR%\python-path.txt"

if not exist "%DIR%\main.py" (
    echo [错误] 没找到后端源码：%DIR%\main.py
    if not defined QUIET pause
    exit /b 1
)

if not defined QUIET (
    echo ============================================================
    echo    Yuki 初雪 · 后端
    echo ------------------------------------------------------------
    echo    Python ： %PYEXE%
    echo    监听   ： %HOST%:%PORT%   ^(只对本机，外网走 Nginx^)
    echo    外网   ： %PUBLIC%
    echo    源码   ： %DIR%
    echo ============================================================
    echo.
)

REM ---------- 1) already healthy? do nothing ----------
call :probe
if "!HEALTH!"=="200" (
    echo [跳过] 后端已在运行（127.0.0.1:%PORT%/health = 200），不重复启动。
    goto :done_ok
)

REM ---------- 2) free the port ----------
call :kill_port
if exist "%RUNDIR%\backend.pid" (
    set "OLDPID="
    set /p OLDPID=<"%RUNDIR%\backend.pid"
    if defined OLDPID (
        %SYS%\tasklist.exe /fi "PID eq !OLDPID!" 2>nul | %SYS%\findstr.exe /c:"!OLDPID!" >nul
        if not errorlevel 1 (
            echo [清场] 结束上次记录的进程 PID !OLDPID!
            %SYS%\taskkill.exe /f /pid !OLDPID! >nul 2>nul
        )
    )
    del /q "%RUNDIR%\backend.pid" >nul 2>nul
)

REM ---------- 3) rotate logs (keep the previous run for post-mortem) ----------
if exist "%LOGDIR%\backend.out.log" move /y "%LOGDIR%\backend.out.log" "%LOGDIR%\backend.prev.out.log" >nul 2>nul
if exist "%LOGDIR%\backend.err.log" move /y "%LOGDIR%\backend.err.log" "%LOGDIR%\backend.prev.err.log" >nul 2>nul

REM ---------- 4) start detached (closing this window does NOT stop it) ----------
echo [启动] 正在拉起 uvicorn（后台运行）...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Start-Process -FilePath '%PYEXE%' -ArgumentList '-m','uvicorn','main:app','--host','%HOST%','--port','%PORT%' -WorkingDirectory '%DIR%' -RedirectStandardOutput '%LOGDIR%\backend.out.log' -RedirectStandardError '%LOGDIR%\backend.err.log' -WindowStyle Hidden -PassThru; $p.Id | Out-File -Encoding ascii '%RUNDIR%\backend.pid'"

REM ---------- 5) wait for readiness ----------
set /a N=0
:waitloop
set /a N+=1
call :sleep %READY_WAIT%
call :probe
if "!HEALTH!"=="200" goto :up
if !N! LSS %READY_TRIES! goto :waitloop

echo.
echo [失败] 健康检查没通过（/health 不是 200）。错误日志最后几行：
echo ------------------------------------------------------------
if exist "%LOGDIR%\backend.err.log" powershell -NoProfile -Command "Get-Content '%LOGDIR%\backend.err.log' -Tail 15" 2>nul
echo ------------------------------------------------------------
echo        也可运行 status.bat 看整体状态。
if not defined QUIET pause
exit /b 1

:up
echo [成功] 后端已监听 %HOST%:%PORT%（/health = 200）。
echo        外网： %PUBLIC%
echo        停止： stop_backend.bat      状态： status.bat
echo        自动守护：install_watchdog.bat  ^(只需装一次^)
:done_ok
if not defined QUIET pause
exit /b 0

REM ============================ helpers ============================

:probe
set "HEALTH=000"
for /f "delims=" %%C in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "try { (Invoke-WebRequest -UseBasicParsing -TimeoutSec 4 'http://%HOST%:%PORT%/health').StatusCode } catch { 000 }" 2^>nul') do set "HEALTH=%%C"
exit /b 0

:sleep
REM sleeps about %1 seconds.
REM NOTE: do NOT use timeout.exe here -- it refuses to run when stdin is
REM redirected ("Input redirection is not supported"), and the watchdog runs
REM unattended. ping works with no console and without a usable PATH.
set /a "PINGS=%~1+1"
%SYS%\ping.exe -n %PINGS% 127.0.0.1 >nul 2>nul
exit /b 0

:kill_port
for /f "tokens=5" %%P in ('%SYS%\netstat.exe -ano ^| %SYS%\findstr.exe /c:":%PORT% " ^| %SYS%\findstr.exe /c:"LISTENING"') do (
    echo [清场] 端口 %PORT% 被 PID %%P 占用，正在结束它...
    %SYS%\taskkill.exe /f /pid %%P >nul 2>nul
    if errorlevel 1 (
        echo [警告] 结束 PID %%P 失败 —— 多半是权限不够，请用管理员身份重跑。
    ) else (
        echo [清场] 已结束 PID %%P
    )
)
exit /b 0
