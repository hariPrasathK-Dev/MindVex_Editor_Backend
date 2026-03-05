@echo off
setlocal
set DATABASE_URL=jdbc:postgresql://crossover.proxy.rlwy.net:47666/railway?sslmode=disable^&user=postgres^&password=wao6iCL1WFwCKPhJ1Usfw0QzTtJnPsGN
set JAVA_HOME=C:\Program Files\Java\jdk-21
set CP=target\classes;%USERPROFILE%\.m2\repository\org\postgresql\postgresql\42.7.9\postgresql-42.7.9.jar

cd /d "%~dp0"

echo ╔════════════════════════════════════════╗
echo ║     Database Quick Status Check        ║
echo ╚════════════════════════════════════════╝
echo.

"%JAVA_HOME%\bin\java.exe" -cp "%CP%" ai.mindvex.backend.util.QuickCheck

echo.
pause
