@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
title HUD TEN DUONG v3 - config-gate + display-content + read-map (LOG rc moi write)

echo ==================================================================
echo   HUD TEN DUONG v3  (ClusterNav)
echo   v1/v2/matrix: 7 lever + combo NEGATIVE, check-state 0x420A1010 ket 0,
echo   0x38B0002E/0x30100030 = not-provisioned. v3 dao them:
echo     * READ-MAP full ho HUD-nav config/status (xem cai gi provisioned)
echo     * N1 = GHI CONFIG GATE 0x38B00030 (matrix chi ghi 0x32B1102E, KHAC)
echo     * N2 = DISPLAY_CONTENT config 0x38B00042 (chon noi dung hien)
echo     * N3 = CP_MAP_NAVIGATION_TIPS 0x40C0B026 (field text)
echo     * N4 = OVERSEA pathname 0x1F7A1008 + guide 0x1F701010 (export SL6)
echo     * N6 = chuoi STRICT theo thu tu (config to navi to dest to guide to pathname)
echo   *** MOI WRITE DEU LOG rc (khac matrix che >nul). DO XE, TAT Nav+HUD app.
echo ==================================================================
echo.

set "NAVJAR=%~dp0navopen.jar"
if not exist "%NAVJAR%" ( echo [LOI] thieu navopen.jar & pause & exit /b 1 )
set "ADB="
where adb >nul 2>nul && set "ADB=adb"
if not defined ADB if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"
if not defined ADB if exist "%~dp0platform-tools\adb.exe" set "ADB=%~dp0platform-tools\adb.exe"
if not defined ADB for %%P in ("%USERPROFILE%\Desktop\platform-tools\adb.exe" "%USERPROFILE%\Downloads\platform-tools\adb.exe" "C:\platform-tools\adb.exe") do if exist "%%~P" set "ADB=%%~P"
if not defined ADB ( echo KEO-THA adb.exe vao day roi Enter. & set /p "ADB=Duong dan adb.exe: " )
set "ADB=%ADB:"=%"

set /p "IP=IP xe (WiFi) - de TRONG neu USB: "
set "TGT=-d"
if not "%IP%"=="" ( "%ADB%" connect %IP%:5555 >nul & set "TGT=-s %IP%:5555" )

set "LOG=%~dp0hud-roadname-v3.txt"
set "TMPF=%TEMP%\navv3_%RANDOM%.txt"
set "NAVCMD=CLASSPATH=/data/local/tmp/navopen.jar app_process /system/bin com.byd.navopen.NavOpen"
REM CJK control = WuYiDaDaoNan (chuoi showroom), UTF-16LE hex:
set "ROAD=944e004e275953905753"

"%ADB%" %TGT% push "%NAVJAR%" /data/local/tmp/navopen.jar >nul 2>&1 && echo -- navopen pushed || ( echo [LOI] khong push duoc & pause & exit /b 1 )
echo ### HUD TEN DUONG v3  %date% %time%> "%LOG%"

echo. & echo Xac nhan xe DANG DO + TAT Nav+HUD tren app. Enter de bat dau.
pause >nul

echo === PRIME: navistate 2 + nav-screen 3 ===
call :run navistate 2
call :run setraw setting 4C10E015 3

REM ============ BASELINE READ-MAP (provisioning ho HUD-nav) ============
echo. & echo ================= BASELINE READ-MAP =================
echo ================= BASELINE READ-MAP =================>> "%LOG%"
echo   (gia tri -2147482648 = NOT-PROVISIONED; 0/khac = co that)
echo   (gia tri -2147482648 = NOT-PROVISIONED)>> "%LOG%"
call :rd 420A1010 "ROAD_NAME_CHECK_STATE (muc tieu: 0 to 1/2)"
call :rd 38B00030 "HUD_NAV_MAP_CONFIG (gate - doc hien tai)"
call :rd 30100030 "HUD_NAV_MAP_CONFIG_STATUS"
call :rd 38B0002E "HUD_NAV_MAP_STATUS"
call :rd 30100031 "NAV_MAP_RESOLUTION_STATUS"
call :rd 38B00042 "DISPLAY_CONTENT_FUNCTION_CONFIG"
call :rd 30100042 "DISPLAY_CONTENT_FUNCTION_CONFIG_STATUS"
call :rd 30100015 "HUD_CONFIG_STATUS"
call :rd 3010000D "HUD_MODE_FEEDBACK_STATUS"
call :rd 40C0103B "GET_NAVI_DESTINATION"

