"""Game session management package.

Re-exports GameSessionManager from the base module for backward compatibility.
"""

from core.game.base import GameSessionManager

__all__ = [
    "GameSessionManager",
]
