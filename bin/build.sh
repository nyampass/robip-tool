#!/bin/bash

JARNAME=robip-tool.jar

echo "Cleaning build ..."
lein clean
if [ -f $JARNAME ]; then 
  rm $JARNAME
fi

echo "Starting build ..."
lein uberjar

mv target/robip-tool-*-standalone.jar $JARNAME
echo "Moved standalone JAR to ./$JARNAME"
