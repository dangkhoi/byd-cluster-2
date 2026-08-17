@echo off
REM ==========================================================================
REM  LAY LOG LAI THU ClusterNav 2.0 (Windows)  --  cho nguoi KHONG ranh IT
REM  Cach dung: double-click file nay (xe noi bang cap USB)
REM         hoac mo cmd:  pull-drive-logs.bat 192.168.1.50  (xe cung WiFi, thay IP)
REM  Log se duoc keo ve mot thu muc tren Desktop, roi tu mo ra.
REM  (Chi tiet cai dat xem docs\HUONG-DAN-LAY-LOG.md)
REM ==========================================================================
setlocal
set PKG=com.byd.clusternav2
set SRC=/sdcard/Android/data/%PKG%/files
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value ^| find "="') do set DT=%%I
set STAMP=%DT:~0,8%-%DT:~8,6%
set DEST=%USERPROFILE%\Desktop\clusternav-logs-%STAMP%

echo == ClusterNav 2.0 -- lay log lai thu ==

where adb >nul 2>nul
if errorlevel 1 (
  echo [X] Chua co 'adb' tren may. Mo docs\HUONG-DAN-LAY-LOG.md, lam 'BUOC 1 -- Cai adb' roi chay lai.
  pause & exit /b 1
)

if not "%~1"=="" (
  echo -^> Noi toi xe qua WiFi: %~1:5555
  adb connect %~1:5555
)

adb get-state >nul 2>nul
if errorlevel 1 (
  echo [X] Khong thay xe. Kiem tra: cap USB da cam ^(va bam 'Allow' tren man xe^), HOAC nhap dung IP WiFi.
  pause & exit /b 1
)

mkdir "%DEST%" 2>nul
echo -^> Dang lay log ve: %DEST%
adb pull %SRC% "%DEST%"
if errorlevel 1 (
  echo [X] Khong lay duoc log. Thuong do: app CHUA bat 'Nhat ky chi tiet' ^(nhan-giu dong phien ban
  echo     tren man hinh chinh toi khi hien 'Nhat ky chi tiet: BAT'^), hoac chua lai nen chua co log.
  pause & exit /b 1
)
echo [OK] XONG. Log nam o: %DEST%
echo     Gom: nav_notif_*.csv, nav_log_*.csv, nav_arrow_log_*.csv + nav_arrow_pngs_*, diag\ (anh GMaps+cum).
explorer "%DEST%"
pause
endlocal
