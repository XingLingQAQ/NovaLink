function createL1Lifecycle({ snapshot, write, quit, cleanup, exit, timeoutMs, clock = globalThis }) {
  let finished = false;
  let started = false;
  let intentionalQuit = false;
  let sequenceComplete = false;
  let closed = false;
  let resolveInitialized, resolveCompleted;
  const initialized = new Promise(resolve => { resolveInitialized = resolve; });
  const completed = new Promise(resolve => { resolveCompleted = resolve; });
  const sleep = ms => new Promise(resolve => clock.setTimeout(resolve, ms)).then(() => {
    if (finished) throw new Error('L1 run finished');
  });
  const writeResults = extra => {
    const payload = { ...snapshot(), sequenceComplete, ...extra };
    write(payload);
    return payload;
  };
  const stop = (code, extra) => {
    if (finished) return;
    finished = true;
    intentionalQuit = true;
    clock.clearTimeout(timer);
    resolveInitialized(false);
    try { close(); }
    catch (error) {
      code = code || 1;
      extra = { ...extra, error: extra?.error || String(error) };
    }
    resolveCompleted(writeResults(extra));
    exit(code);
  };
  const close = () => {
    if (closed) return;
    closed = true;
    try { cleanup(); } finally { quit(); }
  };
  const fail = error => stop(1, { error: String(error) });
  const timer = clock.setTimeout(() => stop(2, { timedOut: true }), timeoutMs);
  return {
    initialized, completed, sleep, fail,
    disconnected: reason => { if (!intentionalQuit) fail(reason); },
    run: async (initialize, sequence) => {
      if (started || finished) return completed;
      started = true;
      try {
        await initialize();
        if (finished) return completed;
        resolveInitialized(true);
        await sequence();
        if (finished) return completed;
        sequenceComplete = true;
        writeResults();
        await sleep(1500);
        intentionalQuit = true;
        close();
        await sleep(1000);
        stop(0);
      } catch (error) {
        if (!finished) fail(error);
      }
      return completed;
    },
  };
}

module.exports = { createL1Lifecycle };
