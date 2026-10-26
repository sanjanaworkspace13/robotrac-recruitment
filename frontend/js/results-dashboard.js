/* HR: results list, attempt details with monitoring timeline, review notes and CSV export. */
(function () {
  'use strict';
  var el = UI.el;
  var state = { page: 0 };
  var REVIEW = { NOT_REVIEWED: ['Not reviewed', ''], REVIEWED_NO_CONCERN: ['No concern', 'green'], NEEDS_FOLLOW_UP: ['Follow up', 'amber'] };
  var FINALIZED = { CANDIDATE: 'Submitted by candidate', CLIENT_TIMER: 'Browser timer', SERVER_DEADLINE: 'Server at deadline' };

  document.addEventListener('DOMContentLoaded', async function () {
    try { await Admin.init(); } catch (e) { return; }
    var params = new URLSearchParams(window.location.search);
    try { await Admin.fillDriveSelect(document.getElementById('driveSel'), true); } catch (e) { Admin.handleError(e, notice()); }
    if (params.get('q')) document.getElementById('search').value = params.get('q');
    ['driveSel', 'statusSel', 'sortSel', 'dirSel'].forEach(function (id) {
      document.getElementById(id).addEventListener('change', function () { state.page = 0; load(); });
    });
    document.getElementById('search').addEventListener('input', UI.debounce(function () { state.page = 0; load(); }, 300));
    document.getElementById('exportBtn').addEventListener('click', exportCsv);
    document.getElementById('detailClose').addEventListener('click', function () { document.getElementById('detailDlg').close(); });
    load();
  });

  function notice() { return document.getElementById('notice'); }

  function filterParams() {
    var p = new URLSearchParams();
    var drive = document.getElementById('driveSel').value;
    var status = document.getElementById('statusSel').value;
    var q = document.getElementById('search').value.trim();
    if (drive) p.set('driveId', drive);
    if (status) p.set('status', status);
    if (q) p.set('q', q);
    return p;
  }

  async function load() {
    var p = filterParams();
    p.set('sort', document.getElementById('sortSel').value);
    p.set('dir', document.getElementById('dirSel').value);
    p.set('page', state.page);
    p.set('size', 25);
    var tbody = document.getElementById('rows');
    try {
      var res = await Api.get('/api/admin/results?' + p.toString());
      UI.clear(tbody);
      if (!res.items.length) tbody.appendChild(el('tr', {}, [el('td', { colspan: '9', class: 'empty', text: 'No attempts match these filters yet.' })]));
      res.items.forEach(function (r) {
        var view = el('button', { type: 'button', class: 'btn btn-secondary btn-sm', text: 'Details' });
        view.addEventListener('click', function () { openDetail(r.attemptId); });
        var rv = REVIEW[r.reviewStatus] || [r.reviewStatus, ''];
        tbody.appendChild(el('tr', {}, [
          el('td', {}, [el('div', { class: 'mono small', text: r.candidateEmail }), r.candidateName ? el('div', { class: 'small muted', text: r.candidateName }) : null]),
          el('td', { class: 'mono small', text: r.driveCode }),
          el('td', {}, [Admin.attemptBadge(r.status)]),
          el('td', { class: 'num', text: r.score === null ? '—' : Number(r.score) + ' / ' + Number(r.maxScore) }),
          el('td', { class: 'num', text: r.correct === null ? '—' : r.correct + ' / ' + r.incorrect + ' / ' + r.unanswered }),
          el('td', { class: 'num' }, [r.awayThresholdExceeded ? UI.badge(String(r.awayCount) + ' · review', 'amber') : String(r.awayCount)]),
          el('td', { class: 'small', text: UI.formatDateTime(r.submittedAt) }),
          el('td', {}, [UI.badge(rv[0], rv[1])]),
          el('td', {}, [view])
        ]));
      });
      UI.renderPager(document.getElementById('pager'), res.page, res.totalPages, res.totalItems, function (pg) { state.page = pg; load(); });
    } catch (e) { Admin.handleError(e, notice()); }
  }

  async function exportCsv() {
    var b = document.getElementById('exportBtn');
    UI.setBusy(b, true, 'Exporting…');
    try {
      await Api.download('/api/admin/results/export?' + filterParams().toString(), 'robotrac-results.csv');
    } catch (e) { Admin.handleError(e, notice()); }
    finally { UI.setBusy(b, false); }
  }

  async function openDetail(attemptId) {
    var dlg = document.getElementById('detailDlg');
    var body = UI.clear(document.getElementById('detailBody'));
    body.appendChild(el('p', { class: 'muted', text: 'Loading…' }));
    dlg.showModal();
    try {
      renderDetail(await Api.get('/api/admin/results/' + attemptId));
    } catch (e) {
      UI.clear(body);
      var n = el('div', { class: 'notice error' });
      body.appendChild(n);
      Admin.handleError(e, n);
    }
  }

  function renderDetail(d) {
    var s = d.summary;
    document.getElementById('detailTitle').textContent = s.candidateEmail + ' · ' + s.driveCode;
    var body = UI.clear(document.getElementById('detailBody'));

    body.appendChild(el('dl', { class: 'kv' }, [
      el('dt', { text: 'Candidate' }), el('dd', { text: (s.candidateName ? s.candidateName + ' · ' : '') + s.candidateEmail }),
      el('dt', { text: 'Drive' }), el('dd', { text: s.driveCode + ' — ' + s.driveName }),
      el('dt', { text: 'Status' }), el('dd', {}, [Admin.attemptBadge(s.status), s.finalizedBy ? ' ' + (FINALIZED[s.finalizedBy] || s.finalizedBy) : '']),
      el('dt', { text: 'Started' }), el('dd', { text: UI.formatDateTime(s.startedAt) }),
      el('dt', { text: 'Deadline' }), el('dd', { text: UI.formatDateTime(s.deadlineAt) }),
      el('dt', { text: 'Submitted' }), el('dd', { text: UI.formatDateTime(s.submittedAt) }),
      el('dt', { text: 'Score' }), el('dd', { text: s.score === null ? 'Not scored yet (attempt in progress)' : Number(s.score) + ' / ' + Number(s.maxScore) +
        ' (' + s.correct + ' correct, ' + s.incorrect + ' incorrect, ' + s.unanswered + ' unanswered)' }),
      el('dt', { text: 'Left the exam window' }), el('dd', {}, [String(s.awayCount) + ' time(s) · review threshold ' + d.awayWarningThreshold + ' ',
        s.awayThresholdExceeded ? UI.badge('At or above threshold', 'amber') : UI.badge('Below threshold', 'green')])
    ]));

    // Review
    var sel = el('select', { class: 'input', id: 'rv-status' });
    Object.keys(REVIEW).forEach(function (k) { sel.appendChild(el('option', { value: k, text: REVIEW[k][0] })); });
    sel.value = s.reviewStatus;
    var notes = el('textarea', { class: 'input', id: 'rv-notes', maxlength: '2000', placeholder: 'Notes for the hiring team (never shown to the candidate)' });
    notes.value = d.reviewNotes || '';
    var saveBtn = el('button', { type: 'button', class: 'btn btn-primary btn-sm', text: 'Save review' });
    var reviewNotice = el('div', { class: 'notice hidden', role: 'status' });
    saveBtn.addEventListener('click', async function () {
      UI.setBusy(saveBtn, true, 'Saving…');
      try {
        await Api.put('/api/admin/results/' + s.attemptId + '/review', { reviewStatus: sel.value, notes: notes.value });
        UI.showNotice(reviewNotice, 'success', 'Review saved.');
        load();
      } catch (e) { Admin.handleError(e, reviewNotice); }
      finally { UI.setBusy(saveBtn, false); }
    });
    body.appendChild(el('h3', { class: 'section-gap', text: 'HR review' }));
    body.appendChild(el('div', { class: 'form-grid' }, [
      el('div', { class: 'form-field' }, [el('label', { for: 'rv-status', text: 'Review status' }), sel]),
      el('div', { class: 'form-field full' }, [el('label', { for: 'rv-notes', text: 'Notes' }), notes]),
      el('div', { class: 'form-field full' }, [el('div', {}, [saveBtn]), reviewNotice])
    ]));

    // Monitoring timeline
    body.appendChild(el('h3', { class: 'section-gap', text: 'Exam-integrity timeline' }));
    body.appendChild(el('p', { class: 'small muted', text: 'Times are server times. "Browser" events are detected by the exam page; "Server" events are verified by the server. A lost focus can have innocent causes (notifications, address bar, system dialogs); treat counts as prompts for review.' }));
    if (!d.events.length) {
      body.appendChild(el('p', { class: 'empty', text: 'No events recorded.' }));
    } else {
      var etb = el('tbody');
      d.events.forEach(function (e) {
        etb.appendChild(el('tr', {}, [
          el('td', { class: 'small', text: UI.formatDateTime(e.serverTime) + ' ' + UI.formatTime(e.serverTime) }),
          el('td', {}, [e.away ? UI.badge(e.type, 'amber') : UI.badge(e.type, e.source === 'SERVER' ? 'blue' : '')]),
          el('td', { class: 'small', text: e.description }),
          el('td', { class: 'small', text: e.source === 'SERVER' ? 'Server' : 'Browser' }),
          el('td', { class: 'small muted', text: e.detail || '' })
        ]));
      });
      body.appendChild(el('div', { class: 'table-wrap' }, [el('table', { class: 'data' }, [
        el('thead', {}, [el('tr', {}, [el('th', { text: 'When' }), el('th', { text: 'Event' }), el('th', { text: 'Meaning' }), el('th', { text: 'Source' }), el('th', { text: 'Detail' })])]),
        etb])]));
    }

    // Answers
    body.appendChild(el('h3', { class: 'section-gap', text: 'Answers (HR only)' }));
    var qtb = el('tbody');
    d.questions.forEach(function (q) {
      var color = q.outcome === 'CORRECT' ? 'green' : (q.outcome === 'INCORRECT' ? 'red' : '');
      qtb.appendChild(el('tr', {}, [
        el('td', { class: 'num', text: String(q.number) }),
        el('td', { class: 'mono small', text: q.code }),
        el('td', { class: 'small' }, [q.section, el('br'), q.difficulty]),
        el('td', {}, [el('div', { class: 'small', text: q.text }), el('ul', { class: 'qopts' }, q.options.map(function (o) {
          return el('li', { class: o.charAt(0) === q.correctOption ? 'key' : '', text: o });
        }))]),
        el('td', { class: 'mono', text: q.selectedOption || '—' }),
        el('td', { class: 'mono', text: q.correctOption }),
        el('td', {}, [UI.badge(q.outcome, color), q.markedForReview ? el('div', {}, [UI.badge('Marked', 'purple')]) : null])
      ]));
    });
    body.appendChild(el('div', { class: 'table-wrap' }, [el('table', { class: 'data' }, [
      el('thead', {}, [el('tr', {}, [el('th', { class: 'num', text: '#' }), el('th', { text: 'ID' }), el('th', { text: 'Section' }), el('th', { text: 'Question' }),
        el('th', { text: 'Chosen' }), el('th', { text: 'Key' }), el('th', { text: 'Outcome' })])]),
      qtb])]));
  }
})();
