/* HR: question bank summary, validated workbook import, review/approval and safe editing. */
(function () {
  'use strict';
  var el = UI.el;
  var state = { page: 0 };
  var editingId = null;
  var STATUS = {
    NEEDS_REVIEW: ['Needs review', 'amber'], APPROVED: ['Approved', 'green'], FLAGGED: ['Flagged', 'red']
  };

  document.addEventListener('DOMContentLoaded', async function () {
    try { await Admin.init(); } catch (e) { return; }
    ['fSection', 'fDifficulty', 'fStatus'].forEach(function (id) {
      document.getElementById(id).addEventListener('change', function () { state.page = 0; loadQuestions(); });
    });
    document.getElementById('fSearch').addEventListener('input', UI.debounce(function () { state.page = 0; loadQuestions(); }, 300));
    document.getElementById('importBtn').addEventListener('click', importWorkbook);
    document.getElementById('editForm').addEventListener('submit', saveEdit);
    document.getElementById('editCancel').addEventListener('click', function () { document.getElementById('editDlg').close(); });
    loadSummary();
    loadQuestions();
  });

  async function loadSummary() {
    try {
      var s = await Api.get('/api/admin/questions/summary');
      var tiles = UI.clear(document.getElementById('tiles'));
      [['Questions in bank', s.total], ['Eligible for papers', s.eligible], ['Needs SME review', s.needsReview],
        ['Approved', s.approved], ['Flagged', s.flagged], ['Deactivated', s.inactive]].forEach(function (t) {
        tiles.appendChild(el('div', { class: 'tile' }, [el('div', { class: 'k', text: t[0] }), el('div', { class: 'v', text: String(t[1]) })]));
      });
      var bySection = {};
      s.cells.forEach(function (c) { (bySection[c.sectionLabel] = bySection[c.sectionLabel] || {})[c.difficulty] = c.eligible; });
      var table = el('table', { class: 'matrix' }, [
        el('thead', {}, [el('tr', {}, [el('th', { text: 'Section' }), el('th', { text: 'Easy' }), el('th', { text: 'Moderate' }), el('th', { text: 'Hard' }), el('th', { text: 'Total' })])])
      ]);
      var tbody = el('tbody');
      Object.keys(bySection).forEach(function (sec) {
        var r = bySection[sec];
        var total = (r.EASY || 0) + (r.MODERATE || 0) + (r.HARD || 0);
        tbody.appendChild(el('tr', {}, [el('td', { text: sec }), el('td', { text: String(r.EASY || 0) }), el('td', { text: String(r.MODERATE || 0) }),
          el('td', { text: String(r.HARD || 0) }), el('td', {}, [el('strong', { text: String(total) })])]));
      });
      table.appendChild(tbody);
      UI.clear(document.getElementById('matrix')).appendChild(table);
    } catch (e) { Admin.handleError(e, document.getElementById('notice')); }
  }

  async function loadQuestions() {
    var p = new URLSearchParams();
    p.set('page', state.page); p.set('size', 20);
    [['section', 'fSection'], ['difficulty', 'fDifficulty'], ['status', 'fStatus'], ['q', 'fSearch']].forEach(function (x) {
      var v = document.getElementById(x[1]).value.trim();
      if (v) p.set(x[0], v);
    });
    var tbody = document.getElementById('rows');
    try {
      var res = await Api.get('/api/admin/questions?' + p.toString());
      UI.clear(tbody);
      if (!res.items.length) tbody.appendChild(el('tr', {}, [el('td', { colspan: '6', class: 'empty', text: 'No questions match these filters.' })]));
      res.items.forEach(function (q) { tbody.appendChild(row(q)); });
      UI.renderPager(document.getElementById('pager'), res.page, res.totalPages, res.totalItems, function (pg) { state.page = pg; loadQuestions(); });
    } catch (e) { Admin.handleError(e, document.getElementById('notice')); }
  }

  function row(q) {
    var opts = el('ul', { class: 'qopts' });
    ['A', 'B', 'C', 'D'].forEach(function (L) {
      opts.appendChild(el('li', { class: q.correctOption === L ? 'key' : '', text: L + '. ' + q['option' + L] + (q.correctOption === L ? '  ✓' : '') }));
    });
    var more = el('details', { class: 'more' }, [el('summary', { text: 'Options, explanation and notes' }), opts,
      q.explanation ? el('p', { class: 'small', text: 'Explanation: ' + q.explanation }) : null,
      q.validationNotes ? el('p', { class: 'small muted', text: 'Notes: ' + q.validationNotes }) : null,
      el('p', { class: 'small muted', text: (q.sourceRow ? 'Workbook row ' + q.sourceRow + '. ' : '') + (q.usedInAttempts ? 'Used in attempts — cannot be edited.' : 'Not used in any attempt yet.') })
    ]);
    var st = STATUS[q.reviewStatus] || [q.reviewStatus, ''];
    var actions = el('div', { class: 'row-actions' });
    if (q.reviewStatus !== 'APPROVED') actions.appendChild(btn('Approve', 'btn-primary', function () { setReview(q, 'APPROVED'); }));
    if (q.reviewStatus !== 'FLAGGED') actions.appendChild(btn('Flag', 'btn-danger', function () { flag(q); }));
    if (!q.usedInAttempts) actions.appendChild(btn('Edit', 'btn-secondary', function () { openEdit(q); }));
    actions.appendChild(btn(q.active ? 'Deactivate' : 'Activate', 'btn-ghost', function () { setActive(q); }));
    return el('tr', {}, [
      el('td', { class: 'mono small', text: q.code }),
      el('td', {}, [el('div', { text: q.text }), more]),
      el('td', { class: 'small' }, [q.sectionLabel, el('br'), q.difficulty]),
      el('td', { class: 'mono', text: q.correctOption || '—' }),
      el('td', {}, [UI.badge(st[0], st[1]), q.active ? null : el('div', {}, [UI.badge('Inactive', '')])]),
      el('td', {}, [actions])
    ]);
  }

  function btn(text, kind, fn) {
    var b = el('button', { type: 'button', class: 'btn btn-sm ' + kind, text: text });
    b.addEventListener('click', fn);
    return b;
  }

  async function refreshAfter(promise, okMessage) {
    try {
      await promise;
      UI.toast(okMessage);
      loadSummary();
      loadQuestions();
    } catch (e) { Admin.handleError(e, document.getElementById('notice')); }
  }

  function setReview(q, status) {
    refreshAfter(Api.put('/api/admin/questions/' + q.id + '/review', { reviewStatus: status }), q.code + ' marked ' + status.toLowerCase().replace('_', ' ') + '.');
  }

  async function flag(q) {
    var res = await UI.confirmDialog({
      title: 'Flag ' + q.code + '?', message: 'Flagged questions are never placed in new papers until approved again.',
      confirmLabel: 'Flag question', danger: true, input: { label: 'Reason (optional)', placeholder: 'e.g. answer key needs SME check' }
    });
    if (!res.ok) return;
    refreshAfter(Api.put('/api/admin/questions/' + q.id + '/review', { reviewStatus: 'FLAGGED', note: res.value || null }), q.code + ' flagged.');
  }

  async function setActive(q) {
    if (q.active) {
      var ok = await UI.confirmDialog({ title: 'Deactivate ' + q.code + '?', message: 'It will not be used in new papers. Existing attempts and scores are unchanged.', confirmLabel: 'Deactivate' });
      if (!ok) return;
    }
    refreshAfter(Api.put('/api/admin/questions/' + q.id + '/active', { active: !q.active }), q.code + (q.active ? ' deactivated.' : ' activated.'));
  }

  function openEdit(q) {
    editingId = q.id;
    document.getElementById('editTitle').textContent = 'Edit ' + q.code;
    document.getElementById('e-text').value = q.text;
    document.getElementById('e-a').value = q.optionA;
    document.getElementById('e-b').value = q.optionB;
    document.getElementById('e-c').value = q.optionC;
    document.getElementById('e-d').value = q.optionD;
    document.getElementById('e-key').value = q.correctOption || 'A';
    document.getElementById('e-expl').value = q.explanation || '';
    UI.hideNotice(document.getElementById('editNotice'));
    document.getElementById('editDlg').showModal();
  }

  async function saveEdit(ev) {
    ev.preventDefault();
    var notice = document.getElementById('editNotice');
    var body = {
      text: document.getElementById('e-text').value.trim(),
      optionA: document.getElementById('e-a').value.trim(),
      optionB: document.getElementById('e-b').value.trim(),
      optionC: document.getElementById('e-c').value.trim(),
      optionD: document.getElementById('e-d').value.trim(),
      correctOption: document.getElementById('e-key').value,
      explanation: document.getElementById('e-expl').value.trim() || null
    };
    if (!body.text || !body.optionA || !body.optionB || !body.optionC || !body.optionD) {
      UI.showNotice(notice, 'error', 'The question and all four options are required.');
      return;
    }
    var save = document.getElementById('editSave');
    UI.setBusy(save, true, 'Saving…');
    try {
      var q = await Api.put('/api/admin/questions/' + editingId, body);
      document.getElementById('editDlg').close();
      UI.toast(q.reviewStatus === 'FLAGGED' ? 'Saved, but still flagged: ' + (q.validationNotes || '') : 'Saved. The question now needs review/approval.');
      loadSummary();
      loadQuestions();
    } catch (e) { Admin.handleError(e, notice); }
    finally { UI.setBusy(save, false); }
  }

  async function importWorkbook() {
    var input = document.getElementById('xlsxFile');
    var notice = document.getElementById('importNotice');
    var file = input.files[0];
    if (!file) { UI.showNotice(notice, 'error', 'Choose the .xlsx workbook first.'); return; }
    if (!/\.xlsx$/i.test(file.name)) { UI.showNotice(notice, 'error', 'Only Excel .xlsx files can be imported.'); return; }
    var fd = new FormData();
    fd.append('file', file);
    var b = document.getElementById('importBtn');
    UI.setBusy(b, true, 'Importing…');
    try {
      var r = await Api.upload('/api/admin/questions/import', fd);
      UI.showNotice(notice, r.flagged || r.rejected ? 'warning' : 'success',
        r.totalRows + ' rows read: ' + r.imported + ' imported, ' + r.flagged + ' imported but flagged for review, ' +
        r.rejected + ' rejected, ' + r.skippedExisting + ' already in the bank (unchanged).');
      renderImportRows(r.rows.filter(function (x) { return x.outcome !== 'SKIPPED_EXISTING'; }));
      input.value = '';
      loadSummary();
      loadQuestions();
    } catch (e) { Admin.handleError(e, notice); }
    finally { UI.setBusy(b, false); }
  }

  function renderImportRows(rows) {
    var box = UI.clear(document.getElementById('importRows'));
    if (!rows.length) return;
    var colors = { FLAGGED: 'amber', REJECTED: 'red', IMPORTED: 'green' };
    var tbody = el('tbody');
    rows.forEach(function (r) {
      tbody.appendChild(el('tr', {}, [el('td', { class: 'num', text: String(r.row) }), el('td', { class: 'mono small', text: r.code || '—' }),
        el('td', {}, [UI.badge(r.outcome, colors[r.outcome])]), el('td', { class: 'small', text: r.messages.join(' ') })]));
    });
    box.appendChild(el('div', { class: 'table-wrap' }, [el('table', { class: 'data' }, [
      el('thead', {}, [el('tr', {}, [el('th', { class: 'num', text: 'Row' }), el('th', { text: 'ID' }), el('th', { text: 'Outcome' }), el('th', { text: 'Details' })])]),
      tbody])]));
  }
})();
