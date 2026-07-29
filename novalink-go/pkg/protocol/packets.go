package protocol

import "fmt"

// HandshakePacket is sent by clients to authenticate with the server.
type HandshakePacket struct {
	BasePacket
	ProtocolVersion int32
	ClientID        string
	PasswordHash    string
	Platform        byte
}

func (p *HandshakePacket) ID() byte { return PacketIDHandshake }

func (p *HandshakePacket) Encode(buf *PacketBuffer) error {
	if err := buf.WriteVarInt(p.ProtocolVersion); err != nil {
		return err
	}
	if err := buf.WriteString(p.ClientID); err != nil {
		return err
	}
	if err := buf.WriteString(p.PasswordHash); err != nil {
		return err
	}
	return buf.WriteByte(p.Platform)
}

func (p *HandshakePacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.ProtocolVersion, err = buf.ReadVarInt(); err != nil {
		return err
	}
	if p.ClientID, err = buf.ReadString(); err != nil {
		return err
	}
	if p.PasswordHash, err = buf.ReadString(); err != nil {
		return err
	}
	p.Platform, err = buf.ReadByte()
	return err
}

// HandshakeResponsePacket is sent by the server in response to a handshake.
type HandshakeResponsePacket struct {
	BasePacket
	Success    bool
	ErrorCode  string
	Message    string
}

func (p *HandshakeResponsePacket) ID() byte { return PacketIDHandshakeResponse }

func (p *HandshakeResponsePacket) Encode(buf *PacketBuffer) error {
	if err := buf.WriteBool(p.Success); err != nil {
		return err
	}
	if err := buf.WriteString(p.ErrorCode); err != nil {
		return err
	}
	return buf.WriteString(p.Message)
}

func (p *HandshakeResponsePacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.Success, err = buf.ReadBool(); err != nil {
		return err
	}
	if p.ErrorCode, err = buf.ReadString(); err != nil {
		return err
	}
	p.Message, err = buf.ReadString()
	return err
}

// ChatMessagePacket represents a chat message.
type ChatMessagePacket struct {
	BasePacket
	SenderID     [16]byte
	SenderName   string
	ClientID     string
	ChannelID    string
	Content      string
	Placeholders map[string]string
}

func (p *ChatMessagePacket) ID() byte { return PacketIDChatMessage }

func (p *ChatMessagePacket) Encode(buf *PacketBuffer) error {
	if err := buf.WriteUUID(p.SenderID); err != nil {
		return err
	}
	if err := buf.WriteString(p.SenderName); err != nil {
		return err
	}
	if err := buf.WriteString(p.ClientID); err != nil {
		return err
	}
	if err := buf.WriteString(p.ChannelID); err != nil {
		return err
	}
	if err := buf.WriteString(p.Content); err != nil {
		return err
	}
	// Write placeholders map
	if p.Placeholders == nil {
		return buf.WriteVarInt(0)
	}
	if err := buf.WriteVarInt(int32(len(p.Placeholders))); err != nil {
		return err
	}
	for key, value := range p.Placeholders {
		if err := buf.WriteString(key); err != nil {
			return err
		}
		if err := buf.WriteString(value); err != nil {
			return err
		}
	}
	return nil
}

func (p *ChatMessagePacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.SenderID, err = buf.ReadUUID(); err != nil {
		return err
	}
	if p.SenderName, err = buf.ReadString(); err != nil {
		return err
	}
	if p.ClientID, err = buf.ReadString(); err != nil {
		return err
	}
	if p.ChannelID, err = buf.ReadString(); err != nil {
		return err
	}
	if p.Content, err = buf.ReadString(); err != nil {
		return err
	}

	// Placeholders map is optional for some legacy clients.
	if buf.Len() <= 0 {
		p.Placeholders = map[string]string{}
		return nil
	}

	size, err := buf.ReadVarInt()
	if err != nil {
		p.Placeholders = map[string]string{}
		return nil
	}
	if size < 0 || size > 1000 {
		p.Placeholders = map[string]string{}
		return nil
	}

	p.Placeholders = make(map[string]string, size)
	for i := int32(0); i < size; i++ {
		key, err := buf.ReadString()
		if err != nil {
			return err
		}
		value, err := buf.ReadString()
		if err != nil {
			return err
		}
		p.Placeholders[key] = value
	}
	return nil
}

