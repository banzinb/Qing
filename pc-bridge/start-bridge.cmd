@echo off
cd /d %~dp0
echo Starting Aether PC Bridge on port 8899...
node server.mjs --port 8899
pause
