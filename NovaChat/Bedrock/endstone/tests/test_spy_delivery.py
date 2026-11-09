from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock
import uuid

from novachat_endstone.chat.handler import ChatHandler
from novachat_endstone.protocol.buffer import PacketBuffer
from novachat_endstone.protocol.packet import PacketIds, SpyMessagePacket, decode_packet


def test_shared_wire_fixture_and_registered_targeted_delivery():
    body = bytes.fromhex((Path(__file__).parents[2] / "test-fixtures/spy-message.hex").read_text())
    buf = PacketBuffer(body)
    packet_id = buf.read_byte()
    request_id = buf.read_uuid()
    packet = decode_packet(packet_id, buf)
    assert isinstance(packet, SpyMessagePacket)
    assert str(packet.target_id) == "22222222-2222-2222-2222-222222222222"
    assert packet.message.placeholders == {"rank": "member"}
    encoded = PacketBuffer()
    encoded.write_byte(packet_id)
    encoded.write_uuid(request_id)
    packet.encode(encoded)
    assert encoded.get_bytes() == body

    target = SimpleNamespace(unique_id=packet.target_id, send_message=Mock())
    other = SimpleNamespace(unique_id=uuid.uuid4(), send_message=Mock())
    plugin = SimpleNamespace(server=SimpleNamespace(online_players=[target, other]))
    consumers = {}
    network = SimpleNamespace(register_handler=lambda key, consumer: consumers.__setitem__(key, consumer))
    handler = ChatHandler(plugin, network, SimpleNamespace())
    tasks = []
    handler._post_to_main_thread = tasks.append
    consumers[PacketIds.SPY_MESSAGE](packet)
    target.send_message.assert_not_called()
    tasks.pop()()
    target.send_message.assert_called_once_with("[SPY] [source/staff] Sender: observed")
    other.send_message.assert_not_called()
    plugin.server.online_players.remove(target)
    consumers[PacketIds.SPY_MESSAGE](packet)
    tasks.pop()()
    target.send_message.assert_called_once()
