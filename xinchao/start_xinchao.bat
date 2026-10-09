@echo off
chcp 65001 >nul
REM Yuki xinchao integration - start Xinchao (uses xinchao.conf)
cd /d C:\Xinchao\xinchao
node --env-file=xinchao.conf src\server.js
