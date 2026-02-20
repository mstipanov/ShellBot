#!/usr/bin/env python3
"""
Adding Game - CLI game that tests addition skills.
Features:
- Asks you to add numbers
- Offers 3 solutions to choose from
- Tracks game stats
- Interactive CLI with scrolling past interaction
"""

import random
import time
import sys
import os
import termios
import tty
import select


class AddingGame:
    def __init__(self):
        self.correct_answers = 0
        self.total_questions = 0
        self.current_question = None
        self.current_options = []
        self.correct_index = 0
        self.past_interactions = []  # Store past Q&A
        self.input_buffer = ""  # Current user input
        self.running = True
        self.question_start_time = None
        self.total_time = 0
        self.warning_given = False
        self.warning_count = 0

    def clear_screen(self):
        """Clear the terminal screen."""
        print("\033[2J\033[H", end="", flush=True)

    def _read_char_with_timeout(self, timeout):
        """Read a single character with timeout."""
        import select
        if select.select([sys.stdin], [], [], timeout)[0]:
            return sys.stdin.read(1)
        raise TimeoutError

    def display_game(self):
        """Display the game UI."""
        # Clear screen and move to top
        self.clear_screen()

        # Display past interactions (scrollable area)
        sys.stdout.write("=== ADDING GAME ===\r\n")

        # Show past Q&A (most recent at bottom)
        for i, interaction in enumerate(self.past_interactions[-10:]):  # Show last 10
            sys.stdout.write(f"{interaction}\r\n")

        # Current question
        if self.current_question:
            sys.stdout.write(f"\r\n{self.current_question}\r\n")
            for i, option in enumerate(self.current_options):
                indicator = "→" if i == 0 else " "  # First option is default/selected
                sys.stdout.write(f"  {indicator} {chr(97 + i)}) {option}\r\n")  # a), b), c)

        # Separator line
        sys.stdout.write(f"\r\n{'─' * 40}\r\n")

        # Stats line - show elapsed time for current question
        if self.question_start_time:
            elapsed = time.time() - self.question_start_time
            time_display = f" | Time: {elapsed:.1f}s"
        else:
            time_display = ""

        if self.total_questions > 0:
            accuracy = (self.correct_answers / self.total_questions) * 100
            avg_time = self.total_time / self.total_questions if self.total_questions > 0 else 0
            sys.stdout.write(f"Correct: {self.correct_answers}/{self.total_questions} ({accuracy:.1f}%) | Avg: {avg_time:.1f}s{time_display}\r\n")
        else:
            sys.stdout.write(f"Correct: 0/0 (0.0%){time_display}\r\n")

        # Input line at bottom
        sys.stdout.write(f"\r\nYour choice (a/b/c or 'q' to quit): {self.input_buffer}")
        sys.stdout.flush()

    def generate_question(self):
        """Generate a new addition question with 3 options."""
        # Generate two random numbers
        num1 = random.randint(10, 99)
        num2 = random.randint(10, 99)
        correct = num1 + num2

        # Generate wrong answers (close to correct)
        wrong1 = correct + random.choice([-5, -4, -3, -2, -1, 1, 2, 3, 4, 5])
        wrong2 = correct + random.choice([-10, -9, -8, -7, -6, 6, 7, 8, 9, 10])

        # Ensure wrong answers are positive and different
        wrong1 = max(1, wrong1)
        wrong2 = max(1, wrong2)
        while wrong1 == correct or wrong1 == wrong2:
            wrong1 = correct + random.randint(-10, 10)
            wrong1 = max(1, wrong1)
        while wrong2 == correct or wrong2 == wrong1:
            wrong2 = correct + random.randint(-10, 10)
            wrong2 = max(1, wrong2)

        # Shuffle options
        options = [correct, wrong1, wrong2]
        random.shuffle(options)

        # Find correct index
        self.correct_index = options.index(correct)

        # Format question and options
        self.current_question = f"What is {num1} + {num2}?"
        self.current_options = [f"{opt}" for opt in options]

        # Start timer for this question
        self.question_start_time = time.time()
        self.warning_given = False
        self.warning_count = 0

        # Add to past interactions
        self.past_interactions.append(f"Q: {self.current_question}")

    def check_answer(self, choice):
        """Check if the user's choice is correct."""
        self.total_questions += 1

        # Convert choice to index (a=0, b=1, c=2)
        choice_index = ord(choice) - 97 if choice in ['a', 'b', 'c'] else -1

        if choice_index == self.correct_index:
            self.correct_answers += 1
            result = f"A: Correct! {self.current_question} = {self.current_options[self.correct_index]}"
            self.past_interactions.append(result)
            return True
        else:
            correct_answer = chr(97 + self.correct_index)
            result = f"A: Wrong! Correct answer is {correct_answer}) {self.current_options[self.correct_index]}"
            self.past_interactions.append(result)
            return False

    def run(self):
        """Main game loop."""
        # Save terminal settings
        fd = sys.stdin.fileno()
        old_settings = termios.tcgetattr(fd)

        try:
            # Set terminal to raw mode for single character input
            tty.setraw(fd)

            # Generate first question
            self.generate_question()

            while self.running:
                # Check time for warnings
                if self.question_start_time:
                    elapsed = time.time() - self.question_start_time
                    # Give warning after 10 seconds
                    if elapsed > 10 and not self.warning_given:
                        self.warning_given = True
                        self.warning_count += 1
                        self.past_interactions.append("⚠️  Speed up! You're taking too long!")
                    # Give another warning after 20 seconds
                    elif elapsed > 20 and self.warning_count == 1:
                        self.warning_count += 1
                        self.past_interactions.append("⚠️  ⚠️  Seriously, speed up!")
                    # Give final warning after 30 seconds
                    elif elapsed > 30 and self.warning_count == 2:
                        self.warning_count += 1
                        self.past_interactions.append("⚠️  ⚠️  ⚠️  Last warning! Answer now!")

                # Display game state
                self.display_game()

                # Wait for key press (with short timeout to check time)
                try:
                    key = self._read_char_with_timeout(0.1)
                except TimeoutError:
                    continue  # No key pressed, loop again to check time

                if key == 'q' or key == '\x03':  # 'q' or Ctrl+C
                    self.running = False
                    break
                elif key == '\r' or key == '\n':  # Enter key
                    if len(self.input_buffer) == 1 and self.input_buffer in ['a', 'b', 'c']:
                        # Record time for this question
                        if self.question_start_time:
                            question_time = time.time() - self.question_start_time
                            self.total_time += question_time

                        # Check answer
                        correct = self.check_answer(self.input_buffer)

                        # Display result briefly
                        self.display_game()
                        time.sleep(1 if correct else 2)

                        # Generate new question
                        self.generate_question()

                        # Clear input buffer
                        self.input_buffer = ""
                    else:
                        # Invalid input, clear it
                        self.input_buffer = ""
                elif key in ['a', 'b', 'c']:
                    # User pressed a valid choice key
                    self.input_buffer = key
                elif key == '\x7f':  # Backspace
                    self.input_buffer = ""
                elif key is not None:
                    # Ignore other keys
                    pass

        except KeyboardInterrupt:
            pass
        finally:
            # Restore terminal settings
            termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)

            # Final display
            self.clear_screen()
            print("=== ADDING GAME - FINAL SCORE ===\n")
            if self.total_questions > 0:
                accuracy = (self.correct_answers / self.total_questions) * 100
                avg_time = self.total_time / self.total_questions if self.total_questions > 0 else 0
                print(f"Total questions: {self.total_questions}")
                print(f"Correct answers: {self.correct_answers}")
                print(f"Accuracy: {accuracy:.1f}%")
                print(f"Average time per question: {avg_time:.1f}s\n")
            print("Thanks for playing!")


def main():
    game = AddingGame()
    game.run()


if __name__ == "__main__":
    main()