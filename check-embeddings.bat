@echo off
REM Quick script to check embeddings in Railway database
REM Usage: double-click this file

setlocal
set JAVA_HOME=C:\Program Files\Java\jdk-21
set CP=c:\Users\hp859\Desktop\IntelligentCodebaseAnalyser\MindVex_Editor_Backend\target\classes;%USERPROFILE%\.m2\repository\org\postgresql\postgresql\42.7.9\postgresql-42.7.9.jar

echo.
echo ============================================
echo   Checking Vector Embeddings
echo ============================================
echo.

"%JAVA_HOME%\bin\java.exe" -cp "%CP%" ai.mindvex.backend.util.QueryDatabase

pause
