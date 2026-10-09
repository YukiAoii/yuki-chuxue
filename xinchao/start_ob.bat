@echo off
chcp 65001 >nul
REM Yuki xinchao integration - start Ombre Brain (GENERATED - contains key, keep private)
cd /d C:\Xinchao\ombre-brain
set OMBRE_BIND_HOST=127.0.0.1
set OMBRE_COMPRESS_API_KEY=<你的模型服务密钥>2aZbLWKNwpu
set OMBRE_COMPRESS_BASE_URL=https://api.your-llm-provider.example.com:18443/v1
set OMBRE_COMPRESS_MODEL=deepseek-flash
set OMBRE_EMBED_API_KEY=<你的模型服务密钥>
".venv\Scripts\python.exe" src\server.py
