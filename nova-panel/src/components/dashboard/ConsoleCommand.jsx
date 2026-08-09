/**
 * Console Command Component
 * Execute backend console commands via REST POST /api/console.
 *
 * Terminal-style UI: input + send button, output area with accumulated history.
 * Shows available commands and warns that stop/shutdown are blocked by the backend.
 * Supports up/down arrow command history navigation.
 */

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Terminal, Send, Trash2, Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import Card from '../ui/Card';
import Button from '../ui/Button';
import Badge from '../ui/Badge';
import { api } from '../../services/api';

function ConsoleCommand({ theme, mode, txtMain: _txtMain, txtSec: _txtSec }) {
  void _txtMain; void _txtSec;
  const { t } = useTranslation();
  const [input, setInput] = useState('');
  const [history, setHistory] = useState([]); // [{ command, output, error }]
  const [running, setRunning] = useState(false);
  const [cmdHistory, setCmdHistory] = useState([]); // past commands for up/down nav
  const [historyIdx, setHistoryIdx] = useState(-1);
  const outputRef = useRef(null);
  const inputRef = useRef(null);

  // Available commands (from backend ConsoleCommandHandler dispatch table).
  const availableCommands = [
    'help', 'status', 'players', 'clients', 'channels', 'channel',
    'mute', 'unmute', 'mutes', 'kick', 'announce', 'title',
    'reload', 'spy', 'spies', 'create', 'delete',
  ];
  const blockedCommands = ['stop', 'shutdown'];

  // Auto-scroll to bottom when new output arrives.
  useEffect(() => {
    if (outputRef.current) {
      outputRef.current.scrollTop = outputRef.current.scrollHeight;
    }
  }, [history, running]);

  const runCommand = useCallback(async () => {
    const cmd = input.trim();
    if (!cmd || running) return;
    setRunning(true);
    setCmdHistory((prev) => [...prev, cmd]);
    setHistoryIdx(-1);
    setInput('');
    try {
      const res = await api.runConsoleCommand(cmd);
      const output = (res && res.output) || '';
      setHistory((prev) => [...prev, { command: cmd, output, error: false }]);
    } catch (err) {
      const msg = (err && err.message) || String(err);
      setHistory((prev) => [...prev, { command: cmd, output: msg, error: true }]);
    } finally {
      setRunning(false);
      if (inputRef.current) inputRef.current.focus();
    }
  }, [input, running]);

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      runCommand();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (cmdHistory.length === 0) return;
      const newIdx = historyIdx < 0 ? cmdHistory.length - 1 : Math.max(0, historyIdx - 1);
      setHistoryIdx(newIdx);
      setInput(cmdHistory[newIdx] || '');
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (cmdHistory.length === 0 || historyIdx < 0) return;
      const newIdx = historyIdx + 1;
      if (newIdx >= cmdHistory.length) {
        setHistoryIdx(-1);
        setInput('');
      } else {
        setHistoryIdx(newIdx);
        setInput(cmdHistory[newIdx] || '');
      }
    }
  };

  const clearHistory = () => {
    setHistory([]);
    setCmdHistory([]);
    setHistoryIdx(-1);
  };

  const fillCommand = (cmd) => {
    setInput(cmd);
    if (inputRef.current) inputRef.current.focus();
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-medium text-foreground">{t('console.title')}</h2>
          <p className="text-xs text-muted-foreground mt-1">{t('console.subtitle')}</p>
        </div>
        {history.length > 0 && (
          <Button
            theme={theme}
            mode={mode}
            variant="ghost"
            onClick={clearHistory}
            className="text-destructive hover:text-destructive"
            title={t('console.clear_history')}
          >
            <Trash2 size={14} /> {t('console.clear_history')}
          </Button>
        )}
      </div>

      {/* Available Commands */}
      <Card className="p-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-muted-foreground flex items-center gap-1.5">
            <Terminal size={12} /> {t('console.available_commands')}:
          </span>
          <div className="flex flex-wrap gap-1.5">
            {availableCommands.map((cmd) => (
              <button
                key={cmd}
                onClick={() => fillCommand(cmd)}
                className="rounded-md bg-muted/60 px-2 py-0.5 text-[11px] font-mono text-foreground transition-colors hover:bg-primary hover:text-primary-foreground"
              >
                {cmd}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-1.5 ml-auto">
            {blockedCommands.map((cmd) => (
              <Badge key={cmd} variant="destructive" className="font-mono text-[10px]">
                {cmd}
              </Badge>
            ))}
            <span className="text-[11px] text-muted-foreground">{t('console.blocked_hint')}</span>
          </div>
        </div>
      </Card>

      {/* Terminal Output */}
      <Card className="p-0 overflow-hidden">
        <div
          ref={outputRef}
          className="h-[420px] overflow-y-auto p-4 space-y-2 font-mono text-xs bg-muted/20"
        >
          {history.length === 0 && !running ? (
            <div className="flex items-center justify-center h-full text-muted-foreground">
              <p>{t('console.output_empty')}</p>
            </div>
          ) : (
            history.map((entry, idx) => (
              <div key={idx} className="space-y-1">
                {/* Command line */}
                <div className="flex items-start gap-2">
                  <span className="text-emerald-600 dark:text-emerald-400 shrink-0">{t('console.prompt')}</span>
                  <span className="text-foreground break-all">{entry.command}</span>
                </div>
                {/* Output */}
                {entry.output && (
                  <pre className={`whitespace-pre-wrap break-all pl-4 ${entry.error ? 'text-destructive' : 'text-muted-foreground'}`}>
                    {entry.output}
                  </pre>
                )}
                {!entry.output && !entry.error && (
                  <pre className="whitespace-pre-wrap break-all pl-4 text-muted-foreground/60">
                    -
                  </pre>
                )}
              </div>
            ))
          )}
          {running && (
            <div className="flex items-center gap-2 text-muted-foreground">
              <Loader2 size={12} className="animate-spin" />
              <span>{t('console.sending')}</span>
            </div>
          )}
        </div>

        {/* Input */}
        <div className="p-3 border-t border-border">
          <div className="flex items-center gap-2">
            <span className="text-emerald-600 dark:text-emerald-400 font-mono text-xs shrink-0">{t('console.prompt')}</span>
            <input
              ref={inputRef}
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={t('console.input_placeholder')}
              className="flex-1 h-8 rounded-md border-0 bg-secondary/55 px-3 py-1 text-xs font-mono outline-none focus-visible:ring-1 focus-visible:ring-ring placeholder:text-muted-foreground text-foreground"
              disabled={running}
            />
            <Button
              theme={theme}
              mode={mode}
              size="icon"
              onClick={runCommand}
              disabled={running || !input.trim()}
              title={t('console.send')}
            >
              {running ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}

export default ConsoleCommand;
