/**
 * Real-time Message Monitor Component
 * Displays live chat messages from all connected servers.
 *
 * Restyled to the shadcn/ui reference idiom: Card message list + pill send
 * Button + CustomSelect filter. Subtle muted rows, token-driven colors.
 */

import React, { useRef, useEffect, useState } from 'react';
import { Volume2, VolumeX, Filter, Send, Trash2, Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';
import CustomSelect from '../ui/CustomSelect';
import { can } from '../../lib/permissions';

function MessageMonitor({
  theme,
  mode,
  txtMain: _txtMain,
  txtSec: _txtSec,
  messages = [],
  channels = [],
  onClearMessages,
  onSendMessage,
  chatContainerRef: externalChatContainerRef,
  consoleAutoScroll: externalAutoScroll,
  setConsoleAutoScroll: externalSetAutoScroll,
  role,
}) {
  void _txtMain; void _txtSec;
  const { t } = useTranslation();
  const canSend = can(role, 'messages.send');
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
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sync default selection with available channels
      setTargetChannel(channels[0].id);
    }
  }, [channels, targetChannel]);

  // Auto-scroll to bottom when new messages arrive.
  useEffect(() => {
    if (autoScroll && chatContainerRef.current) {
      chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
    }
  }, [messages, autoScroll, chatContainerRef]);

  // Filter messages.
  const filteredMessages = messages.filter((m) => {
    const channelMatch = chatFilter === 'all' || m.channel === chatFilter;
    const serverMatch = serverFilter === 'all' || m.server === serverFilter;
    return channelMatch && serverMatch;
  });

  // Get unique servers from messages.
  const servers = [...new Set(messages.map((m) => m.server))];

  // Handle send message.
  const handleSendMessage = () => {
    if (messageInput.trim() && onSendMessage) {
      setSending(true);
      Promise.resolve(onSendMessage(targetChannel, messageInput.trim()))
        .then((result) => { if (result) setMessageInput(''); })
        .finally(() => setSending(false));
    }
  };

  // Handle key press.
  const handleKeyPress = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('messages.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('messages.subtitle', { count: filteredMessages.length })}</p>
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          {/* Channel Filter */}
          <div className="flex items-center gap-1.5">
            <Filter size={14} className="text-muted-foreground" />
            <div className="w-28">
              <CustomSelect
                theme={theme}
                mode={mode}
                options={['all', ...channels.map((c) => c.id)]}
                defaultValue={chatFilter}
                onChange={setChatFilter}
              />
            </div>
          </div>

          {/* Server Filter */}
          {servers.length > 0 && (
            <div className="w-28">
              <CustomSelect
                theme={theme}
                mode={mode}
                options={['all', ...servers]}
                defaultValue={serverFilter}
                onChange={setServerFilter}
              />
            </div>
          )}

          {/* Auto-scroll Toggle */}
          <button
            onClick={() => setAutoScroll(!autoScroll)}
            className={`rounded-md p-1.5 transition-colors ${autoScroll ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground hover:text-foreground'}`}
            title={autoScroll ? t('messages.autoscroll_on') : t('messages.autoscroll_off')}
            aria-label={autoScroll ? t('messages.autoscroll_on') : t('messages.autoscroll_off')}
          >
            {autoScroll ? <Volume2 size={16} /> : <VolumeX size={16} />}
          </button>

          {/* Clear Messages */}
          {onClearMessages && (
            <Button
              theme={theme}
              mode={mode}
              variant="ghost"
              onClick={onClearMessages}
              className="text-destructive hover:text-destructive"
              title={t('messages.clear')}
              aria-label={t('messages.clear')}
            >
              <Trash2 size={14} />
            </Button>
          )}
        </div>
      </div>

      {/* Message Display */}
      <Card className="p-0 overflow-hidden">
        <div
          ref={chatContainerRef}
          className="h-[500px] overflow-y-auto p-4 space-y-1 font-mono text-xs"
        >
          {filteredMessages.length === 0 ? (
            <div className="flex items-center justify-center h-full text-muted-foreground">
              <p>{t('messages.empty')}</p>
            </div>
          ) : (
            filteredMessages.map((msg, idx) => (
              <MessageLine key={msg.id || idx} message={msg} />
            ))
          )}
        </div>

        {/* Message Input — hidden for read-only roles */}
        {onSendMessage && canSend && (
          <div className="p-3 border-t border-border">
            <div className="flex items-center gap-2">
              <div className="w-32">
                <CustomSelect
                  theme={theme}
                  mode={mode}
                  options={channels.map((c) => c.id)}
                  defaultValue={targetChannel}
                  onChange={setTargetChannel}
                />
              </div>
              <input
                type="text"
                value={messageInput}
                onChange={(e) => setMessageInput(e.target.value)}
                onKeyDown={handleKeyPress}
                placeholder={t('messages.input_placeholder')}
                className="flex-1 h-8 rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs outline-none focus-visible:ring-1 focus-visible:ring-ring placeholder:text-muted-foreground text-foreground"
              />
              <Button
                theme={theme}
                mode={mode}
                size="icon"
                onClick={handleSendMessage}
                disabled={sending || !messageInput.trim()}
                aria-label={t('messages.send')}
              >
                {sending ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}
              </Button>
            </div>
          </div>
        )}
      </Card>

      {/* Statistics */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard label={t('messages.stat_total')} value={messages.length} />
        <StatCard
          label={t('messages.stat_java')}
          value={messages.filter((m) => m.platform === 'Java').length}
          color="text-emerald-600 dark:text-emerald-400"
        />
        <StatCard
          label={t('messages.stat_bedrock')}
          value={messages.filter((m) => m.platform === 'Bedrock').length}
          color="text-amber-600 dark:text-amber-400"
        />
        <StatCard
          label={t('messages.stat_active_servers')}
          value={servers.length}
          color="text-sky-600 dark:text-sky-400"
        />
      </div>
    </div>
  );
}

// Individual Message Line
function MessageLine({ message }) {
  const platformColor = message.platform === 'Bedrock' ? 'text-amber-600 dark:text-amber-400' : 'text-emerald-600 dark:text-emerald-400';

  return (
    <div className="flex items-start gap-2 p-1.5 rounded-md hover:bg-muted/40 transition-colors">
      <span className="text-muted-foreground shrink-0">[{message.time}]</span>
      <span className="text-muted-foreground shrink-0">[{message.server}]</span>
      {message.channel && message.channel !== 'global' && (
        <span className="text-sky-600 dark:text-sky-400 shrink-0">#{message.channel}</span>
      )}
      <span className={`${platformColor} shrink-0 font-medium`}>{message.player}</span>
      <span className="text-muted-foreground">:</span>
      <span className="text-foreground break-all">{message.content}</span>
    </div>
  );
}

// Statistics Card
function StatCard({ label, value, color }) {
  return (
    <Card className="p-4 text-center">
      <p className={`text-2xl font-medium ${color || 'text-foreground'}`}>{value}</p>
      <p className="text-xs text-muted-foreground mt-1">{label}</p>
    </Card>
  );
}

export default MessageMonitor;