// ChannelActionPacket represents a channel action request.
type ChannelActionPacket struct {
	BasePacket
	Action    byte
	ChannelID string
	Password  string
	Extra     map[string]string
}

func (p *ChannelActionPacket) ID() byte { return PacketIDChannelAction }

func (p *ChannelActionPacket) Encode(buf *PacketBuffer) error {
	if err := buf.WriteByte(p.Action); err != nil {
		return err
	}
	if err := buf.WriteString(p.ChannelID); err != nil {
		return err
	}
	if err := buf.WriteString(p.Password); err != nil {
		return err
	}
	if p.Extra == nil {
		return buf.WriteVarInt(0)
	}
	if err := buf.WriteVarInt(int32(len(p.Extra))); err != nil {
		return err
	}
	for k, v := range p.Extra {
		if err := buf.WriteString(k); err != nil {
			return err
		}
		if err := buf.WriteString(v); err != nil {
			return err
		}
	}
	return nil
}

func (p *ChannelActionPacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.Action, err = buf.ReadByte(); err != nil {
		return err
	}
	if p.ChannelID, err = buf.ReadString(); err != nil {
		return err
	}
	if p.Password, err = buf.ReadString(); err != nil {
		return err
	}

	if buf.Len() <= 0 {
		p.Extra = map[string]string{}
		return nil
	}

	size, err := buf.ReadVarInt()
	if err != nil {
		return err
	}
	if size < 0 || size > 1000 {
		return fmt.Errorf("invalid extra map size: %d", size)
	}

	p.Extra = make(map[string]string, size)
	for i := int32(0); i < size; i++ {
		key, err := buf.ReadString()
		if err != nil {
			return err
		}
		value, err := buf.ReadString()
		if err != nil {
			return err
		}
		p.Extra[key] = value
	}
	return nil
}

// ChannelUpdatePacket notifies clients of channel changes.
type ChannelUpdatePacket struct {
	BasePacket
	Action      byte
	ChannelID   string
	ChannelJSON string
}

func (p *ChannelUpdatePacket) ID() byte { return PacketIDChannelUpdate }

func (p *ChannelUpdatePacket) Encode(buf *PacketBuffer) error {
	if err := buf.WriteByte(p.Action); err != nil {
		return err
	}
	if err := buf.WriteString(p.ChannelID); err != nil {
		return err
	}
	return buf.WriteString(p.ChannelJSON)
}

func (p *ChannelUpdatePacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.Action, err = buf.ReadByte(); err != nil {
		return err
	}
	if p.ChannelID, err = buf.ReadString(); err != nil {
		return err
	}
	p.ChannelJSON, err = buf.ReadString()
	return err
}

// AnnouncementPacket broadcasts an announcement to clients.
type AnnouncementPacket struct {
	BasePacket
	Type    byte
	Content string
}

func (p *AnnouncementPacket) ID() byte { return PacketIDAnnouncement }

func (p *AnnouncementPacket) Encode(buf *PacketBuffer) error {
	if err := buf.WriteByte(p.Type); err != nil {
		return err
	}
	return buf.WriteString(p.Content)
}

func (p *AnnouncementPacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.Type, err = buf.ReadByte(); err != nil {
		return err
	}
	p.Content, err = buf.ReadString()
	return err
}

// KeepAlivePacket is used to maintain connection liveness.
type KeepAlivePacket struct {
	BasePacket
	Timestamp int64
}

func (p *KeepAlivePacket) ID() byte { return PacketIDKeepAlive }

func (p *KeepAlivePacket) Encode(buf *PacketBuffer) error {
	return buf.WriteInt64(p.Timestamp)
}

