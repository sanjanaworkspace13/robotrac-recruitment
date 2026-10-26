/* Instructions page: shows the drive's real settings and starts the attempt only on explicit acknowledgement. */
(function () {
  'use strict';

  document.addEventListener('DOMContentLoaded', async function () {
    Candidate.wireLogout(false);
    var pageNotice = document.getElementById('pageNotice');
    var me;
    try {
      me = await Candidate.requireCandidate();
    } catch (e) {
      UI.showNotice(pageNotice, 'error', e.message);
      return;
    }
    if (!me) return;

    var info;
    try {
      info = await Api.get('/api/candidate/instructions');
    } catch (e) {
      if (e.status === 401) { window.location.replace('candidate-login.html?reason=expired'); return; }
      UI.showNotice(pageNotice, 'error', e.message);
      return;
    }
    if (info.attemptStatus === 'COMPLETED') { window.location.replace('candidate-submitted.html'); return; }
    render(info);

    var ack = document.getElementById('ack');
    var start = document.getElementById('startBtn');
    var hint = document.getElementById('startHint');
    var startNotice = document.getElementById('startNotice');
    if (info.attemptStatus === 'IN_PROGRESS') {
      start.textContent = 'Resume Examination';
      hint.textContent = 'Your attempt is already in progress. The timer has been running since you started.';
    }
    ack.addEventListener('change', function () { start.disabled = !ack.checked; });

    start.addEventListener('click', async function () {
      if (!ack.checked) return;
      UI.hideNotice(startNotice);
      if (info.attemptStatus === 'IN_PROGRESS') { window.location.href = 'candidate-exam.html'; return; }
      var ok = await UI.confirmDialog({
        title: 'Start the examination?',
        message: 'Your one attempt begins now and the ' + info.availableMinutes + '-minute timer starts on the server. It cannot be paused.',
        confirmLabel: 'Start now'
      });
      if (!ok) return;
      UI.setBusy(start, true, 'Preparing your paper…');
      try {
        await Api.post('/api/candidate/attempt/start');
        window.location.href = 'candidate-exam.html';
      } catch (e) {
        UI.setBusy(start, false);
        if (e.status === 401) { window.location.replace('candidate-login.html?reason=expired'); return; }
        if (e.code === 'ATTEMPT_CLOSED') { window.location.replace('candidate-submitted.html'); return; }
        var msg = e.message;
        if (e.code === 'DRIVE_NOT_STARTED' && e.details && e.details.startAt) {
          msg = 'This test opens on ' + UI.formatDateTime(e.details.startAt) + '. Please come back then.';
        }
        UI.showNotice(startNotice, 'error', msg);
      }
    });
  });

  function render(info) {
    var el = UI.el;
    document.getElementById('content').classList.remove('hidden');
    document.getElementById('ititle').textContent = info.driveName;
    document.getElementById('driveDesc').textContent = info.description || ('Drive code ' + info.driveCode);

    var facts = document.getElementById('facts');
    UI.clear(facts);
    [
      ['Questions', String(info.totalQuestions)],
      ['Total marks', String(Number(info.totalMarks))],
      ['Duration', info.durationMinutes + ' min'],
      ['Time available now', info.availableMinutes + ' min'],
      ['Window closes', UI.formatDateTime(info.windowEndsAt)]
    ].forEach(function (f) {
      facts.appendChild(el('div', { class: 'fact' }, [el('div', { class: 'k', text: f[0] }), el('div', { class: 'v', text: f[1] })]));
    });

    var marking = document.getElementById('marking');
    UI.clear(marking);
    marking.appendChild(el('li', { text: 'Correct answer: +' + Number(info.marksPerCorrect) + ' mark(s).' }));
    marking.appendChild(el('li', {
      text: info.negativeMarkingEnabled
        ? 'Incorrect answer: −' + Number(info.negativeMarks) + ' mark(s) (negative marking is ON).'
        : 'Incorrect answer: 0 marks (no negative marking).'
    }));
    marking.appendChild(el('li', { text: 'Unanswered question: 0 marks.' }));

    var sections = document.getElementById('sections');
    UI.clear(sections);
    (info.sections || []).forEach(function (s) { sections.appendChild(el('li', { text: s })); });

    document.getElementById('windowNote').textContent = info.availableMinutes < info.durationMinutes
      ? 'Note: the test window closes soon, so you will have ' + info.availableMinutes + ' minutes instead of ' + info.durationMinutes + '.'
      : 'You will have the full ' + info.durationMinutes + ' minutes once you start.';
    document.getElementById('thresholdNote').textContent =
      'Leaving the window ' + info.awayWarningThreshold + ' or more times is highlighted to HR for review. It does not automatically fail you.';
  }
})();
