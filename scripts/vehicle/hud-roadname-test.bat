@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
title Test ban TEN DUONG len HUD - ClusterNav (chay khi DO XE)

echo ==================================================================
echo   TEST BAN TEN DUONG LEN HUD  (ClusterNav)
echo   Xe cua ban da hien MUI TEN + KHOANG CACH tren HUD roi.
echo   Script thu NHIEU KIEU MA HOA ten duong de tim cai HIEN duoc.
echo   Sau moi kieu: doc check-state (420A1010) + hoi ban NHIN KINH.
echo.
echo   *** CHAY KHI DO XE. Trong luc test hay TAT dan duong tren app
echo       (de app khong ghi de). Script tu ban nav gia + don dep cuoi.
echo ==================================================================
echo.

REM --- navopen.jar phai nam CANH file .bat nay ---
set "NAVJAR=%~dp0navopen.jar"
if not exist "%NAVJAR%" ( echo [LOI] Thieu navopen.jar canh file .bat. & pause & exit /b 1 )

REM --- tim adb.exe (PATH -^> canh .bat -^> thu muc thuong gap -^> keo-tha) ---
set "ADB="
where adb >nul 2>nul && set "ADB=adb"
if not defined ADB if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"
if not defined ADB if exist "%~dp0platform-tools\adb.exe" set "ADB=%~dp0platform-tools\adb.exe"
if not defined ADB for %%P in (
  "%USERPROFILE%\Desktop\platform-tools\adb.exe"
  "%USERPROFILE%\Downloads\platform-tools\adb.exe"
  "C:\platform-tools\adb.exe" "D:\platform-tools\adb.exe" "D:\clusternav\platform-tools\adb.exe"
) do if exist "%%~P" set "ADB=%%~P"
if not defined ADB (
  echo Khong tu tim thay adb.exe. KEO-THA adb.exe vao cua so nay roi Enter.
  set /p "ADB=Duong dan adb.exe: "
)
set "ADB=%ADB:"=%"

echo.
set /p "IP=IP xe (WiFi, vd 192.168.1.50) - de TRONG neu dung CAP USB: "
set "TGT=-d"
if not "%IP%"=="" ( "%ADB%" connect %IP%:5555 >nul & set "TGT=-s %IP%:5555" )

set "LOG=%~dp0hud-roadname-test.txt"
set "TMPF=%TEMP%\navout_%RANDOM%.txt"
set "NAVCMD=CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen"

echo === push navopen ===
"%ADB%" %TGT% push "%NAVJAR%" /data/local/tmp/navopen.jar >nul 2>&1 && echo   ok || ( echo   [LOI] khong push duoc - kiem tra ket noi xe & pause & exit /b 1 )

echo ### TEST TEN DUONG HUD  %date% %time%> "%LOG%"

echo.
echo -- Doc context truoc (khong ghi):
call :run getraw instr 420A1010
call :run getraw instr 38B00030
echo.
echo Xac nhan: xe DANG DO + da TAT dan duong tren app. Bam Enter de bat dau test.
pause >nul

REM ===== cac bien the ma hoa ten duong (hex = UTF-16LE) =====
call :variant "A_ASCII (Le Loi)"          4c00650020004c006f006900
call :variant "B_VN-co-dau (Le Loi)"      4c00ea0020004c00e31e6900
call :variant "C_CJK-showroom (WuYiDaDaoNan)" 944e004e275953905753
call :variant "D_dau-cach-dan ( Le Loi)"  20004c00650020004c006f006900
call :variant "E_NUL-cuoi (Le Loi\0)"     4c00650020004c006f0069000000
call :variant "F_ngan (Kim)"              4b0069006d00

echo.
echo === DON DEP (ket thuc nav gia) ===
call :run navistate 4

echo.
echo ==================================================================
echo XONG. Gui lai file:  %LOG%
echo   Ghi ro: bien the nao (A/B/C/D/E/F) lam TEN DUONG HIEN tren HUD,
echo   va check-state 420A1010 = 1 (VALID) hay 2 (INVALID) o moi bien the.
echo   (Mui ten + khoang cach nen van hien suot - do la doi chung.)
echo ==================================================================
pause >nul
del "%TMPF%" >nul 2>&1
exit /b 0

REM ---------- ham ----------
:variant
echo.
echo ================= BIEN THE %~1 =================
echo.>> "%LOG%"
echo ================= %~1 =================>> "%LOG%"
REM ban 1 khung nav (mui ten thang 20 + 250m + ten placeholder), roi ghi de ten duong bang bien the
call :run frame 20 250 RD 8 5000
call :run setbytes instr 43FA1008 %~2
call :run getraw instr 420A1010
call :run getbytes instr 43FA1008
echo.
echo   ^>^>^> NHIN KINH LAI: ten duong co HIEN khong? Noi dung gi?
set "OBS="
set /p "OBS=   Ghi quan sat (vd: hien-dung / khong-hien / loi-font / hien-sai): "
echo    [quan sat %~1]: !OBS!>> "%LOG%"
goto :eof

:run
"%ADB%" %TGT% shell "%NAVCMD% %*" > "%TMPF%" 2>&1
type "%TMPF%"
type "%TMPF%">> "%LOG%"
goto :eof
