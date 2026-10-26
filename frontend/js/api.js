/*
 * Small fetch wrapper used by every page.
 *  - Sends the session cookie (credentials: 'include').
 *  - Fetches the CSRF token once from /api/auth/csrf and sends it in the X-XSRF-TOKEN header on every
 *    state-changing request; refreshes it once automatically if the server says it expired.
 *  - Turns every failure into an ApiError with { status, code, message, details }.
 *    Network failures get code "NETWORK_ERROR" (status 0).
 * Nothing sensitive is stored in localStorage.
 */
(function () {
  'use strict';

  var base = (window.ROBOTRAC_CONFIG && window.ROBOTRAC_CONFIG.apiBase) || '';
  var csrf = null; // { headerName, token } kept in memory only

  function ApiError(status, code, message, details) {
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.message = message;
    this.details = details;
  }
  ApiError.prototype = Object.create(Error.prototype);

  var FRIENDLY = {
    NETWORK_ERROR: 'Cannot reach the server. Check your internet connection and try again.',
    INTERNAL_ERROR: 'Something went wrong on the server. Please try again.',
    DATABASE_UNAVAILABLE: 'The service is temporarily unavailable. Please try again shortly.',
    RATE_LIMITED: 'Too many attempts. Please wait a little and try again.'
  };

  async function loadCsrf(force) {
    if (csrf && !force) return csrf;
    var res = await fetch(base + '/api/auth/csrf', { credentials: 'include', cache: 'no-store' });
    if (!res.ok) throw new ApiError(res.status, 'CSRF_UNAVAILABLE', 'Could not start a secure session. Please reload the page.');
    csrf = await res.json();
    return csrf;
  }

  async function request(method, path, body, options) {
    options = options || {};
    var unsafe = method !== 'GET' && method !== 'HEAD';
    var attempt = 0;
    while (true) {
      attempt++;
      var headers = { 'Accept': 'application/json' };
      var payload;
      if (body instanceof FormData) {
        payload = body; // browser sets the multipart boundary
      } else if (body !== undefined && body !== null) {
        headers['Content-Type'] = 'application/json';
        payload = JSON.stringify(body);
      }
      var res;
      try {
        if (unsafe) {
          var token = await loadCsrf(false);
          headers[token.headerName] = token.token;
        }
        res = await fetch(base + path, {
          method: method,
          headers: headers,
          body: payload,
          credentials: 'include',
          cache: 'no-store',
          keepalive: !!options.keepalive
        });
      } catch (e) {
        if (e instanceof ApiError) throw e;
        throw new ApiError(0, 'NETWORK_ERROR', FRIENDLY.NETWORK_ERROR);
      }

      if (res.status === 204) return null;
      var data = null;
      var text = await res.text();
      if (text) {
        try { data = JSON.parse(text); } catch (ignore) { data = null; }
      }
      if (res.ok) return data;

      var code = (data && data.code) || ('HTTP_' + res.status);
      if (code === 'CSRF_INVALID' && attempt === 1) {
        await loadCsrf(true); // token rotated (e.g. after logout) - fetch a new one and retry once
        continue;
      }
      var message = (data && data.message) || FRIENDLY[code] || ('Request failed (' + res.status + ').');
      if (res.status === 429 && !(data && data.message)) message = FRIENDLY.RATE_LIMITED;
      if (res.status >= 500 && !(data && data.message)) message = FRIENDLY.INTERNAL_ERROR;
      throw new ApiError(res.status, code, message, data && (data.details || data.fieldErrors));
    }
  }

  /** Downloads a file (e.g. CSV export) and saves it with the given name. */
  async function download(path, fallbackName) {
    var res;
    try {
      res = await fetch(base + path, { credentials: 'include', cache: 'no-store' });
    } catch (e) {
      throw new ApiError(0, 'NETWORK_ERROR', FRIENDLY.NETWORK_ERROR);
    }
    if (!res.ok) {
      var data = null;
      try { data = await res.json(); } catch (ignore) { /* not json */ }
      throw new ApiError(res.status, (data && data.code) || 'HTTP_' + res.status, (data && data.message) || 'Download failed.');
    }
    var blob = await res.blob();
    var name = fallbackName;
    var cd = res.headers.get('Content-Disposition');
    var m = cd && /filename="?([^";]+)"?/i.exec(cd);
    if (m) name = m[1];
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 2000);
  }

  window.Api = {
    ApiError: ApiError,
    get: function (path) { return request('GET', path); },
    post: function (path, body, options) { return request('POST', path, body, options); },
    put: function (path, body) { return request('PUT', path, body); },
    del: function (path) { return request('DELETE', path); },
    upload: function (path, formData) { return request('POST', path, formData); },
    download: download,
    resetCsrf: function () { csrf = null; },
    me: function () { return request('GET', '/api/auth/me'); },
    logout: async function () {
      try { await request('POST', '/api/auth/logout'); } finally { csrf = null; }
    }
  };
})();
