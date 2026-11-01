@echo off
rem SPDX-License-Identifier: Apache-2.0
rem Invoked only inside the disposable, offline Windows native CI container.
if not "%BHASH_HTTP_CI_CONTAINER%"=="true" exit /b 2
certutil.exe -user -addstore -f Root C:\repo\src\test\resources\network\ca.pem >nul
if errorlevel 1 exit /b 1
set BHASH_HTTP_WINDOWS_TRUSTED=true
call C:\maven\bin\mvn.cmd %*
exit /b %ERRORLEVEL%