func (p *KeepAlivePacket) Decode(buf *PacketBuffer) error {
	var err error
	p.Timestamp, err = buf.ReadInt64()
	return err
}

// TitleMessagePacket sends a title message to players.
type TitleMessagePacket struct {
	BasePacket

	ChannelID string
	Title     string
	Subtitle  string

	FadeIn  int32
	Stay    int32
	FadeOut int32

	// SenderID is the UUID of the sender (admin). Zero UUID means "system/console".
	SenderID [16]byte
}

func (p *TitleMessagePacket) ID() byte { return PacketIDTitle }

func (p *TitleMessagePacket) Encode(buf *PacketBuffer) error {
	if err := buf.WriteString(p.ChannelID); err != nil {
		return err
	}
	if err := buf.WriteString(p.Title); err != nil {
		return err
	}
	if err := buf.WriteString(p.Subtitle); err != nil {
		return err
	}
	if err := buf.WriteInt32(p.FadeIn); err != nil {
		return err
	}
	if err := buf.WriteInt32(p.Stay); err != nil {
		return err
	}
	if err := buf.WriteInt32(p.FadeOut); err != nil {
		return err
	}
	return buf.WriteUUID(p.SenderID)
}

func (p *TitleMessagePacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.ChannelID, err = buf.ReadString(); err != nil {
		return err
	}
	if p.Title, err = buf.ReadString(); err != nil {
		return err
	}
	if p.Subtitle, err = buf.ReadString(); err != nil {
		return err
	}
	if p.FadeIn, err = buf.ReadInt32(); err != nil {
		return err
	}
	if p.Stay, err = buf.ReadInt32(); err != nil {
		return err
	}
	if p.FadeOut, err = buf.ReadInt32(); err != nil {
		return err
	}
	p.SenderID, err = buf.ReadUUID()
	return err
}

// ChannelActionResponsePacket responds to a channel action request.
type ChannelActionResponsePacket struct {
	BasePacket

	Success   bool
	Action    byte
	ChannelID string
	ErrorCode string
	Message   string
	Extra     map[string]string
}

func (p *ChannelActionResponsePacket) ID() byte { return PacketIDChannelActionResponse }

func (p *ChannelActionResponsePacket) Encode(buf *PacketBuffer) error {
	if err := buf.WriteBool(p.Success); err != nil {
		return err
	}
	if err := buf.WriteByte(p.Action); err != nil {
		return err
	}
	if err := buf.WriteString(p.ChannelID); err != nil {
		return err
	}
	if err := buf.WriteString(p.ErrorCode); err != nil {
		return err
	}
	if err := buf.WriteString(p.Message); err != nil {
		return err
	}

	if p.Extra == nil {
		return buf.WriteVarInt(0)
	}
	if err := buf.WriteVarInt(int32(len(p.Extra))); err != nil {
		return err
	}
	for k, v := range p.Extra {
		if err := buf.WriteString(k); err != nil {
			return err
		}
		if err := buf.WriteString(v); err != nil {
			return err
		}
	}
	return nil
}

func (p *ChannelActionResponsePacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.Success, err = buf.ReadBool(); err != nil {
		return err
	}
	if p.Action, err = buf.ReadByte(); err != nil {
		return err
	}
	if p.ChannelID, err = buf.ReadString(); err != nil {
		return err
	}
	if p.ErrorCode, err = buf.ReadString(); err != nil {
		return err
	}
	if p.Message, err = buf.ReadString(); err != nil {
		return err
	}

	if buf.Len() <= 0 {
		p.Extra = map[string]string{}
		return nil
	}

	size, err := buf.ReadVarInt()
	if err != nil {
		p.Extra = map[string]string{}
		return nil
	}
	if size < 0 || size > 1000 {
		p.Extra = map[string]string{}
		return nil
	}

	p.Extra = make(map[string]string, size)
	for i := int32(0); i < size; i++ {
		key, err := buf.ReadString()
		if err != nil {
			return err
		}
		val, err := buf.ReadString()
		if err != nil {
			return err
		}
		p.Extra[key] = val
	}
	return nil
}

