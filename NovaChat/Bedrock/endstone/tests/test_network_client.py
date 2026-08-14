"""
Tests for NetworkClient reconnect-task lifecycle.

Regression coverage for the shutdown path: disconnect()/close must cancel a
pending reconnect loop and the closing flag must prevent any new reconnect
from being scheduled, so unloading the plugin cannot leave a background
reconnect loop running.
"""

import asyncio

import pytest

from novachat_endstone.network.client import NetworkClient


def make_client() -> NetworkClient:
    # Port 1 on localhost is refused immediately, so connect() fails fast and
    # walks the reconnect-scheduling path without real backend infrastructure.
    return NetworkClient(
        plugin=None,
        host="127.0.0.1",
        port=1,
        username="test",
        password="secret",
    )


async def drain_pending_tasks() -> None:
    """Give cancellations and close tasks a chance to run."""
    for _ in range(5):
        await asyncio.sleep(0)


class TestReconnectTaskLifecycle:
    """Reconnect loop must die with the connection on explicit shutdown."""

    async def test_failed_connect_schedules_reconnect_task(self):
        client = make_client()

        assert await client.connect() is False

        task = client._reconnect_task
        assert task is not None
        assert not task.done()

        # Cleanup so the loop does not leak into other tests.
        client.disconnect()
        await drain_pending_tasks()

    async def test_disconnect_cancels_pending_reconnect_task(self):
        client = make_client()

        await client.connect()
        task = client._reconnect_task
        assert task is not None and not task.done()

        client.disconnect()
        await drain_pending_tasks()

        assert task.cancelled()
        assert client._reconnect_task is None

    async def test_closing_flag_blocks_new_reconnect_scheduling(self):
        client = make_client()

        client.disconnect()
        await drain_pending_tasks()

        client._schedule_reconnect()
        assert client._reconnect_task is None

    async def test_handle_disconnect_after_close_does_not_reschedule(self):
        client = make_client()

        client.disconnect()
        await drain_pending_tasks()

        # Late "connection lost" event arriving after shutdown must be a no-op.
        client._connected = True
        await client._handle_disconnect()
        await drain_pending_tasks()

        assert client._reconnect_task is None

    async def test_explicit_connect_rearms_after_disconnect(self):
        client = make_client()

        client.disconnect()
        await drain_pending_tasks()
        assert client._closing is True

        # Explicit reconnect attempt clears the closing flag; the failed dial
        # schedules the reconnect loop again.
        assert await client.connect() is False
        assert client._closing is False
        task = client._reconnect_task
        assert task is not None and not task.done()

        client.disconnect()
        await drain_pending_tasks()
        assert task.cancelled()

    async def test_schedule_reconnect_does_not_stack_loops(self):
        client = make_client()

        client._schedule_reconnect()
        first = client._reconnect_task
        client._schedule_reconnect()

        assert client._reconnect_task is first

        client.disconnect()
        await drain_pending_tasks()
        assert first.cancelled()
