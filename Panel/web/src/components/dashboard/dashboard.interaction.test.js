import '../../../_test/dom.mjs';
import test, { afterEach, mock } from 'node:test';
import assert from 'node:assert/strict';
import React, { useEffect } from 'react';
import { render, screen, fireEvent, waitFor, cleanup, act, within } from '@testing-library/react';
import i18n from '../../i18n.js';
import { api } from '../../services/api.js';
import { useSettings } from '../../hooks/useSettings.js';
import SettingsView from './SettingsView.jsx';
import ConfigPublishPanel from './ConfigPublishPanel.jsx';
import ModerationManagement from './ModerationManagement.jsx';
import AppealQueue from './AppealQueue.jsx';
import MessageHistory from './MessageHistory.jsx';

afterEach(() => { cleanup(); mock.restoreAll(); });
const t = (key, values) => i18n.t(key, values);
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const settings = (revision, filterEnabled = true) => ({ revision, filterEnabled, messageLogEnabled: true, crossServerChatEnabled: true });

function SettingsHarness({ sessionKey = 'test-session', toast = () => {} }) {
  const data = useSettings({ sessionKey, addToast: toast, t });
  const { fetchSettings } = data;
  useEffect(() => { void fetchSettings(); }, [fetchSettings]);
  return React.createElement(SettingsView, {
    theme: 'clean', mode: 'light', role: 'SUPER_ADMIN', settings: data.settings,
    settingsLoading: data.settingsLoading, onToggle: data.handleSettingToggle,
    onChange: data.handleSettingChange, setMode() {}, modeState: 'light',
  });
}

test('SettingsView consumes save revisions, disables repeated clicks and shows actual state after failure', async () => {
  mock.method(api, 'getSettings', async () => settings(10));
  const pending = deferred();
  const bases = [];
  mock.method(api, 'updateSettings', (body, base) => {
    bases.push(base);
    if (bases.length === 1) return pending.promise;
    if (bases.length === 2) return Promise.resolve(settings(12, true));
    return Promise.reject(new Error('Disk full'));
  });
  const toasts = [];
  render(React.createElement(SettingsHarness, { toast: (...args) => toasts.push(args) }));
  const control = screen.getByRole('switch', { name: t('common.settings_filter') });
  await waitFor(() => assert.equal(control.disabled, false));
  fireEvent.click(control);
  fireEvent.click(control);
  assert.equal(control.disabled, true);
  assert.deepEqual(bases, [10]);
  await act(async () => pending.resolve(settings(11, false)));
  assert.equal(control.getAttribute('aria-checked'), 'false');
  fireEvent.click(control);
  await waitFor(() => assert.equal(control.disabled, false));
  assert.deepEqual(bases, [10, 11]);
  fireEvent.click(control);
  await waitFor(() => assert.equal(control.disabled, false));
  assert.equal(control.getAttribute('aria-checked'), 'true');
  assert.ok(toasts.some(([message, type]) => type === 'error' && message.includes('Disk full')));
});

test('a changed backend session accepts its lower revision and ignores the previous session response', async () => {
  const old = deferred();
  let calls = 0;
  mock.method(api, 'getSettings', () => ++calls === 1 ? old.promise : Promise.resolve(settings(1, false)));
  const view = render(React.createElement(SettingsHarness, { sessionKey: 'old' }));
  view.rerender(React.createElement(SettingsHarness, { sessionKey: 'new' }));
  const control = screen.getByRole('switch', { name: t('common.settings_filter') });
  await waitFor(() => assert.equal(control.getAttribute('aria-checked'), 'false'));
  await act(async () => old.resolve(settings(99, true)));
  assert.equal(control.getAttribute('aria-checked'), 'false');
});

