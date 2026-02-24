#!/bin/bash

# ShellBot PTY Kotlin - Install Script

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/usr/local/bin"
JAR_NAME="shellbot.jar"
SCRIPT_NAME="shellbot"

echo "Installing ShellBot PTY Kotlin..."
echo "Project directory: $PROJECT_DIR"
echo "Install directory: $INSTALL_DIR"

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo "Please run as root or with sudo"
    exit 1
fi

# Build the project first
echo ""
echo "Building project..."
if ! "$PROJECT_DIR/build.sh"; then
    echo "Build failed. Please check errors above."
    exit 1
fi

# Find the JAR file
JAR_FILE=$(find "$PROJECT_DIR/target" -name "*.jar" ! -name "*sources*" ! -name "*tests*" | head -1)

if [ ! -f "$JAR_FILE" ]; then
    echo "Error: JAR file not found!"
    exit 1
fi

echo ""
echo "Found JAR: $JAR_FILE"

# Copy JAR to install directory
echo "Copying JAR to $INSTALL_DIR/$JAR_NAME..."
cp "$JAR_FILE" "$INSTALL_DIR/$JAR_NAME"

# Create wrapper script
echo "Creating wrapper script $INSTALL_DIR/$SCRIPT_NAME..."
cat > "$INSTALL_DIR/$SCRIPT_NAME" << EOF
#!/bin/bash
java -jar "$INSTALL_DIR/$JAR_NAME" "\$@"
EOF

# Make wrapper executable
chmod +x "$INSTALL_DIR/$SCRIPT_NAME"

echo ""
echo "Installation complete!"
echo ""
echo "Usage:"
echo "  $SCRIPT_NAME -c \"echo Hello World\""
echo "  $SCRIPT_NAME -c \"python3 script.py\" -v"
echo ""
echo "Test installation:"
echo "  $SCRIPT_NAME --help"
echo "  $SCRIPT_NAME -c \"echo 'ShellBot installed successfully!'\""

# Verify installation
echo ""
echo "Verifying installation..."
if command -v "$SCRIPT_NAME" &> /dev/null; then
    echo "✓ ShellBot is now available as '$SCRIPT_NAME'"
else
    echo "✗ Installation may have failed. Check if $INSTALL_DIR is in your PATH."
    echo "  Current PATH: $PATH"
fi