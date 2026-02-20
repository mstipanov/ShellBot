# Shell bot

## Purpose
Imagine you have a long living CLI program (you cn call it Claude Code).
This bot "wraps" the original CLI and catches everything the program outputs, refines it and sends it to the user using telegram.
User can type some input, that this wrapper just sends to stdin. 

### Usage:
    python3 shell_bot.py -c "claude"

It needs to behave exactly like the target command would if it would be invoked without the wrapper.
