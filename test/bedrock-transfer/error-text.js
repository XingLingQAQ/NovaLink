function redactText(value, environment = process.env) {
  const secrets = [environment.BT_BACKEND_SECRET, environment.BT_RCON_PASSWORD]
    .filter(secret => typeof secret === 'string' && secret.length)
    .sort((left, right) => right.length - left.length);
  let text = String(value);
  for (const secret of secrets) text = text.split(secret).join('[REDACTED]');
  return text;
}

function errorText(error, environment = process.env) {
  return redactText(error?.stack || error?.message || String(error), environment);
}

function redactFailureData(value) {
  if (typeof value === 'string') return redactText(value);
  if (Array.isArray(value)) return value.map(redactFailureData);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, redactFailureData(item)]));
  }
  return value;
}

module.exports = { redactText, errorText, redactFailureData };
