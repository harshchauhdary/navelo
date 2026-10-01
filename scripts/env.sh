# Source from the repository root: . scripts/env.sh
if [ -d "$PWD/.tools/jdk-17.0.20.1+1/Contents/Home" ]; then
  export JAVA_HOME="$PWD/.tools/jdk-17.0.20.1+1/Contents/Home"
fi
export ANDROID_HOME="$PWD/.tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.tools/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
