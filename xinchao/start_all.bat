@echo off
chcp 65001 >nul
REM Yuki xinchao integration - restart both services (kill by port, then start)
REM Registered as scheduled task "XinchaoServices"; also runnable by hand.
REM Idempotent: works whether services are dead or alive.

REM --- kill old instances (LISTENING rows only -> service PIDs) ---
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":18110" ^| findstr "LISTENING"') do taskkill /f /pid %%p >nul 2>nul
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":18001" ^| findstr "LISTENING"') do taskkill /f /pid %%p >nul 2>nul
timeout /t 2 /nobreak >nul

REM --- start both (own windows; hidden when run as SYSTEM task) ---
start "xinchao-ob" /min cmd /c "C:\Xinchao\start_ob.bat"
start "xinchao-mind" /min cmd /c "C:\Xinchao\start_xinchao.bat"
