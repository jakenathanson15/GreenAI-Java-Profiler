@echo off
echo Setting up for universal energy profiling...

REM Create directories
mkdir energy_results 2>NUL

REM Create improved JFR config
echo ^<?xml version="1.0" encoding="UTF-8"?^> > high-freq-jfr.jfc
echo ^<configuration version="2.0"^> >> high-freq-jfr.jfc
echo   ^<event name="jdk.ExecutionSample"^> >> high-freq-jfr.jfc
echo     ^<setting name="enabled"^>true^</setting^> >> high-freq-jfr.jfc
echo     ^<setting name="period"^>2 ms^</setting^> >> high-freq-jfr.jfc
echo     ^<setting name="stackDepth"^>64^</setting^> >> high-freq-jfr.jfc
echo   ^</event^> >> high-freq-jfr.jfc
echo ^</configuration^> >> high-freq-jfr.jfc

echo Compiling Java files...
javac -d out EnergyAttribution.java

echo Running Java with JFR recording on Core 0...
cmd /c start /AFFINITY 1 /HIGH /WAIT java -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:-Inline -XX:StartFlightRecording:settings=high-freq-jfr.jfc,name=prof,filename=energy_results\profile.jfr,duration=40s -cp out demo.Top10Load

echo Running Power Gadget with MATCHING duration...
"C:\Program Files\Intel\Power Gadget 3.6\PowerLog3.0.exe" -duration 40 -resolution 20 -file energy_results\power.csv

echo Analyzing energy data...
java -cp out demo.EnergyAttribution energy_results\profile.jfr energy_results\power.csv 10 --core 0

echo.
echo Press any key to exit...
pause > nul