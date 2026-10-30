"""One implementation of every operation for both the sync and the async client.

Operations are written as generators that ``yield`` :class:`Request` objects
and receive the decoded response (or have the mapped exception thrown into
them). :class:`SyncRunner` and :class:`AsyncRunner` drive those generators with
blocking or awaitable I/O, so multi-step operations such as ``apply()`` or a
version-checked update exist exactly once.
"""

from __future__ import annotations

import functools
from typing import Any, AsyncIterator, Callable, Generator, Iterator, List, Optional, TypeVar

from ranger_sdk._transport import AsyncTransport, Request, SyncTransport
from ranger_sdk.errors import RangerException

T = TypeVar("T")
Flow = Generator[Request, Any, T]


class SyncRunner:
    is_async = False

    def __init__(self, transport: SyncTransport) -> None:
        self.transport = transport

    def run(self, flow: Flow[T]) -> T:
        try:
            req = next(flow)
            while True:
                try:
                    result = self.transport.call(req)
                except RangerException as exc:
                    req = flow.throw(exc)
                else:
                    req = flow.send(result)
        except StopIteration as stop:
            return stop.value  # type: ignore[no-any-return]

    def paginate(self, page: Callable[[int], Flow[List[T]]], page_size: int) -> Iterator[T]:
        start = 0
        while True:
            items = self.run(page(start))
            yield from items
            if len(items) < page_size:
                return
            start += len(items)


class AsyncRunner:
    is_async = True

    def __init__(self, transport: AsyncTransport) -> None:
        self.transport = transport

    async def run(self, flow: Flow[T]) -> T:
        try:
            req = next(flow)
            while True:
                try:
                    result = await self.transport.call(req)
                except RangerException as exc:
                    req = flow.throw(exc)
                else:
                    req = flow.send(result)
        except StopIteration as stop:
            return stop.value  # type: ignore[no-any-return]

    async def paginate(self, page: Callable[[int], Flow[List[T]]], page_size: int) -> AsyncIterator[T]:
        start = 0
        while True:
            items = await self.run(page(start))
            for item in items:
                yield item
            if len(items) < page_size:
                return
            start += len(items)


def flow(fn: Callable[..., Flow[T]]) -> Callable[..., Any]:
    """Turn a generator method into a call executed by the owner's runner."""

    @functools.wraps(fn)
    def wrapper(self: Any, *args: Any, **kwargs: Any) -> Any:
        return self._runner.run(fn(self, *args, **kwargs))

    wrapper.flow = fn  # type: ignore[attr-defined]
    return wrapper


def call(req: Request, parse: Optional[Callable[[Any], T]] = None) -> Flow[T]:
    """A flow consisting of a single request."""
    result = yield req
    return parse(result) if parse is not None else result