test('approval failure keeps the note and modal open and blocks duplicate submission', async () => {
  mock.method(api, 'listDrafts', async () => [{ id: 7, status: 'DRAFT', createdBy: 'other-admin' }]);
  mock.method(api, 'listBackups', async () => []);
  const pending = deferred();
  const approve = mock.method(api, 'approveDraft', () => pending.promise);
  render(React.createElement(ConfigPublishPanel, { role: 'SUPER_ADMIN', theme: 'clean', mode: 'light' }));
  fireEvent.click(await screen.findByRole('button', { name: t('configPublish.action_approve') }));
  const dialog = await screen.findByRole('dialog');
  const note = within(dialog).getByLabelText(t('configPublish.approve_note_label'));
  fireEvent.change(note, { target: { value: 'Checked server routing' } });
  const confirm = within(dialog).getByRole('button', { name: t('configPublish.approve_confirm') });
  fireEvent.click(confirm);
  fireEvent.click(confirm);
  assert.equal(approve.mock.callCount(), 1);
  await act(async () => pending.reject(Object.assign(new Error('Forbidden'), { status: 403 })));
  assert.equal(note.value, 'Checked server routing');
  assert.ok(within(dialog).getByText(t('configPublish.approve_self_error')));
  assert.equal(confirm.disabled, false);
});

test('moderation evidence 403 renders its failure in the opened case', async () => {
  const row = { caseId: 'case-7', status: 'OPEN', targetName: 'Alex', reasonText: 'Report content' };
  mock.method(api, 'listCases', async () => ({ items: [row], total: 1 }));
  mock.method(api, 'getCase', async () => row);
  mock.method(api, 'getCaseEvidence', async () => { throw Object.assign(new Error('Evidence denied'), { status: 403 }); });
  render(React.createElement(ModerationManagement, { role: 'SUPER_ADMIN', theme: 'clean', mode: 'light' }));
  fireEvent.click(await screen.findByText('case-7'));
  const dialog = await screen.findByRole('dialog');
  await waitFor(() => assert.ok(within(dialog).getByText(t('moderation.evidence_load_failed', { error: 'Evidence denied' }))));
  assert.ok(within(dialog).getByText('Report content'));
});

test('appeal self-review 403 keeps the decision and note and shows the actual refusal', async () => {
  mock.method(api, 'listAppeals', async () => ({ items: [{ appealId: 'appeal-7', caseId: 'case-7', status: 'PENDING' }], total: 1 }));
  const pending = deferred();
  const review = mock.method(api, 'reviewAppeal', () => pending.promise);
  render(React.createElement(AppealQueue, { role: 'SUPER_ADMIN', theme: 'clean', mode: 'light' }));
  fireEvent.click(await screen.findByRole('button', { name: t('appeals.review_button') }));
  const dialog = await screen.findByRole('dialog');
  const select = within(dialog).getByRole('button', { name: t('appeals.decision_label') });
  fireEvent.click(select);
  fireEvent.click(within(dialog).getByRole('option', { name: t('appeals.decision_approved') }));
  const note = within(dialog).getByPlaceholderText(t('appeals.review_note_placeholder'));
  fireEvent.change(note, { target: { value: 'Evidence reviewed' } });
  const submit = within(dialog).getByRole('button', { name: t('appeals.submit_review') });
  fireEvent.click(submit);
  fireEvent.click(submit);
  assert.equal(review.mock.callCount(), 1);
  await act(async () => pending.reject(Object.assign(new Error('Self review'), { status: 403 })));
  assert.ok(within(dialog).getByText(t('appeals.self_review_hint')));
  assert.equal(note.value, 'Evidence reviewed');
  assert.ok(select.textContent.includes(t('appeals.decision_approved')));
  assert.equal(submit.disabled, false);
});

test('MessageHistory ignores the previous mount request and Enter cannot duplicate a pending load', async () => {
  const old = deferred(), current = deferred();
  let calls = 0;
  const getMessages = mock.method(api, 'getMessages', () => ++calls === 1 ? old.promise : current.promise);
  render(React.createElement(React.StrictMode, null, React.createElement(MessageHistory, { theme: 'clean', mode: 'light' })));
  assert.equal(getMessages.mock.callCount(), 2);
  fireEvent.keyDown(screen.getByPlaceholderText(t('history.filter_keyword_placeholder')), { key: 'Enter' });
  assert.equal(getMessages.mock.callCount(), 2);
  await act(async () => current.resolve({ items: [{ id: 'new', content: 'Current response' }], total: 1 }));
  assert.ok(screen.getByText('Current response'));
  await act(async () => old.resolve({ items: [{ id: 'old', content: 'Outdated response' }], total: 1 }));
  assert.equal(screen.queryByText('Outdated response'), null);
  assert.ok(screen.getByText('Current response'));
});
