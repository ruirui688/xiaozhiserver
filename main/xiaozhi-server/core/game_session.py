"""Re-export GameSessionManager from the core.game package.

This module exists for backward compatibility. New code should import
directly from core.game.
"""

from core.game import GameSessionManager  # noqa: F401
