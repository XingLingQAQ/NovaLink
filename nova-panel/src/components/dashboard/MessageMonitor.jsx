/**
 * Real-time Message Monitor Component
 * Displays live chat messages from all connected servers
 * 
 * Requirements: 24.2 - Real-time message monitoring
 */

import React, { useRef, useEffect, useState } from 'react';
import { Volume2, VolumeX, Filter, Send, Trash2, Loader2 } from 'lucide-react';
import Card from '../ui/Card';
import Button from '../ui/Button';
import CustomSelect from '../ui/CustomSelect';

function MessageMonitor({
  theme,
  mode,
  txtMain,
  txtSec,
  messages = [],
  channels = [],
  onClearMessages,
  onSendMessage,
  chatContainerRef: externalChatContainerRef,
  consoleAutoScroll: externalAutoScroll,
  setConsoleAutoScroll: externalSetAutoScroll
}) {
  const [chatFilter, setChatFilter] = useState('all');
  const [serverFilter, setServerFilter] = useState('all');
  const [internalAutoScroll, setInternalAutoScroll] = useState(true);
  const autoScroll = externalAutoScroll !== undefined ? externalAutoScroll : internalAutoScroll;
  const setAutoScroll = externalSetAutoScroll || setInternalAutoScroll;
  const [messageInput, setMessageInput] = useState('');
  const [targetChannel, setTargetChannel] = useState('global');
  const [sending, setSending] = useState(false);
  const internalChatContainerRef = useRef(null);
  const chatContainerRef = externalChatContainerRef || internalChatContainerRef;

  // Default target channel to the first available channel id.
  useEffect(() => {
    if (channels.length > 0 && !channels.find((c) => c.id === targetChannel)) {
      setTargetChannel(channels[0].id);
    }
  }, [channels, targetChannel]);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    if (autoScroll && chatContainerRef.current) {
      chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
    }
  }, [messages, autoScroll, chatContainerRef]);

  // Filter messages
  const filteredMessages = messages.filter(m => {
    const channelMatch = chatFilter === 'all' || m.channel === chatFilter;
    const serverMatch = serverFilter === 'all' || m.server === serverFilter;
    return channelMatch && serverMatch;
  });

  // Get unique servers from messages
  const servers = [...new Set(messages.map(m => m.server))];

  // Handle send message
  const handleSendMessage = () => {
    if (messageInput.trim() && onSendMessage) {
      setSending(true);
      Promise.resolve(onSendMessage(targetChannel, messageInput.trim()))
        .then(() => setMessageInput(''))
        .finally(() => setSending(false));
    }
  };

  // Handle key press
  const handleKeyPress = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  return (
    <div className="space-y-4 animate-in fade-in duration-500">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className={`text-2xl font-bold ${txtMain}`}>实时控制台</h2>
          <p className={`text-sm ${txtSec} mt-1`}>监控全服聊天消息 · {filteredMessages.length} 条消息</p>
        </div>
        <div className="flex items-center gap-3 flex-wrap">
          {/* Channel Filter */}
          <div className="flex items-center gap-2">
            <Filter size={16} className={txtSec} />
            <CustomSelect 
              theme={theme} 
              mode={mode} 
              options={['all', ...channels.map(c => c.id)]} 
              defaultValue={chatFilter}
              onChange={setChatFilter}
            />
          </div>
          
          {/* Server Filter */}
          {servers.length > 0 && (
            <CustomSelect 
              theme={theme} 
              mode={mode} 
              options={['all', ...servers]} 
              defaultValue={serverFilter}
              onChange={setServerFilter}
            />
          )}

          {/* Auto-scroll Toggle */}
          <button 
            onClick={() => setAutoScroll(!autoScroll)}
            className={`p-2 rounded-lg transition-colors ${autoScroll ? 'bg-sky-500/20 text-sky-500' : (mode === 'dark' ? 'bg-white/10 text-white/50' : 'bg-slate-100 text-slate-400')}`}
            title={autoScroll ? '自动滚动: 开' : '自动滚动: 关'}
          >
            {autoScroll ? <Volume2 size={20} /> : <VolumeX size={20} />}
          </button>

          {/* Clear Messages */}
          {onClearMessages && (
            <Button 
              theme={theme} 
              mode={mode} 
              variant="ghost" 
              onClick={onClearMessages}
              className="text-rose-400 hover:text-rose-500"
            >
              <Trash2 size={16} />
            </Button>
          )}
        </div>
      </div>

      {/* Message Display */}
      <Card theme={theme} mode={mode} className="p-0 overflow-hidden">
        <div 
          ref={chatContainerRef}
          className="h-[500px] overflow-y-auto p-4 space-y-1 font-mono text-sm custom-scrollbar"
        >
          {filteredMessages.length === 0 ? (
            <div className={`flex items-center justify-center h-full ${txtSec}`}>
              <p>暂无消息</p>
            </div>
          ) : (
            filteredMessages.map((msg, idx) => (
              <MessageLine
                key={msg.id || idx}
                message={msg}
                txtMain={txtMain}
                txtSec={txtSec}
              />
            ))
          )}
        </div>

        {/* Message Input */}
        {onSendMessage && (
          <div className={`p-3 border-t ${mode === 'dark' ? 'border-white/10' : 'border-slate-200'}`}>
            <div className="flex items-center gap-2">
              <CustomSelect 
                theme={theme} 
                mode={mode} 
                options={channels.map(c => c.id)} 
                defaultValue={targetChannel}
                onChange={setTargetChannel}
              />
              <input
                type="text"
                value={messageInput}
                onChange={(e) => setMessageInput(e.target.value)}
                onKeyPress={handleKeyPress}
                placeholder="输入消息..."
                className={`flex-1 px-4 py-2 rounded-xl border outline-none focus:ring-2 transition-all ${
                  theme === 'clean' 
                    ? (mode === 'dark' ? 'bg-slate-700 border-slate-600 focus:ring-sky-500 text-white' : 'bg-white border-slate-200 focus:ring-sky-500 text-slate-900') 
                    : 'bg-white/10 border-white/20 focus:ring-white/50 text-white placeholder:text-white/30'
                }`}
              />
              <Button
                theme={theme}
                mode={mode}
                variant="primary"
                onClick={handleSendMessage}
                disabled={sending || !messageInput.trim()}
                className="px-3"
              >
                {sending ? <Loader2 size={18} className="animate-spin" /> : <Send size={18} />}
              </Button>
            </div>
          </div>
        )}
      </Card>

      {/* Statistics */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard 
          theme={theme} 
          mode={mode} 
          txtMain={txtMain} 
          txtSec={txtSec}
          label="总消息数"
          value={messages.length}
        />
        <StatCard 
          theme={theme} 
          mode={mode} 
          txtMain={txtMain} 
          txtSec={txtSec}
          label="Java 消息"
          value={messages.filter(m => m.platform === 'Java').length}
          color="text-emerald-400"
        />
        <StatCard 
          theme={theme} 
          mode={mode} 
          txtMain={txtMain} 
          txtSec={txtSec}
          label="Bedrock 消息"
          value={messages.filter(m => m.platform === 'Bedrock').length}
          color="text-amber-400"
        />
        <StatCard 
          theme={theme} 
          mode={mode} 
          txtMain={txtMain} 
          txtSec={txtSec}
          label="活跃服务器"
          value={servers.length}
          color="text-sky-400"
        />
      </div>
    </div>
  );
}

// Individual Message Line
function MessageLine({ message, txtMain, txtSec }) {
  const platformColor = message.platform === 'Bedrock' ? 'text-amber-400' : 'text-emerald-400';
  
  return (
    <div className={`flex items-start gap-2 p-2 rounded-lg hover:bg-white/5 transition-colors group`}>
      <span className={`${txtSec} shrink-0 text-xs`}>[{message.time}]</span>
      <span className="text-sky-400 shrink-0 text-xs">[{message.server}]</span>
      {message.channel && message.channel !== 'global' && (
        <span className="text-purple-400 shrink-0 text-xs">#{message.channel}</span>
      )}
      <span className={`${platformColor} shrink-0`}>
        {message.player}
      </span>
      <span className={txtSec}>:</span>
      <span className={`${txtMain} break-all`}>{message.content}</span>
    </div>
  );
}

// Statistics Card
function StatCard({ theme, mode, txtMain, txtSec, label, value, color }) {
  return (
    <Card theme={theme} mode={mode} className="p-4 text-center">
      <p className={`text-2xl font-bold ${color || txtMain}`}>{value}</p>
      <p className={`text-xs ${txtSec} mt-1`}>{label}</p>
    </Card>
  );
}

export default MessageMonitor;
