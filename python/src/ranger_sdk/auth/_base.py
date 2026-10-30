from __future__ import annotations

from typing import Dict, Tuple


class AuthProvider:
    """Base class for authentication providers. Subclasses override what they need."""

    #: servers this provider may be used against ("admin", "kms", "pdp")
    servers: Tuple[str, ...] = ("admin", "kms", "pdp")
    #: True when the transport may skip auth while it holds a server session cookie
    supports_session_reuse = False

    def headers(self, host: str) -> Dict[str, str]:
        return {}

    def query_params(self) -> Dict[str, str]:
        return {}

    def refresh(self) -> bool:
        """Called once after a 401. Return True if the request should be retried."""
        return False

    def describe(self) -> str:
        return type(self).__name__

    def __repr__(self) -> str:  # never leak secrets through repr()
        return f"<{self.describe()}>"
