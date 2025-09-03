#!/bin/bash
VERSION=$1

while [[ -z $VERSION ]]; do
	echo "Specify version string:"
	read VERSION
done

echo Building JAR...

cd ..
./gradlew jar
cd release/
echo

JAR=../build/libs/CircuitSim.jar
NAME=CircuitSim$VERSION
OUT=out

rm -rf $OUT
mkdir $OUT
rm build.log

# JAR
echo Copying JAR...
cp $JAR $OUT

# Linux
echo Creating Linux release...
awk BINMODE=1 linux_stub.sh > $OUT/$NAME
cat $JAR >> $OUT/$NAME

# Mac
echo Creating Mac release
python jar2app.py $JAR $OUT/$NAME -n "CircuitSim $VERSION" -i icon.icns -b com.ra4king.circuitsim -j "-Xmx250M" -v $VERSION -s $VERSION >> build.log
cd $OUT
tar -a -c -f $NAME-mac.zip $NAME.app && rm -rf $NAME.app
cd ..

# Windows
echo Creating Windows release
"$JAVA_HOME/bin/jpackage" @circuitsim-windows-jpackage.txt --app-version $VERSION
mv "$OUT/CircuitSim-$VERSION.exe" "$OUT/$NAME.exe"