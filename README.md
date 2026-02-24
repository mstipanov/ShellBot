# ShellBot PTY - Kotlin

A Kotlin rewrite of `shell_bot_pty_fixed.py` with proper Maven project structure.

## Project Structure

```
shellbot-kotlin/
├── pom.xml                      # Maven build configuration
├── src/
│   ├── main/
│   │   └── kotlin/
│   │       └── com/
│   │           └── shellbot/
│   │               ├── ShellBot.kt           # Main ShellBot class
│   │               ├── Main.kt               # CLI entry point
│   │               ├── AdvancedShellBot.kt   # JLine-enhanced version
│   │               └── terminal/
│   │                   └── TerminalManager.kt # JLine terminal wrapper
│   └── test/
│       └── kotlin/
│           └── com/
│               └── shellbot/
│                   └── ShellBotTest.kt       # Unit tests
├── examples/
│   └── Example.kt               # Usage examples
└── README.md                    # This file
```

## Features

- **I/O Forwarding**: Forward stdin/stdout/stderr between console and subprocess
- **Terminal Support**: Optional JLine integration for better terminal handling
- **Multi-threaded**: Separate threads for input/output handling
- **Control Character Handling**: Basic Ctrl+C, Ctrl+D support
- **Configurable**: Set environment variables, working directory, verbosity

## Dependencies

- **Kotlin 1.9.0**: Standard library
- **kotlinx-cli 0.3.6**: Command-line argument parsing
- **JLine 3.23.0**: Terminal operations (optional)
- **JNA 5.13.0**: Native PTY support (stub/placeholder)
- **JUnit 5.9.2**: Testing

## Building

### With Maven:

```bash
# Clean build
mvn clean package

# Run tests
mvn test

# Create executable JAR
mvn package

# Run directly
mvn compile exec:java -Dexec.mainClass="com.shellbot.ShellBotMain" -Dexec.args="-c 'echo Hello'"
```

### Executable JAR:

After building, find the executable JAR in `target/shellbot-kotlin-1.0.0.jar`

```bash
java -jar target/shellbot-kotlin-1.0.0.jar -c "your_command"
```

## Usage

### Basic:

```bash
# Simple command
shellbot -c "echo Hello World"

# Python script
shellbot -c "python3 adding_game.py"

# With verbose output
shellbot -c "ls -la" -v

# Set environment variable
shellbot -c "echo \$MY_VAR" -e "MY_VAR=test"
```

### Programmatic Usage:

```kotlin
import com.shellbot.ShellBot

fun main() {
    val shellBot = ShellBot("python3 script.py")
    val exitCode = shellBot.run()
    println("Exit code: $exitCode")
}
```

### Advanced (with JLine):

```kotlin
import com.shellbot.AdvancedShellBot

fun main() {
    val shellBot = AdvancedShellBot("interactive_program")
    val exitCode = shellBot.run()
}
```

## Implementation Notes

### Compared to Python Version:

1. **Architecture**: Multi-threaded vs Python's single-threaded `select()`
2. **PTY Support**: Basic I/O forwarding vs true pseudo-terminal
3. **Terminal Control**: JLine wrapper vs direct terminal system calls
4. **Error Handling**: Kotlin exception handling vs Python try/except

### Limitations:

1. **No true PTY**: Current implementation doesn't provide actual `forkpty()` like Python
2. **Signal handling**: Basic Ctrl+C support, limited other signals
3. **Terminal modes**: Simplified terminal emulation

### For True PTY Support:

To match Python's functionality exactly, you would need:

1. **JNI/JNA bindings** for `forkpty()`, `tcgetattr()`, `tcsetattr()`
2. **Native library** with C/C++ PTY implementation
3. **Signal handling** for proper terminal control

## Testing

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=ShellBotTest

# Generate test coverage report
mvn jacoco:report
```

## License

Apache 2.0

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make changes with tests
4. Submit pull request