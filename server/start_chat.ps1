# Chathyx 聊天服务器启动脚本
# 数据库密码等配置已放在同目录 .env 文件中，程序启动时自动读取，无需在此设置。
# 用法：右键“使用 PowerShell 运行”，或在 PowerShell 中执行： .\start_chat.ps1
Set-Location -Path $PSScriptRoot
python chat_server.py
