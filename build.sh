#!/bin/bash

# ShellBot PTY Kotlin - Build Script

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN_CLASS="com.shellbot.ShellBotMain"

echo "Building ShellBot PTY Kotlin..."
echo "Project directory: $PROJECT_DIR"

# Check for Maven
if ! command -v mvn &> /dev/null; then
    echo "Error: Maven is required but not installed."
    echo "Install with: brew install maven (macOS) or apt-get install maven (Ubuntu)"
    exit 1
fi

# Check for Kotlin compiler (optional)
if command -v kotlinc &> /dev/null; then
    echo "Kotlin compiler detected: $(kotlinc -version 2>&1 | head -1)"
fi

# Build with Maven
echo ""
echo "Running Maven build..."
cd "$PROJECT_DIR"
mvn clean compile

# Create executable JAR
echo ""
echo "Creating executable JAR..."
mvn package

# Find the JAR file
JAR_FILE=$(find "$PROJECT_DIR/target" -name "*.jar" ! -name "*sources*" ! -name "*tests*" | head -1)

if [ -f "$JAR_FILE" ]; then
    echo ""
    echo "Build successful!"
    echo "JAR file: $JAR_FILE"
    echo ""
    echo "Usage examples:"
    echo "  java -jar $JAR_FILE -c \"echo Hello World\""
    echo "  java -jar $JAR_FILE -c \"python3 script.py\" -v"
    echo ""
    echo "To install globally:"
    echo "  sudo cp $JAR_FILE /usr/local/bin/shellbot.jar"
    echo "  echo '#!/bin/bash' > /usr/local/bin/shellbot"
    echo "  echo 'java -jar /usr/local/bin/shellbot.jar \"\$@\"' >> /usr/local/bin/shellbot"
    echo "  chmod +x /usr/local/bin/shellbot"
else
    echo "Error: JAR file not found!"
    exit 1
fi