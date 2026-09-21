@echo off
title Celica Dash setup
cd /d "%~dp0"
echo.
echo   Celica Dash setup
echo   -----------------
echo   Starting the helper; your browser opens on the wizard.
echo   Keep this window open while the head unit fetches. Close it when you are done.
echo.

set PY=
where py >nul 2>nul && (py -3 -c "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)" >nul 2>nul && set "PY=py -3")
if "%PY%"=="" (where python >nul 2>nul && (python -c "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)" >nul 2>nul && set "PY=python"))
if not "%PY%"=="" goto run

echo   Python 3 is needed and was not found. Trying to install it (winget)...
echo   If Windows asks, say yes. This is a one-off.
where winget >nul 2>nul && winget install -e --id Python.Python.3.12 --accept-package-agreements --accept-source-agreements --silent
if errorlevel 1 (
  echo.
  echo   Could not install automatically. Please install Python 3 from https://www.python.org/downloads/
  echo   - tick "Add python.exe to PATH" - then run this again.
  start https://www.python.org/downloads/
  pause
  exit /b 1
)
set "PATH=%LOCALAPPDATA%\Programs\Python\Python312;%LOCALAPPDATA%\Programs\Python\Python312\Scripts;%PATH%"
set "PY=python"

:run
%PY% "setup\helper.py"
if errorlevel 1 (
  echo.
  echo   The helper stopped with an error. The text above says why.
  pause
)
