/*
 * Exam-integrity monitoring (browser side).
 *
 * What it can detect: this tab becoming hidden (switching tab, minimising), this window losing focus
 * (switching to another application or window), leaving full-screen, and the page being closed/reloaded.
 * What it CANNOT detect: phones or other devices, screenshots, a second monitor, other people, or
 * anything outside this browser. Events are evidence for HR review, not verdicts.
 *
 * Each real transition is reported ONCE:
 *   - switching tabs fires "blur" then "visibilitychange(hidden)"; a short delay on blur lets the hidden
 *     event win, so only TAB_HIDDEN is sent (not both);
 *   - returning fires "visibilitychange(visible)" and "focus"; only the first is sent.
 * Every event has a random id; retries reuse the id so the server never counts the same event twice.
 * The browser never sends counts - the server counts stored events and returns the total.
 */
(function () {
  'use strict';

  var BLUR_DELAY_MS = 250;
  var RETRY_MS = 5000;

  function uuid() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    var b = new Uint8Array(16);
    crypto.getRandomValues(b);
    return Array.prototype.map.call(b, function (x) { return ('0' + x.toString(16)).slice(-2); }).join('')
      .replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5');
  }

  function ExamMonitor(options) {
    this.onAck = options.onAck || function () {};
    this.onLeft = options.onLeft || function () {};
    this.onFullscreenExit = options.onFullscreenExit || function () {};
    this.onClosed = options.onClosed || function () {};
    this.away = false;
    this.blurTimer = null;
    this.queue = [];
    this.sending = false;
    this.active = false;
    this.wasFullscreen = false;
    this.retryTimer = null;
    this._bound = {
      visibility: this._onVisibility.bind(this),
      blur: this._onBlur.bind(this),
      focus: this._onFocus.bind(this),
      fullscreen: this._onFullscreen.bind(this),
      pagehide: this._onPageHide.bind(this)
    };
  }

  /** Call only after the server confirmed the attempt is IN_PROGRESS. */
  ExamMonitor.prototype.start = function () {
    if (this.active) return;
    this.active = true;
    document.addEventListener('visibilitychange', this._bound.visibility);
    window.addEventListener('blur', this._bound.blur);
    window.addEventListener('focus', this._bound.focus);
    document.addEventListener('fullscreenchange', this._bound.fullscreen);
    window.addEventListener('pagehide', this._bound.pagehide);
    this.record('PAGE_LOADED', null);
    // If the page was opened in the background (e.g. restored tab), record that immediately.
    if (document.hidden) { this.away = true; this.record('TAB_HIDDEN', 'hidden at load'); }
  };

  ExamMonitor.prototype.stop = function () {
    this.active = false;
    clearTimeout(this.blurTimer);
    clearTimeout(this.retryTimer);
    document.removeEventListener('visibilitychange', this._bound.visibility);
    window.removeEventListener('blur', this._bound.blur);
    window.removeEventListener('focus', this._bound.focus);
    document.removeEventListener('fullscreenchange', this._bound.fullscreen);
    window.removeEventListener('pagehide', this._bound.pagehide);
  };

  ExamMonitor.prototype._onVisibility = function () {
    if (document.hidden) {
      clearTimeout(this.blurTimer);
      if (!this.away) {
        this.away = true;
        this.record('TAB_HIDDEN', null);
      }
    } else if (this.away) {
      this.away = false;
      this.record('TAB_VISIBLE', null);
      this.onLeft();
    }
  };

  ExamMonitor.prototype._onBlur = function () {
    var self = this;
    if (this.away) return;
    clearTimeout(this.blurTimer);
    this.blurTimer = setTimeout(function () {
      if (!self.away && !document.hidden && !document.hasFocus()) {
        self.away = true;
        self.record('WINDOW_BLUR', null);
      }
    }, BLUR_DELAY_MS);
  };

  ExamMonitor.prototype._onFocus = function () {
    clearTimeout(this.blurTimer);
    if (this.away && !document.hidden) {
      this.away = false;
      this.record('WINDOW_FOCUS', null);
      this.onLeft();
    }
  };

  ExamMonitor.prototype._onFullscreen = function () {
    var isFs = !!document.fullscreenElement;
    if (this.wasFullscreen && !isFs) {
      this.record('FULLSCREEN_EXIT', null);
      this.onFullscreenExit();
    }
    this.wasFullscreen = isFs;
  };

  ExamMonitor.prototype._onPageHide = function () {
    // Best effort: the browser may not deliver this. keepalive lets the request outlive the page.
    var evt = { type: 'PAGE_UNLOAD', clientEventId: uuid(), clientTime: new Date().toISOString() };
    Api.post('/api/candidate/attempt/events', evt, { keepalive: true }).catch(function () {});
  };

  ExamMonitor.prototype.record = function (type, detail) {
    if (!this.active) return;
    this.queue.push({ type: type, clientEventId: uuid(), clientTime: new Date().toISOString(), detail: detail || undefined });
    this._flush();
  };

  ExamMonitor.prototype._flush = async function () {
    var self = this;
    if (this.sending || !this.queue.length) return;
    this.sending = true;
    try {
      while (this.queue.length && this.active) {
        var evt = this.queue[0];
        try {
          var ack = await Api.post('/api/candidate/attempt/events', evt);
          this.queue.shift();
          this.onAck(ack);
        } catch (e) {
          if (e.code === 'ATTEMPT_CLOSED') { this.queue = []; this.onClosed(); return; }
          if (e.status === 400 || e.status === 404) { this.queue.shift(); continue; } // not retryable
          // Network error, 429 or 5xx: keep the event (same id) and retry later.
          clearTimeout(this.retryTimer);
          this.retryTimer = setTimeout(function () { self._flush(); }, RETRY_MS);
          return;
        }
      }
    } finally {
      this.sending = false;
    }
  };

  window.ExamMonitor = ExamMonitor;
})();
