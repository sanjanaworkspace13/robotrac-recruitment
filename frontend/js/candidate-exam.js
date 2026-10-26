/*
 * Examination page controller.
 *
 * Source of truth = the server. This page only displays the server's state:
 *  - questions, their order and option order come from GET /api/candidate/attempt (same after refresh);
 *  - an answer counts only after the server confirms the save (the page never shows "Saved" before that);
 *  - remaining time is computed from the server's deadline and the server's clock (offset), and
 *    re-synchronised every minute; the server also enforces the deadline on its own.
 */
(function () {
  'use strict';

  var el = UI.el;
  var STATE_LABELS = {
    'not-visited': 'Not visited',
    'not-answered': 'Not answered',
    'answered': 'Answered',
    'marked': 'Marked for review',
    'answered-marked': 'Answered & marked for review'
  };

  var exam = {
    questions: [],
    current: 0,
    draft: null,          // option index selected but not yet saved (current question only)
    seq: 1,               // monotonically increasing sequence for updates
    deadlineMs: 0,
    offsetMs: 0,          // serverTime - clientTime
    busy: false,
    submitted: false,
    autoSubmitting: false,
    awayCount: 0,
    threshold: 3,
    timerHandle: null,
    syncHandle: null,
    monitor: null,
    lastAnnouncedMinute: null
  };

  var dom = {};

  document.addEventListener('DOMContentLoaded', init);

  async function init() {
    ['exam', 'qno', 'qsection', 'qtext', 'options', 'saveStatus', 'markBtn', 'clearBtn', 'unmarkBtn', 'prevBtn',
      'saveNextBtn', 'submitBtn', 'paletteGrid', 'legend', 'awayInfo', 'timer', 'timerValue', 'awayBanner',
      'awayText', 'awayDismiss', 'connBanner', 'connText', 'loadingOverlay', 'blockOverlay', 'blockTitle',
      'blockText', 'blockActions', 'fullscreenBtn', 'palette', 'paletteToggle', 'srTime'
    ].forEach(function (id) { dom[id] = document.getElementById(id); });

    var me;
    try {
      me = await Candidate.requireCandidate();
    } catch (e) {
      block('Cannot reach the server', e.message, [retryButton()]);
      return;
    }
    if (!me) return;

    var state;
    try {
      state = await Api.get('/api/candidate/attempt');
    } catch (e) {
      if (e.code === 'NO_ATTEMPT') { window.location.replace('candidate-instructions.html'); return; }
      if (e.status === 401) { sessionExpired(); return; }
      block('Cannot load the examination', e.message, [retryButton()]);
      return;
    }
    if (state.status !== 'IN_PROGRESS') { goSubmitted(); return; }

    applyState(state);
    wireEvents();
    dom.loadingOverlay.classList.add('hidden');
    dom.exam.classList.remove('hidden');
    renderLegend();
    open(firstUnansweredIndex());
    startTimer();

    exam.monitor = new ExamMonitor({
      onAck: function (ack) { setAwayCount(ack.awayCount, ack.warningThreshold); },
      onLeft: showAwayWarning,
      onFullscreenExit: function () { dom.fullscreenBtn.classList.remove('hidden'); },
      onClosed: goSubmitted
    });
    exam.monitor.start();
  }

  // ------------------------------------------------------------------ state

  function applyState(state) {
    exam.questions = state.questions;
    exam.threshold = state.awayWarningThreshold;
    exam.seq = exam.questions.reduce(function (m, q) { return Math.max(m, q.lastSeq || 0); }, 0) + 1;
    syncClock(state.serverNow, state.deadlineAt);
    setAwayCount(state.awayCount, state.awayWarningThreshold);
  }

  function syncClock(serverNow, deadlineAt) {
    exam.offsetMs = Date.parse(serverNow) - Date.now();
    exam.deadlineMs = Date.parse(deadlineAt);
  }

  function nextSeq() { return exam.seq++; }

  function statusOf(q) {
    var answered = q.selectedIndex !== null && q.selectedIndex !== undefined;
    if (answered && q.markedForReview) return 'answered-marked';
    if (q.markedForReview) return 'marked';
    if (answered) return 'answered';
    if (q.visited) return 'not-answered';
    return 'not-visited';
  }

  function firstUnansweredIndex() {
    for (var i = 0; i < exam.questions.length; i++) {
      if (exam.questions[i].selectedIndex === null || exam.questions[i].selectedIndex === undefined) return i;
    }
    return 0;
  }

  // ------------------------------------------------------------------ rendering

  function open(index) {
    if (index < 0 || index >= exam.questions.length) return;
    exam.current = index;
    exam.draft = null;
    var q = exam.questions[index];
    dom.qno.textContent = 'Question ' + q.number + ' of ' + exam.questions.length;
    dom.qsection.textContent = q.section;
    dom.qtext.textContent = q.text;
    renderOptions(q);
    renderStatusLine(q);
    renderButtons();
    renderPalette();
    if (!q.visited) {
      q.visited = true; // shown immediately; confirmed by the server below (not an answer)
      renderPalette();
      Api.post('/api/candidate/attempt/questions/' + q.number + '/visit').catch(handleBackgroundError);
    }
  }

  function renderOptions(q) {
    var fs = dom.options;
    Array.prototype.slice.call(fs.querySelectorAll('.option')).forEach(function (n) { n.remove(); });
    var shown = exam.draft !== null ? exam.draft : q.selectedIndex;
    q.options.forEach(function (text, i) {
      var id = 'opt-' + i;
      var input = el('input', { type: 'radio', name: 'answer', id: id, value: String(i) });
      input.checked = shown === i;
      input.addEventListener('change', function () {
        exam.draft = i;
        renderOptions(q);
        renderStatusLine(q);
        renderButtons();
        var again = document.getElementById(id);
        if (again) again.focus();
      });
      var label = el('label', { class: 'option' + (shown === i ? ' selected' : ''), for: id }, [
        input,
        el('span', { class: 'letter', 'aria-hidden': 'true', text: String.fromCharCode(65 + i) + '.' }),
        el('span', { class: 'otext', text: text })
      ]);
      fs.appendChild(label);
    });
  }

  function renderStatusLine(q, override) {
    var s = dom.saveStatus;
    UI.clear(s);
    s.className = 'save-status';
    if (override) {
      s.classList.add(override.kind);
      s.appendChild(document.createTextNode(override.text));
      if (override.retry) s.appendChild(override.retry);
      return;
    }
    if (exam.draft !== null && exam.draft !== q.selectedIndex) {
      s.classList.add('pending');
      s.textContent = 'Selection not saved yet — press Save & Next (or Mark for Review & Next) to save it.';
    } else if (q.selectedIndex !== null && q.selectedIndex !== undefined) {
      s.classList.add('ok');
      s.textContent = 'Answer saved: option ' + String.fromCharCode(65 + q.selectedIndex) + (q.markedForReview ? ' · marked for review' : '') + '.';
    } else if (q.markedForReview) {
      s.textContent = 'Marked for review · not answered.';
    } else {
      s.textContent = 'Not answered.';
    }
  }

  function renderButtons() {
    var q = exam.questions[exam.current];
    var disabled = exam.busy || exam.submitted;
    dom.prevBtn.disabled = disabled || exam.current === 0;
    dom.saveNextBtn.disabled = disabled;
    dom.markBtn.disabled = disabled;
    dom.clearBtn.disabled = disabled || (exam.draft === null && (q.selectedIndex === null || q.selectedIndex === undefined));
    dom.submitBtn.disabled = disabled;
    dom.unmarkBtn.classList.toggle('hidden', !q.markedForReview);
    dom.unmarkBtn.disabled = disabled;
    dom.saveNextBtn.textContent = exam.current === exam.questions.length - 1 ? 'Save' : 'Save & Next →';
  }

  function renderPalette() {
    var grid = dom.paletteGrid;
    UI.clear(grid);
    var lastSection = null;
    var row = null;
    exam.questions.forEach(function (q, i) {
      if (q.section !== lastSection) {
        lastSection = q.section;
        grid.appendChild(el('div', { class: 'sec-title', text: q.section }));
        row = el('div', { class: 'grid' });
        grid.appendChild(row);
      }
      var st = statusOf(q);
      var btn = el('button', {
        type: 'button',
        class: 'pal ' + st + (i === exam.current ? ' current' : ''),
        'aria-label': 'Question ' + q.number + ': ' + STATE_LABELS[st] + (i === exam.current ? ' (current)' : ''),
        'aria-current': i === exam.current ? 'true' : null,
        title: STATE_LABELS[st],
        text: String(q.number)
      });
      btn.addEventListener('click', function () {
        if (exam.busy) return;
        open(i);
        dom.qno.scrollIntoView({ block: 'nearest' });
      });
      row.appendChild(btn);
    });
    updateLegendCounts();
  }

  function renderLegend() {
    var lg = dom.legend;
    UI.clear(lg);
    Object.keys(STATE_LABELS).forEach(function (k) {
      lg.appendChild(el('span', {}, [
        el('span', { class: 'pal ' + k, 'aria-hidden': 'true', dataset: { legend: k } }),
        el('span', { class: 'cnt', dataset: { count: k }, text: STATE_LABELS[k] })
      ]));
    });
    updateLegendCounts();
  }

  function counts() {
    var c = { 'not-visited': 0, 'not-answered': 0, 'answered': 0, 'marked': 0, 'answered-marked': 0 };
    exam.questions.forEach(function (q) { c[statusOf(q)]++; });
    return c;
  }

  function updateLegendCounts() {
    var c = counts();
    Object.keys(c).forEach(function (k) {
      var badge = dom.legend.querySelector('[data-legend="' + k + '"]');
      if (badge) badge.textContent = String(c[k]);
    });
  }

  // ------------------------------------------------------------------ actions

  function wireEvents() {
    dom.saveNextBtn.addEventListener('click', saveAndNext);
    dom.markBtn.addEventListener('click', markAndNext);
    dom.clearBtn.addEventListener('click', clearResponse);
    dom.unmarkBtn.addEventListener('click', unmark);
    dom.prevBtn.addEventListener('click', function () { if (!exam.busy) open(exam.current - 1); });
    dom.submitBtn.addEventListener('click', confirmSubmit);
    dom.awayDismiss.addEventListener('click', function () { dom.awayBanner.classList.add('hidden'); });
    dom.fullscreenBtn.addEventListener('click', function () {
      if (document.documentElement.requestFullscreen) {
        document.documentElement.requestFullscreen().then(function () {
          if (exam.monitor) exam.monitor.wasFullscreen = true;
          dom.fullscreenBtn.classList.add('hidden');
        }).catch(function () { UI.toast('Full screen is not available in this browser.'); });
      }
    });
    dom.paletteToggle.addEventListener('click', function () {
      var collapsed = dom.palette.classList.toggle('collapsed');
      dom.paletteToggle.textContent = collapsed ? 'Show' : 'Hide';
      dom.paletteToggle.setAttribute('aria-expanded', String(!collapsed));
    });
    document.addEventListener('keydown', function (e) {
      // Number keys 1-4 choose an option when focus is not in a text field.
      if (exam.busy || exam.submitted || e.altKey || e.ctrlKey || e.metaKey) return;
      if (/^[1-4]$/.test(e.key) && document.activeElement && document.activeElement.tagName !== 'TEXTAREA') {
        var radio = document.getElementById('opt-' + (Number(e.key) - 1));
        if (radio) { radio.click(); }
      }
    });
  }

  async function saveAndNext() {
    var q = exam.questions[exam.current];
    var selection = exam.draft !== null ? exam.draft : q.selectedIndex;
    var hasSelection = selection !== null && selection !== undefined;
    if (hasSelection && (selection !== q.selectedIndex || q.markedForReview)) {
      var ok = await send(q, 'PUT', '/response', { selectedIndex: selection, markedForReview: false }, saveAndNext);
      if (!ok) return;
    }
    goNext();
  }

  async function markAndNext() {
    var q = exam.questions[exam.current];
    var selection = exam.draft !== null ? exam.draft : q.selectedIndex;
    var ok;
    if (selection !== null && selection !== undefined) {
      ok = await send(q, 'PUT', '/response', { selectedIndex: selection, markedForReview: true }, markAndNext);
    } else {
      ok = await send(q, 'POST', '/review', { markedForReview: true }, markAndNext);
    }
    if (ok) goNext();
  }

  async function clearResponse() {
    var q = exam.questions[exam.current];
    if (q.selectedIndex === null || q.selectedIndex === undefined) {
      exam.draft = null; // nothing saved - just clear the unsaved selection
      renderOptions(q); renderStatusLine(q); renderButtons();
      return;
    }
    if (await send(q, 'POST', '/clear', {}, clearResponse)) {
      exam.draft = null;
      renderOptions(q); renderStatusLine(q, { kind: 'ok', text: 'Response cleared at ' + UI.formatTime(new Date().toISOString()) + '.' });
      renderButtons();
    }
  }

  async function unmark() {
    var q = exam.questions[exam.current];
    if (await send(q, 'POST', '/review', { markedForReview: false }, unmark)) {
      renderStatusLine(q); renderButtons();
    }
  }

  function goNext() {
    if (exam.current < exam.questions.length - 1) {
      open(exam.current + 1);
    } else {
      renderOptions(exam.questions[exam.current]);
      renderPalette();
      renderButtons();
      UI.toast('This is the last question. Review your answers or press Submit Test.');
    }
  }

  /**
   * Sends one change to the server with safe retries (same sequence number, so a retry can never overwrite
   * a newer change). Resolves true only after the server confirmed it. After repeated failures the
   * candidate gets a Retry button that repeats the same action (with a new sequence number).
   */
  async function send(q, method, suffix, body, retryAction) {
    if (exam.busy || exam.submitted) return false;
    exam.busy = true;
    renderButtons();
    body.seq = nextSeq();
    renderStatusLine(q, { kind: 'pending', text: 'Saving…' });
    var path = '/api/candidate/attempt/questions/' + q.number + suffix;
    var delays = [0, 800, 2000, 4000];
    var lastError = null;
    for (var i = 0; i < delays.length; i++) {
      if (delays[i]) await new Promise(function (r) { setTimeout(r, delays[i]); });
      try {
        var res = method === 'PUT' ? await Api.put(path, body) : await Api.post(path, body);
        applyQuestionState(q, res);
        exam.busy = false;
        hideConnectionProblem();
        if (q === exam.questions[exam.current]) {
          renderStatusLine(q, { kind: 'ok', text: savedText(res) });
        }
        renderPalette();
        renderButtons();
        return true;
      } catch (e) {
        lastError = e;
        if (e.code === 'STALE_UPDATE' && e.details) {
          applyQuestionState(q, e.details);
          exam.busy = false;
          exam.draft = null;
          renderOptions(q);
          renderStatusLine(q, { kind: 'pending', text: 'This question was changed in another window. Showing the latest saved answer.' });
          renderPalette(); renderButtons();
          return false;
        }
        if (e.code === 'ATTEMPT_CLOSED') { exam.busy = false; goSubmitted(); return false; }
        if (e.status === 401) { exam.busy = false; sessionExpired(); return false; }
        var retryable = e.status === 0 || e.status === 429 || e.status >= 500 || e.code === 'BUSY';
        if (!retryable) break;
        showConnectionProblem('Connection problem — retrying to save your answer…');
      }
    }
    exam.busy = false;
    var retryBtn = el('button', { type: 'button', class: 'btn btn-secondary btn-sm', text: 'Retry' });
    retryBtn.addEventListener('click', function () { retryAction(); });
    renderStatusLine(q, { kind: 'err', text: 'Not saved: ' + (lastError ? lastError.message : 'unknown error') + ' ', retry: retryBtn });
    renderButtons();
    return false;
  }

  function applyQuestionState(q, s) {
    q.selectedIndex = s.selectedIndex === undefined ? null : s.selectedIndex;
    q.markedForReview = !!s.markedForReview;
    q.visited = !!s.visited;
    q.lastSeq = s.lastSeq;
    if (s.lastSeq >= exam.seq) exam.seq = s.lastSeq + 1;
    if (s.serverNow && typeof s.remainingSeconds === 'number') {
      exam.offsetMs = Date.parse(s.serverNow) - Date.now();
    }
  }

  function savedText(res) {
    var t = UI.formatTime(res.savedAt || res.serverNow);
    if (res.selectedIndex !== null && res.selectedIndex !== undefined) {
      return 'Saved at ' + t + ': option ' + String.fromCharCode(65 + res.selectedIndex) + (res.markedForReview ? ' · marked for review' : '') + '.';
    }
    return (res.markedForReview ? 'Marked for review' : 'Updated') + ' at ' + t + '.';
  }

  function handleBackgroundError(e) {
    if (e.code === 'ATTEMPT_CLOSED') goSubmitted();
    else if (e.status === 401) sessionExpired();
  }

  // ------------------------------------------------------------------ timer

  function remainingSeconds() {
    return Math.max(0, Math.floor((exam.deadlineMs - (Date.now() + exam.offsetMs)) / 1000));
  }

  function startTimer() {
    tick();
    exam.timerHandle = setInterval(tick, 500);
    exam.syncHandle = setInterval(resyncClock, (window.ROBOTRAC_CONFIG && window.ROBOTRAC_CONFIG.clockSyncMs) || 60000);
  }

  function tick() {
    var left = remainingSeconds();
    dom.timerValue.textContent = UI.formatClock(left);
    dom.timer.classList.toggle('warn', left <= 300 && left > 60);
    dom.timer.classList.toggle('danger', left <= 60);
    var minute = Math.ceil(left / 60);
    if ((minute === 5 || minute === 1) && exam.lastAnnouncedMinute !== minute && left > 0) {
      exam.lastAnnouncedMinute = minute;
      dom.srTime.textContent = minute + (minute === 1 ? ' minute' : ' minutes') + ' remaining.';
      UI.toast(minute + (minute === 1 ? ' minute' : ' minutes') + ' remaining.');
    }
    if (left <= 0 && !exam.autoSubmitting && !exam.submitted) {
      autoSubmit();
    }
  }

  async function resyncClock() {
    try {
      var c = await Api.get('/api/candidate/attempt/clock');
      if (c.status !== 'IN_PROGRESS') { goSubmitted(); return; }
      syncClock(c.serverNow, c.deadlineAt);
      hideConnectionProblem();
    } catch (e) {
      if (e.status === 401) sessionExpired();
      else if (e.status === 0) showConnectionProblem('Connection lost. Your saved answers are safe on the server; the timer keeps running.');
    }
  }

  // ------------------------------------------------------------------ submission

  async function confirmSubmit() {
    if (exam.busy || exam.submitted) return;
    var c = counts();
    var unanswered = c['not-visited'] + c['not-answered'] + c['marked'];
    var details = [
      'Answered: ' + (c['answered'] + c['answered-marked']) + ' (of which marked for review: ' + c['answered-marked'] + ')',
      'Not answered: ' + unanswered,
      'Time left: ' + UI.formatClock(remainingSeconds())
    ];
    var q = exam.questions[exam.current];
    if (exam.draft !== null && exam.draft !== q.selectedIndex) {
      details.push('Question ' + q.number + ' has a selection that is NOT saved and will not be counted.');
    }
    var ok = await UI.confirmDialog({
      title: 'Submit your test?',
      message: unanswered > 0
        ? 'You still have ' + unanswered + ' unanswered question(s). After submitting you cannot change any answer.'
        : 'After submitting you cannot change any answer.',
      details: details,
      confirmLabel: 'Submit test',
      cancelLabel: 'Go back'
    });
    if (!ok) return;
    await submit('MANUAL');
  }

  async function autoSubmit() {
    exam.autoSubmitting = true;
    block('Time is up', 'Submitting your test… Please do not close this window.', []);
    await submit('TIMER');
  }

  async function submit(reason) {
    if (exam.submitted) return;
    exam.busy = true;
    renderButtons();
    UI.setBusy(dom.submitBtn, true, 'Submitting…');
    var deadlineForRetries = Date.now() + 60000;
    while (true) {
      try {
        await Api.post('/api/candidate/attempt/submit', { reason: reason });
        goSubmitted();
        return;
      } catch (e) {
        if (e.code === 'ATTEMPT_CLOSED') { goSubmitted(); return; }
        if (e.status === 401) { sessionExpired(); return; }
        var retryable = e.status === 0 || e.status >= 500 || e.status === 429 || e.code === 'BUSY';
        if (!retryable || Date.now() > deadlineForRetries) {
          exam.busy = false;
          UI.setBusy(dom.submitBtn, false);
          renderButtons();
          if (reason === 'TIMER') {
            // The server finalises expired attempts by itself, so the candidate loses nothing.
            block('Time is up', 'We could not reach the server, but your test is closed and your saved answers are recorded automatically by the server. You may close this window.', []);
            exam.submitted = true;
          } else {
            UI.toast('Could not submit: ' + e.message, 'error');
          }
          return;
        }
        showConnectionProblem('Connection problem — retrying submission…');
        await new Promise(function (r) { setTimeout(r, 3000); });
      }
    }
  }

  function goSubmitted() {
    exam.submitted = true;
    if (exam.monitor) exam.monitor.stop();
    clearInterval(exam.timerHandle);
    clearInterval(exam.syncHandle);
    if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(function () {});
    window.location.replace('candidate-submitted.html');
  }

  // ------------------------------------------------------------------ warnings / overlays

  function setAwayCount(count, threshold) {
    exam.awayCount = count || 0;
    if (threshold) exam.threshold = threshold;
    dom.awayInfo.textContent = 'Times you left this exam window (recorded): ' + exam.awayCount + '.';
  }

  function showAwayWarning() {
    // The count shown is updated when the server acknowledges the event.
    setTimeout(function () {
      var text = 'You left the examination window. This has been recorded (total: ' + exam.awayCount + '). ';
      text += exam.awayCount >= exam.threshold
        ? 'You have reached the review threshold of ' + exam.threshold + '; HR will review this.'
        : 'Please stay on this tab until you submit. Repeated switching is reviewed by HR.';
      dom.awayText.textContent = text;
      dom.awayBanner.classList.remove('hidden');
    }, 400);
  }

  function showConnectionProblem(text) {
    dom.connText.textContent = text;
    dom.connBanner.classList.remove('hidden');
  }

  function hideConnectionProblem() {
    dom.connBanner.classList.add('hidden');
  }

  function block(title, text, actions) {
    dom.loadingOverlay.classList.add('hidden');
    dom.blockTitle.textContent = title;
    dom.blockText.textContent = text;
    UI.clear(dom.blockActions);
    actions.forEach(function (a) { dom.blockActions.appendChild(a); });
    dom.blockOverlay.classList.remove('hidden');
  }

  function retryButton() {
    var b = el('button', { type: 'button', class: 'btn btn-primary', text: 'Try again' });
    b.addEventListener('click', function () { window.location.reload(); });
    return b;
  }

  function sessionExpired() {
    exam.submitted = true; // stop sending; the attempt itself continues on the server
    if (exam.monitor) exam.monitor.stop();
    clearInterval(exam.timerHandle);
    clearInterval(exam.syncHandle);
    var b = el('button', { type: 'button', class: 'btn btn-primary', text: 'Log in again' });
    b.addEventListener('click', function () { window.location.href = 'candidate-login.html?reason=expired'; });
    block('Session expired', 'For your security you need to log in again. Your attempt, timer and saved answers are kept on the server — log in with the same details to continue.', [b]);
  }
})();
