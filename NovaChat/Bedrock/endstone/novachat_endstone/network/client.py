"""
Network client for connecting to NovaLink backend.

This module implements an asyncio-based TCP client for communicating
with the NovaLink backend server using the NovaProtocol.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import logging
import os
import secrets
import ssl
import time
import uuid
from typing import TYPE_CHECKING, Optional, Callable, Dict, Any

from novachat_endstone.protocol.varint import VarInt
from novachat_endstone.protocol.buffer import PacketBuffer
from novachat_endstone.protocol.packet import (
    Packet,
    PacketIds,
    PlatformType,
    HandshakePacket,
    HandshakeResponsePacket,
    HandshakeInitPacket,
    HandshakeChallengePacket,
    HandshakeAuthenticatePacket,
    KeepAlivePacket,
    decode_packet,
)

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin


class NetworkClient:
    """Asyncio-based network client for NovaLink communication."""

    PROTOCOL_VERSION = 3
    PLATFORM_ENDSTONE = PlatformType.ENDSTONE  # 10 (must match Java PlatformType.ENDSTONE)
    KEEPALIVE_INTERVAL = 15  # seconds
    MAX_FRAME_LENGTH = 4 * 1024 * 1024  # 4 MiB (must match server-side limits)
    
    def __init__(
        self,
        plugin: "NovaChatPlugin",
        host: str,
        port: int,
        username: str,
        password: str,
        server_version: str,
        reconnect_delay: int,
        tls_enabled: bool = False,
        tls_ca_cert_path: str = "",
        tls_client_cert_path: str = "",
        tls_client_key_path: str = "",
    ):
        """
        Initialize the network client.

        Args:
            plugin: The parent plugin instance
            host: Backend server host
            port: Backend server port
            username: Client username for authentication
            password: Client password for authentication
            server_version: Minecraft server version reported in handshake (v3)
            reconnect_delay: Initial reconnect delay in seconds
            tls_enabled: When True, wrap the TCP transport in TLS (AUTH-002).
                False keeps the plaintext path (zero regression against the
                pre-TLS behavior).
            tls_ca_cert_path: PEM file used to verify the backend certificate.
                Empty string means use the system CA store. Verification is
                always enforced when tls_enabled is True — there is no option
                to disable it.
            tls_client_cert_path: Optional mTLS client certificate (PEM). Must
                be paired with tls_client_key_path.
            tls_client_key_path: Optional mTLS client private key (PEM).
        """
        self._plugin = plugin
        self._host = host
        self._port = port
        self._username = username
        self._password = password
        self._server_version = server_version or ""

        # AUTH-002 TLS: transport encryption. Built once (cheap) and reused on
        # every reconnect so the SSLContext is not rebuilt per connection.
        self._ssl_context: Optional[ssl.SSLContext] = None
        if tls_enabled:
            self._ssl_context = self._build_ssl_context(
                ca_cert_path=tls_ca_cert_path,
                client_cert_path=tls_client_cert_path,
                client_key_path=tls_client_key_path,
            )
        
        self._reader: Optional[asyncio.StreamReader] = None
        self._writer: Optional[asyncio.StreamWriter] = None
        self._connected = False
        self._authenticated = False

        # The asyncio event loop running the background read/keepalive tasks.
        # Captured in connect() (which is async, so a loop is running) so that
        # callers on non-loop threads (e.g. Endstone's main-thread chat events)
        # can schedule coroutines onto it via run_coroutine_threadsafe instead
        # of asyncio.create_task (which requires a running loop in the current
        # thread and raises RuntimeError otherwise).
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        
        if reconnect_delay <= 0:
            raise ValueError("reconnect_delay must be greater than 0")
        self._reconnect_delay = reconnect_delay
        self._max_reconnect_delay = 60
        self._current_reconnect_delay = self._reconnect_delay
        
        self._packet_handlers: Dict[int, Callable[[Packet], None]] = {}
        self._keepalive_task: Optional[asyncio.Task] = None
        self._read_task: Optional[asyncio.Task] = None
        self._reconnect_task: Optional[asyncio.Task] = None
        # Tracks the in-flight _close_connection() task scheduled by
        # disconnect() so it can be awaited (preventing a fire-and-forget
        # socket leak where the writer may never finish closing).
        self._close_task: Optional[asyncio.Task] = None
        # Set by disconnect(); blocks any new reconnect scheduling until the
        # next explicit connect() so plugin unload cannot leave a loop running.
        self._closing = False
        
        self._logger = logging.getLogger("NovaChat.Network")

    @staticmethod
    def _build_ssl_context(
        ca_cert_path: str,
        client_cert_path: str,
        client_key_path: str,
    ) -> ssl.SSLContext:
        """Build a verified client SSLContext for the backend transport.

        AUTH-002: the context is configured to ALWAYS verify the server
        certificate. There is no flag to disable verification — the whole
        point of enabling TLS is closing the passive-sniff / offline-brute-force
        window on the HMAC key material, and disabling verification would
        re-open it.

        PROTOCOL_TLS_CLIENT negotiates the highest mutually supported TLS
        version (TLS 1.2+ on CPython 3.10+, which has deprecated TLS 1.0/1.1
        at the protocol level) and sets sane defaults: check_hostname=True and
        verify_mode=CERT_REQUIRED out of the box. We re-assert them explicitly
        so the intent is obvious and resilient to upstream default changes.
        """
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        # check_hostname is redundant with CERT_REQUIRED for a client context,
        # but asserted explicitly: the backend certificate MUST match the
        # host we connect to.
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED

        ca_path = ca_cert_path.strip()
        if ca_path:
            # Operator-supplied CA bundle (e.g. a private CA). load_verify_locations
            # raises FileNotFoundError/SSLError if the path is bad, which
            # surfaces at plugin enable — before any connection is attempted.
            context.load_verify_locations(cafile=ca_path)
        else:
            # No explicit CA bundle: fall back to the system trust store.
            context.load_default_certs()

        cert_path = client_cert_path.strip()
        key_path = client_key_path.strip()
        if cert_path and key_path:
            # mTLS: present a client certificate when the backend requests one.
            context.load_cert_chain(certfile=cert_path, keyfile=key_path)

        return context

    async def connect(self) -> bool:
        """
        Connect to the backend server.
        
        Returns:
            True if connection and authentication succeeded
        """
        # An explicit connect() re-arms the client after disconnect(). The
        # reconnect loop also lands here, but disconnect() cancels that task
        # before it can run again, so re-arming is safe.
        self._closing = False

        # Capture the running loop so callers on other threads can schedule
        # coroutines back onto it via run_coroutine_threadsafe (Bug 4: the
        # chat handler's on_player_chat runs on Endstone's main thread where
        # no asyncio loop is running, so asyncio.create_task raises).
        try:
            self._loop = asyncio.get_running_loop()
        except RuntimeError:
            self._loop = asyncio.get_event_loop()
        
        try:
            self._logger.info(f"Connecting to {self._host}:{self._port}...")

            self._reader, self._writer = await asyncio.open_connection(
                self._host, self._port,
                ssl=self._ssl_context,
            )
            self._connected = True
            self._current_reconnect_delay = self._reconnect_delay
            
            # Send handshake
            if await self._authenticate():
                self._authenticated = True
                self._logger.info("Successfully connected and authenticated!")
                
                # Start background tasks
                self._keepalive_task = asyncio.create_task(self._keepalive_loop())
                self._read_task = asyncio.create_task(self._read_loop())
                
                return True
            else:
                self._logger.error("Authentication failed!")
                await self._close_connection()
                return False
                
        except Exception as e:
            self._logger.error(f"Connection failed: {e}")
            self._connected = False
            self._schedule_reconnect()
            return False
    
    def disconnect(self) -> None:
        """
        Disconnect from the backend server (explicit shutdown).

        Sets the closing flag so no new reconnect gets scheduled, cancels any
        running reconnect loop, and schedules ``_close_connection`` to flush
        and close the socket. The close task is tracked on ``self._close_task``
        so callers that can await (e.g. an async shutdown path) can drive it
        to completion via :meth:`await_close`. A later explicit
        :meth:`connect` re-arms the client.
        """
        self._closing = True
        self._logger.info("Disconnecting from backend...")
        self._cancel_reconnect_task()

        # If a previous close is still in flight, let it finish instead of
        # stacking another task.
        if self._close_task is not None and not self._close_task.done():
            return

        # ``disconnect`` is called from a synchronous context (e.g. Endstone's
        # ``on_disable``), so we cannot await here. Schedule the close and
        # track it so the writer is actually drained/closed rather than left
        # dangling (the previous fire-and-forget ``create_task`` could be
        # garbage-collected before completing the close).
        try:
            loop = asyncio.get_event_loop()
        except RuntimeError:
            # No running loop in this thread; perform a best-effort
            # synchronous close of the writer so the socket is released.
            self._sync_close_writer()
            return

        if loop.is_running():
            self._close_task = loop.create_task(self._close_connection())
        else:
            # Loop exists but isn't running — run the close to completion
            # synchronously so we don't leak an unstarted task.
            loop.run_until_complete(self._close_connection())

    def _sync_close_writer(self) -> None:
        """Best-effort synchronous socket close when no event loop is available."""
        self._connected = False
        self._authenticated = False
        if self._keepalive_task is not None and not self._keepalive_task.done():
            self._keepalive_task.cancel()
        self._keepalive_task = None
        if self._read_task is not None and not self._read_task.done():
            self._read_task.cancel()
        self._read_task = None
        if self._writer is not None:
            try:
                self._writer.close()
            except Exception:
                pass
            self._writer = None
            self._reader = None

    async def await_close(self, timeout: float = 5.0) -> None:
        """
        Wait for the close task scheduled by :meth:`disconnect` to finish.

        Use from an async shutdown path to ensure the socket is fully closed
        before returning. No-op if no close is in flight.

        Args:
            timeout: Maximum seconds to wait for the close to complete.
        """
        task = self._close_task
        if task is None:
            return
        try:
            await asyncio.wait_for(asyncio.shield(task), timeout)
        except asyncio.TimeoutError:
            self._logger.warning("Timed out waiting for backend connection close")
        except Exception as e:
            self._logger.debug(f"Close task ended with: {e}")
        finally:
            self._close_task = None
    
    def _cancel_reconnect_task(self) -> None:
        """Cancel a pending reconnect loop, if any."""
        task = self._reconnect_task
        if task is not None and not task.done():
            task.cancel()
        self._reconnect_task = None
    
    def _schedule_reconnect(self) -> None:
        """Start the reconnect loop unless closing or one is already running."""
        if self._closing:
            return
        if self._reconnect_task is not None and not self._reconnect_task.done():
            return
        self._reconnect_task = asyncio.create_task(self._reconnect())
    
    async def _close_connection(self) -> None:
        """Close the connection and cleanup."""
        self._connected = False
        self._authenticated = False
        # Clear the close-task reference now that we're executing it, so a
        # subsequent connect() starts from a clean slate.
        self._close_task = None

        if self._closing:
            # Shutdown path: make sure no reconnect loop survives the close.
            self._cancel_reconnect_task()
        
        if self._keepalive_task:
            self._keepalive_task.cancel()
            self._keepalive_task = None
        
        if self._read_task:
            self._read_task.cancel()
            self._read_task = None
        
        if self._writer:
            self._writer.close()
            try:
                await self._writer.wait_closed()
            except Exception:
                pass
            self._writer = None
            self._reader = None
    
    async def _reconnect(self) -> None:
        """Attempt to reconnect with exponential backoff."""
        while not self._connected and not self._closing:
            self._logger.info(
                f"Reconnecting in {self._current_reconnect_delay} seconds..."
            )
            await asyncio.sleep(self._current_reconnect_delay)
            
            if self._closing:
                break
            
            if await self.connect():
                break
            
            # Exponential backoff
            self._current_reconnect_delay = min(
                self._current_reconnect_delay * 2,
                self._max_reconnect_delay
            )
    
    async def _authenticate(self) -> bool:
        """
        Perform the AUTH-002 challenge-response authentication handshake.

        Three-packet flow (replaces the replayable static-hash HandshakePacket):
            1. Client -> Server: HandshakeInitPacket (0x15) carrying a fresh
               cryptographically-secure random client nonce.
            2. Server -> Client: HandshakeChallengePacket (0x16) with the
               server's fresh random nonce.
            3. Client -> Server: HandshakeAuthenticatePacket (0x17) echoing the
               client id + client nonce and an HMAC-SHA-256 over
               (serverNonce + clientNonce), keyed by sha256hex(password).
        The server then replies with HandshakeResponsePacket (0x02).

        Returns:
            True if authentication succeeded
        """
        # Fresh 16-byte cryptographically-secure random nonce, hex (32 chars).
        # secrets.token_hex uses the OS CSPRNG; never a weak PRNG.
        client_nonce = secrets.token_hex(16)

        # 1) Send init.
        init = HandshakeInitPacket(
            protocol_version=self.PROTOCOL_VERSION,
            client_id=self._username,
            platform=self.PLATFORM_ENDSTONE,
            server_version=self._server_version,
            client_nonce=client_nonce,
        )
        await self.send_packet(init)

        # 2) Await the server challenge.
        try:
            challenge = await self._read_packet()
        except Exception as e:
            self._logger.error(f"Error reading handshake challenge: {e}")
            return False
        if not isinstance(challenge, HandshakeChallengePacket):
            self._logger.error("Unexpected response to handshake init (expected challenge)")
            return False
        server_nonce = challenge.server_nonce or ""

        # 3) Compute the HMAC and authenticate.
        #    key      = utf-8 bytes of sha256hex(password)   (stored credential hash)
        #    message  = utf-8 bytes of (server_nonce_hex + client_nonce_hex)
        #    output   = HMAC-SHA-256(key, message) as lowercase hex
        key = hashlib.sha256(self._password.encode()).hexdigest().encode()
        message = (server_nonce + client_nonce).encode()
        auth_hmac = hmac.new(key, message, hashlib.sha256).hexdigest()

        authenticate = HandshakeAuthenticatePacket(
            client_id=self._username,
            client_nonce=client_nonce,
            hmac=auth_hmac,
        )
        await self.send_packet(authenticate)

        # 4) Await the final response.
        try:
            packet = await self._read_packet()
            if isinstance(packet, HandshakeResponsePacket):
                if packet.success:
                    return True
                else:
                    self._logger.error(f"Auth failed: {packet.error_code}")
                    # Handle specific error codes with clear messages (Requirements: 27.3)
                    if packet.error_code == "NC-401":
                        self._logger.error("Please check your username and password in config.yml")
                    elif packet.error_code == "NC-420":
                        self._logger.error("=================================================")
                        self._logger.error("PROTOCOL VERSION MISMATCH!")
                        self._logger.error("Your NovaChat plugin version is incompatible with the NovaLink backend.")
                        self._logger.error("Please update your plugin to match the backend protocol version.")
                        self._logger.error(f"Current plugin protocol version: {self.PROTOCOL_VERSION}")
                        self._logger.error("=================================================")
                    return False
            else:
                self._logger.error("Unexpected response to handshake authenticate")
                return False
        except Exception as e:
            self._logger.error(f"Error during authentication: {e}")
            return False
    
    async def send_packet(self, packet: Packet) -> None:
        """
        Send a packet to the server.
        
        Args:
            packet: The packet to send
        """
        if not self._connected or not self._writer:
            self._logger.warning("Cannot send packet: not connected")
            return
        
        try:
            # Encode packet
            buffer = PacketBuffer()
            buffer.write_byte(packet.packet_id)

            # NovaProtocol v1 framing includes request_id (UUID) after packet_id.
            request_id = getattr(packet, "request_id", None)
            if not isinstance(request_id, uuid.UUID):
                request_id = uuid.uuid4()
                try:
                    setattr(packet, "request_id", request_id)
                except Exception:
                    pass
            buffer.write_uuid(request_id)

            packet.encode(buffer)
            
            # Write length-prefixed packet
            packet_data = buffer.get_bytes()
            if len(packet_data) <= 0 or len(packet_data) > self.MAX_FRAME_LENGTH:
                raise ValueError(f"Outbound packet too large: {len(packet_data)} bytes")
            length_prefix = VarInt.encode(len(packet_data))
            
            self._writer.write(length_prefix + packet_data)
            await self._writer.drain()
            
        except Exception as e:
            self._logger.error(f"Error sending packet: {e}")
            await self._handle_disconnect()
    
    async def _read_packet(self) -> Optional[Packet]:
        """
        Read a single packet from the server.
        
        Returns:
            The decoded packet, or None on error
        """
        if not self._reader:
            return None
        
        try:
            # Read packet length
            length_bytes = bytearray()
            while True:
                byte = await self._reader.read(1)
                if not byte:
                    return None
                length_bytes.append(byte[0])
                if (byte[0] & 0x80) == 0:
                    break
            
            length, _ = VarInt.decode(bytes(length_bytes))
            if length <= 0 or length > self.MAX_FRAME_LENGTH:
                self._logger.error(f"Invalid inbound frame length: {length}")
                return None
            
            # Read packet data
            data = await self._reader.readexactly(length)
            buffer = PacketBuffer(data)
            
            # Decode packet
            packet_id = buffer.read_byte()
            request_id = buffer.read_uuid()
            pkt = decode_packet(packet_id, buffer)
            try:
                setattr(pkt, "request_id", request_id)
            except Exception:
                pass
            return pkt
            
        except asyncio.IncompleteReadError:
            return None
        except Exception as e:
            self._logger.error(f"Error reading packet: {e}")
            return None
    
    async def _read_loop(self) -> None:
        """Background task for reading packets."""
        while self._connected:
            try:
                packet = await self._read_packet()
                if packet is None:
                    await self._handle_disconnect()
                    break
                
                await self._handle_packet(packet)
                
            except asyncio.CancelledError:
                break
            except Exception as e:
                self._logger.error(f"Error in read loop: {e}")
                await self._handle_disconnect()
                break
    
    async def _keepalive_loop(self) -> None:
        """Background task for sending keepalive packets."""
        while self._connected:
            try:
                await asyncio.sleep(self.KEEPALIVE_INTERVAL)
                
                if self._connected:
                    keepalive = KeepAlivePacket(timestamp=int(time.time() * 1000))
                    await self.send_packet(keepalive)
                    
            except asyncio.CancelledError:
                break
            except Exception as e:
                self._logger.error(f"Error in keepalive loop: {e}")
    
    async def _handle_packet(self, packet: Packet) -> None:
        """
        Handle a received packet.
        
        Args:
            packet: The received packet
        """
        handler = self._packet_handlers.get(packet.packet_id)
        if handler:
            try:
                handler(packet)
            except Exception as e:
                self._logger.error(f"Error handling packet: {e}")
        else:
            self._logger.debug(f"No handler for packet ID: {packet.packet_id}")
    
    async def _handle_disconnect(self) -> None:
        """Handle unexpected disconnection."""
        if self._connected:
            self._logger.warning("Connection lost, attempting to reconnect...")
            await self._close_connection()
            self._schedule_reconnect()
    
    def register_handler(
        self,
        packet_id: int,
        handler: Callable[[Packet], None]
    ) -> None:
        """
        Register a packet handler.
        
        Args:
            packet_id: The packet ID to handle
            handler: The handler function
        """
        self._packet_handlers[packet_id] = handler
    
    @property
    def is_connected(self) -> bool:
        """Check if connected to the backend."""
        return self._connected and self._authenticated

    @property
    def loop(self) -> Optional[asyncio.AbstractEventLoop]:
        """The asyncio event loop backing this client's background tasks.

        Callers running on a non-loop thread (e.g. Endstone's main server
        thread) use this with :func:`asyncio.run_coroutine_threadsafe` to
        schedule coroutines onto the client's loop instead of
        :func:`asyncio.create_task`, which requires a running loop in the
        current thread.
        """
        return self._loop
