"""简易熔断器 — Java SimpleCircuitBreaker 的 Python 移植。"""
import time
import threading
import logging
from enum import Enum

logger = logging.getLogger(__name__)


class State(Enum):
    CLOSED = "CLOSED"
    OPEN = "OPEN"
    HALF_OPEN = "HALF_OPEN"


class SimpleCircuitBreaker:
    """状态机: CLOSED → OPEN → HALF_OPEN → CLOSED/OPEN"""

    def __init__(self, name: str, failure_threshold: int = 3, recovery_timeout_ms: int = 30_000):
        self.name = name
        self.failure_threshold = failure_threshold
        self.recovery_timeout_ms = recovery_timeout_ms
        self._state = State.CLOSED
        self._failure_count = 0
        self._last_failure_time = 0.0
        self._lock = threading.Lock()

    @property
    def state(self) -> State:
        return self._state

    def is_call_permitted(self) -> bool:
        with self._lock:
            if self._state == State.CLOSED:
                return True
            if self._state == State.OPEN:
                if (time.monotonic() * 1000 - self._last_failure_time) >= self.recovery_timeout_ms:
                    self._state = State.HALF_OPEN
                    logger.info("熔断器 %s: OPEN → HALF_OPEN", self.name)
                    return True
                return False
            # HALF_OPEN
            return True

    def record_success(self):
        with self._lock:
            self._failure_count = 0
            if self._state != State.CLOSED:
                logger.info("熔断器 %s: → CLOSED", self.name)
            self._state = State.CLOSED

    def record_failure(self):
        with self._lock:
            self._last_failure_time = time.monotonic() * 1000
            if self._state == State.HALF_OPEN:
                self._state = State.OPEN
                self._failure_count = 0
                logger.warning("熔断器 %s: HALF_OPEN 探测失败 → OPEN", self.name)
                return
            self._failure_count += 1
            if self._failure_count >= self.failure_threshold:
                self._state = State.OPEN
                logger.warning("熔断器 %s: 连续失败 %s 次 → OPEN", self.name, self._failure_count)
