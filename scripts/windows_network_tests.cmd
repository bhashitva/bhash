@echo off
rem SPDX-License-Identifier: Apache-2.0
rem Invoked only inside the disposable, offline Windows native CI container.
if not "%BHASH_HTTP_CI_CONTAINER%"=="true" exit /b 2
rem The CurrentUser Root store can prompt interactively even with -f.
rem ContainerAdministrator installs this fixture only in the disposable machine store.
echo Installing public HTTP fixture CA in the disposable container machine store.
certutil.exe -addstore -f Root C:\repo\src\test\resources\network\ca.pem
if errorlevel 1 exit /b 1
echo HTTP fixture CA installed; starting bounded transport tests.
set BHASH_HTTP_WINDOWS_TRUSTED=true
call C:\maven\bin\mvn.cmd %*
exit /b %ERRORLEVEL%
