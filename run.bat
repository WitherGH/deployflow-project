@echo off
cd /d %~dp0
if not exist out mkdir out
dir /s /b src\*.java > sources.txt
javac -d out @sources.txt
java -cp out com.deployflow.web.DeployFlowApp 8080
