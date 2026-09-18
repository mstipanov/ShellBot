#!/bin/bash

# ShellBot PTY Kotlin - Install Script
#
# Installs missing prerequisites (Java 21+, Maven, tmux), builds the
# project, installs the JAR + wrapper into ~/bin and makes sure ~/bin
# is on PATH.

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${SHELLBOT_INSTALL_DIR:-$HOME/bin}"
JAR_NAME="shellbot.jar"
SCRIPT_NAME="shellbot"
MIN_JAVA_MAJOR=21

echo "Installing ShellBot PTY Kotlin..."
echo "Project directory: $PROJECT_DIR"
echo "Install directory: $INSTALL_DIR"

have() { command -v "$1" &> /dev/null; }
fail() { echo "Error: $*" >&2; exit 1; }

java_major() {
    java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/'
}

have_java() {
    have java || return 1
    local major
    major="$(java_major)"
    [[ "$major" =~ ^[0-9]+$ ]] || return 1
    [ "$major" -ge "$MIN_JAVA_MAJOR" ]
}

# Add a directory to PATH for this session and persist it in the shell rc file.
add_to_path() {
    local dir="$1"
    case ":$PATH:" in
        *":$dir:"*) ;;
        *) export PATH="$dir:$PATH" ;;
    esac

    local rc
    case "$(basename "${SHELL:-bash}")" in
        zsh) rc="$HOME/.zshrc" ;;
        bash) rc="$HOME/.bashrc" ;;
        *) rc="$HOME/.profile" ;;
    esac
    touch "$rc"
    if ! grep -qF "export PATH=\"$dir:\$PATH\"" "$rc"; then
        printf '\n# Added by ShellBot installer\nexport PATH="%s:$PATH"\n' "$dir" >> "$rc"
    fi
}

install_homebrew() {
    have brew && return
    echo "Installing Homebrew..."
    NONINTERACTIVE=1 /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)" || \
        fail "Homebrew installation failed."
    if [ -x /opt/homebrew/bin/brew ]; then
        eval "$(/opt/homebrew/bin/brew shellenv)"
    elif [ -x /usr/local/bin/brew ]; then
        eval "$(/usr/local/bin/brew shellenv)"
    fi
}

install_macos() {
    install_homebrew
    local pkg
    for pkg in "$@"; do
        case "$pkg" in
            java)
                brew install openjdk
                add_to_path "$(brew --prefix openjdk)/bin"
                ;;
            mvn) brew install maven ;;
            tmux) brew install tmux ;;
        esac
    done
}

linux_java_package() {
    if have apt-get; then
        local v
        for v in 25 24 23 22 21; do
            if apt-cache show "openjdk-$v-jdk-headless" &> /dev/null; then
                echo "openjdk-$v-jdk-headless"
                return
            fi
        done
        echo "openjdk-21-jdk-headless"
    elif have dnf || have yum; then
        echo "java-latest-openjdk-devel"
    elif have pacman; then
        echo "jdk-openjdk"
    elif have zypper; then
        echo "java-latest-openjdk-devel"
    elif have apk; then
        echo "openjdk21"
    else
        echo "openjdk"
    fi
}

install_linux() {
    local pkgs=()
    local pkg
    for pkg in "$@"; do
        case "$pkg" in
            java) pkgs+=("$(linux_java_package)") ;;
            mvn) pkgs+=("maven") ;;
            tmux) pkgs+=("tmux") ;;
        esac
    done

    if have apt-get; then
        sudo apt-get update
        sudo apt-get install -y "${pkgs[@]}"
    elif have dnf; then
        sudo dnf install -y "${pkgs[@]}"
    elif have yum; then
        sudo yum install -y "${pkgs[@]}"
    elif have pacman; then
        sudo pacman -Sy --noconfirm "${pkgs[@]}"
    elif have zypper; then
        sudo zypper --non-interactive install "${pkgs[@]}"
    elif have apk; then
        sudo apk add "${pkgs[@]}"
    else
        fail "No supported package manager found. Install Java 21+, Maven and tmux manually."
    fi
}

install_prerequisites() {
    local missing=()
    have_java || missing+=("java")
    have mvn || missing+=("mvn")
    have tmux || missing+=("tmux")

    if [ "${#missing[@]}" -eq 0 ]; then
        echo "Prerequisites present: java $(java_major), maven, tmux"
        return
    fi

    echo ""
    echo "Installing missing prerequisites: ${missing[*]}"

    case "$(uname -s)" in
        Darwin)
            install_macos "${missing[@]}"
            ;;
        Linux)
            install_linux "${missing[@]}"
            ;;
        *)
            fail "Unsupported OS. Install Java $MIN_JAVA_MAJOR+, Maven and tmux manually."
            ;;
    esac

    hash -r
    have_java || fail "Java $MIN_JAVA_MAJOR+ is still not available."
    have mvn || fail "Maven is still not available."
    have tmux || fail "tmux is still not available."
}

install_prerequisites

# Ensure install directory exists
mkdir -p "$INSTALL_DIR"

# Build the project first
echo ""
echo "Building project..."
if ! "$PROJECT_DIR/build.sh"; then
    echo "Build failed. Please check errors above."
    exit 1
fi

# Find the JAR file
JAR_FILE=$(find "$PROJECT_DIR/target" -maxdepth 1 -name "shellbot-*.jar" \
    ! -name "original-*" ! -name "*sources*" ! -name "*tests*" | head -1)

if [ -z "$JAR_FILE" ] || [ ! -f "$JAR_FILE" ]; then
    echo "Error: JAR file not found!"
    exit 1
fi

echo ""
echo "Found JAR: $JAR_FILE"

# Copy JAR to install directory
echo "Copying JAR to $INSTALL_DIR/$JAR_NAME..."
cp -f "$JAR_FILE" "$INSTALL_DIR/$JAR_NAME"

# Create wrapper script (loops on exit code 3 for /sb_restart support)
echo "Creating wrapper script $INSTALL_DIR/$SCRIPT_NAME..."
cat > "$INSTALL_DIR/$SCRIPT_NAME" << EOF
#!/bin/bash
JAR="$INSTALL_DIR/$JAR_NAME"
JAVA="\$HOME/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home/bin/java"
[ -x "\$JAVA" ] || JAVA=java
while true; do
    "\$JAVA" -jar "\$JAR" "\$@"
    rc=\$?
    [ "\$rc" -ne 3 ] && exit "\$rc"
    echo "shellbot: restarting..." >&2
done
EOF

# Make wrapper executable
chmod +x "$INSTALL_DIR/$SCRIPT_NAME"

# Make sure the install directory is reachable
PATH_ADDED=0
case ":$PATH:" in
    *":$INSTALL_DIR:"*) ;;
    *) add_to_path "$INSTALL_DIR"; PATH_ADDED=1 ;;
esac

echo ""
echo "Installation complete!"
echo ""
echo "Usage:"
echo "  $SCRIPT_NAME -c \"echo Hello World\""
echo "  $SCRIPT_NAME -c \"python3 script.py\" -v"

if [ "$PATH_ADDED" -eq 1 ]; then
    echo ""
    echo "Added $INSTALL_DIR to your PATH. Open a new terminal (or re-source your shell rc) to use it."
fi

# Verify installation
echo ""
echo "Verifying installation..."
hash -r
if command -v "$SCRIPT_NAME" &> /dev/null; then
    echo "✓ ShellBot is now available as '$SCRIPT_NAME'"
else
    echo "✗ Installation may have failed. Check if $INSTALL_DIR is in your PATH."
    echo "  Current PATH: $PATH"
fi