REM ============ N1: GHI CONFIG GATE 0x38B00030 ============
call :seqroad
echo. & echo ================= N1 WRITE CONFIG GATE 38B00030=1 =================
echo ================= N1 WRITE CONFIG GATE 38B00030=1 =================>> "%LOG%"
call :run setraw instr 38B00030 1
call :rd 38B00030 "readback CONFIG (co nhan 1 khong?)"
call :rd 30100030 "CONFIG_STATUS (con -2147482648?)"
call :rd 38B0002E "MAP_STATUS (con -2147482648?)"
call :seqroad
call :rd 420A1010 "check-state sau N1"
call :obs "N1 CONFIG GATE 38B00030=1"
call :run setraw instr 38B00030 0

REM ============ N2: DISPLAY_CONTENT_CONFIG 0x38B00042 = 1,2,3 ============
echo. & echo ================= N2 DISPLAY_CONTENT 38B00042 =================
echo ================= N2 DISPLAY_CONTENT 38B00042 =================>> "%LOG%"
for %%V in (1 2 3) do (
  call :run setraw instr 38B00042 %%V
  call :rd 30100042 "DC_STATUS sau =%%V"
  call :seqroad
  call :rd 420A1010 "check-state DC=%%V"
)
call :obs "N2 DISPLAY_CONTENT 38B00042 (1/2/3)"
call :run setraw instr 38B00042 0

REM ============ N3: CP_MAP_NAVIGATION_TIPS 0x40C0B026 (text) ============
echo. & echo ================= N3 CP_MAP_NAVIGATION_TIPS 40C0B026 =================
echo ================= N3 CP_MAP_NAVIGATION_TIPS 40C0B026 =================>> "%LOG%"
call :run navistate 2
call :run setbytes instr 40C0B026 %ROAD%
call :rd 420A1010 "check-state sau N3"
call :obs "N3 CP_MAP_NAVIGATION_TIPS (ten duong hien o dau khong?)"

REM ============ N4: OVERSEA pathname 0x1F7A1008 + guide 0x1F701010 ============
echo. & echo ================= N4 OVERSEA pathname 1F7A1008 =================
echo ================= N4 OVERSEA pathname 1F7A1008 =================>> "%LOG%"
call :run navistate 2
call :run setraw instr 1F701010 20
call :run setbytes instr 1F7A1008 %ROAD%
call :rd 420A1010 "check-state sau N4"
call :obs "N4 OVERSEA pathname+guide"

REM ============ N6: chuoi STRICT theo thu tu ============
echo. & echo ================= N6 STRICT SEQUENCE =================
echo ================= N6 STRICT SEQUENCE (config to navi to dest to guide to dist to pathname) =================>> "%LOG%"
call :run setraw instr 38B00030 1
call :run navistate 2
call :run setraw instr 43E00038 2
call :run setraw instr 43F01010 20
call :run setraw instr 43F01018 250
call :run setbytes instr 43FA1008 %ROAD%
call :rd 420A1010 "check-state sau N6"
call :obs "N6 STRICT SEQUENCE"
call :run setraw instr 38B00030 0
call :run setraw instr 43E00038 0

echo. & echo === DONE: navistate 4 (tat nav) ===
call :run navistate 4
echo. & echo === XONG. Gui lai file: %LOG% ===
echo Log: %LOG%
pause >nul
endlocal
exit /b 0

REM ===== helpers =====
REM :run <navopen args...> - chay + LOG dong ket qua (rc / gia tri), KHONG che
:run
"%ADB%" %TGT% shell "%NAVCMD% %*" > "%TMPF%" 2>&1
findstr /C:"-> rc=" /C:") = " "%TMPF%" >> "%LOG%"
findstr /C:"-> rc=" /C:") = " "%TMPF%"
goto :eof

REM :rd <hexid> <nhan> - doc 1 register instr + LOG kem nhan
:rd
echo   [%~2]>> "%LOG%"
echo   [%~2]
call :run getraw instr %~1
goto :eof

REM :seqroad - bom 1 nhip guidance + pathname (domestic) de co du lieu danh gia
:seqroad
call :run frame 20 250 RD 8 5000
call :run setbytes instr 43FA1008 %ROAD%
goto :eof

REM :obs <ten> - hoi quan sat + ghi log
:obs
echo.
set "OBS=" & set /p "OBS=   ten duong co HIEN khong? + ghi 420A1010 (vd 'khong-hien 420A=0' / 'CO-HIEN'): "
echo    [quan sat %~1]: !OBS!>> "%LOG%"
goto :eof
