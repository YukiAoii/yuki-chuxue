@echo off
chcp 65001 >nul
setlocal
title Yuki 初雪 - 同步后端源码到部署目录

REM =============================================================================
REM  为什么要有这个脚本
REM -----------------------------------------------------------------------------
REM  源码在仓库里：  项目1\Yuki初雪\backend\      ← 版本控制、可回溯、可 diff
REM  运行在独立目录：桌面\YukiServer\            ← 不受「项目1」里其他项目增删影响
REM
REM  两边分开是有意的：后端站点与同机其他项目没有从属关系，
REM  它的运行目录不该埋在别人的项目树里。
REM
REM  每次改完后端源码，跑一次这个脚本把两边对齐。
REM
REM  ⚠️ **不会**动部署目录里的 data\（数据库在那里，不是源码）。
REM =============================================================================

set "SRC=%~dp0.."
set "DST=<部署目录，例：D:\YukiServer>"

if not exist "%SRC%\main.py" (
    echo [错误] 找不到源码：%SRC%\main.py
    pause
    exit /b 1
)
if not exist "%DST%" (
    echo [错误] 部署目录不存在：%DST%
    echo        请先确认后端已经部署到上面的部署目录。
    pause
    exit /b 1
)

echo 源码：  %SRC%
echo 部署：  %DST%
echo.

for %%f in (main.py e2e_check.py test_backend.py requirements.txt start_backend.bat) do (
    copy /Y "%SRC%\%%f" "%DST%\%%f" >nul && echo   [ok] %%f
)

robocopy "%SRC%\static" "%DST%\static" /E /NFL /NDL /NJH /NJS /NP >nul
echo   [ok] static\  （后台网页）
robocopy "%SRC%\deploy" "%DST%\deploy" /E /NFL /NDL /NJH /NJS /NP >nul
echo   [ok] deploy\  （Nginx 配置留档）

echo.
echo 同步完成。data\ 未动，管理员密码不变。
echo 若后端正在运行，需要重启它才会加载新代码。
pause