// ConfigSyncPacket synchronizes configuration to clients (hot-reload).
type ConfigSyncPacket struct {
	BasePacket

	ConfigJSON string
	Timestamp  int64
}

func (p *ConfigSyncPacket) ID() byte { return PacketIDConfigSync }

func (p *ConfigSyncPacket) Encode(buf *PacketBuffer) error {
	if p.ConfigJSON == "" {
		p.ConfigJSON = "{}"
	}
	if err := buf.WriteString(p.ConfigJSON); err != nil {
		return err
	}
	return buf.WriteInt64(p.Timestamp)
}

func (p *ConfigSyncPacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.ConfigJSON, err = buf.ReadString(); err != nil {
		return err
	}
	p.Timestamp, err = buf.ReadInt64()
	return err
}

// AdminActionPacket is used for admin operations (auth/spy/reload/status).
type AdminActionPacket struct {
	BasePacket

	Action       byte
	PlayerID     [16]byte
	PasswordHash string
	Target       string
	Extra        map[string]string
}

func (p *AdminActionPacket) ID() byte { return PacketIDAdminAction }

func (p *AdminActionPacket) Encode(buf *PacketBuffer) error {
	if err := buf.WriteByte(p.Action); err != nil {
		return err
	}
	if err := buf.WriteUUID(p.PlayerID); err != nil {
		return err
	}
	if err := buf.WriteString(p.PasswordHash); err != nil {
		return err
	}
	if err := buf.WriteString(p.Target); err != nil {
		return err
	}

	if p.Extra == nil {
		return buf.WriteVarInt(0)
	}
	if err := buf.WriteVarInt(int32(len(p.Extra))); err != nil {
		return err
	}
	for k, v := range p.Extra {
		if err := buf.WriteString(k); err != nil {
			return err
		}
		if err := buf.WriteString(v); err != nil {
			return err
		}
	}
	return nil
}

func (p *AdminActionPacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.Action, err = buf.ReadByte(); err != nil {
		return err
	}
	if p.PlayerID, err = buf.ReadUUID(); err != nil {
		return err
	}
	if p.PasswordHash, err = buf.ReadString(); err != nil {
		return err
	}
	if p.Target, err = buf.ReadString(); err != nil {
		return err
	}

	if buf.Len() <= 0 {
		p.Extra = map[string]string{}
		return nil
	}

	size, err := buf.ReadVarInt()
	if err != nil {
		return err
	}
	if size < 0 || size > 1000 {
		return fmt.Errorf("invalid extra map size: %d", size)
	}

	p.Extra = make(map[string]string, size)
	for i := int32(0); i < size; i++ {
		key, err := buf.ReadString()
		if err != nil {
			return err
		}
		val, err := buf.ReadString()
		if err != nil {
			return err
		}
		p.Extra[key] = val
	}
	return nil
}

// AdminActionResponsePacket responds to AdminActionPacket.
type AdminActionResponsePacket struct {
	BasePacket

	Action    byte
	Success   bool
	ErrorCode string
	Message   string
}

func (p *AdminActionResponsePacket) ID() byte { return PacketIDAdminActionResponse }

func (p *AdminActionResponsePacket) Encode(buf *PacketBuffer) error {
	if err := buf.WriteByte(p.Action); err != nil {
		return err
	}
	if err := buf.WriteBool(p.Success); err != nil {
		return err
	}
	if err := buf.WriteString(p.ErrorCode); err != nil {
		return err
	}
	return buf.WriteString(p.Message)
}

func (p *AdminActionResponsePacket) Decode(buf *PacketBuffer) error {
	var err error
	if p.Action, err = buf.ReadByte(); err != nil {
		return err
	}
	if p.Success, err = buf.ReadBool(); err != nil {
		return err
	}
	if p.ErrorCode, err = buf.ReadString(); err != nil {
		return err
	}
	p.Message, err = buf.ReadString()
	return err
}
