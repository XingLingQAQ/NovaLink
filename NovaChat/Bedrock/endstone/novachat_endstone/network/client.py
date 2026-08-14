"""
Network client for connecting to NovaLink backend.

This module implements an asyncio-based TCP client for communicating
with the NovaLink backend server using the NovaProtocol.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
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
    KeepAlivePacket,
    decode_packet,
)

if TYPE_CHECKING:
    from novachat_endstone.plugin import NovaChatPlugin


class NetworkClient:
    """Asyncio-based network client for NovaLink communication."""

    PROTOCOL_VERSION = 2
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
        server_version: str = "",
    ):
        """
        Initialize the network client.

        Args:
            plugin: The parent plugin instance
            host: Backend server host
            port: Backend server port
            username: Client username for authentication
            password: Client password for authentication
            server_version: Minecraft server version reported in handshake (v2)
        """
        self._plugin = plugin
        self._host = host
        self._port = port
        self._username = username
        self._password = password
        self._server_version = server_version or ""
        
        self._reader: Optional[asyncio.StreamReader] = None
        self._writer: Optional[asyncio.StreamWriter] = None
        self._connected = False
        self._authenticated = False
        
        self._reconnect_delay = 5
        self._max_reconnect_delay = 60
        self._current_reconnect_delay = self._reconnect_delay
        
        self._packet_handlers: Dict[int, Callable[[Packet], None]] = {}
        self._keepalive_task: Optional[asyncio.Task] = None
        self._read_task: Optional[asyncio.Task] = None
        self._reconnect_task: Optional[asyncio.Task] = None
        # Set by disconnect(); blocks any new reconnect scheduling until the
        # next explicit connect() so plugin unload cannot leave a loop running.
        self._closing = False
        
        self._logger = logging.getLogger("NovaChat.Network")
    
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
        
        try:
            self._logger.info(f"Connecting to {self._host}:{self._port}...")
            
            self._reader, self._writer = await asyncio.open_connection(
                self._host, self._port
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
        running reconnect loop, and closes the connection. A later explicit
        :meth:`connect` re-arms the client.
        """
        self._closing = True
        self._logger.info("Disconnecting from backend...")
        self._cancel_reconnect_task()
        asyncio.create_task(self._close_connection())
    
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
        Perform authentication handshake.
        
        Returns:
            True if authentication succeeded
        """
        # Hash the password
        password_hash = hashlib.sha256(self._password.encode()).hexdigest()
        
        # Send handshake packet (protocol v2: includes server_version)
        handshake = HandshakePacket(
            protocol_version=self.PROTOCOL_VERSION,
            client_id=self._username,
            password_hash=password_hash,
            platform=self.PLATFORM_ENDSTONE,
            server_version=self._server_version,
        )
        
        await self.send_packet(handshake)
        
        # Wait for response
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
                self._logger.error("Unexpected response to handshake")
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
