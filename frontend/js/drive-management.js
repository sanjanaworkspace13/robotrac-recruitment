/* HR: create, edit, check, activate, deactivate and close recruitment drives. */
(function () {
  'use strict';
  var el = UI.el;
  var editing = null;   // DriveDto being edited, or null for a new drive
  var drives = [];

  var LOCKED_WHEN_ACTIVE = ['f-code', 'f-start', 'f-duration', 'f-easy', 'f-moderate', 'f-hard', 'f-marks',
    'f-balanced', 'f-shuffle', 'f-negative', 'f-negmarks'];

  document.addEventListener('DOMContentLoaded', async function () {
    try { await Admin.init(); } catch (e) { return; }
    document.getElementById('newBtn').addEventListener('click', function () { openForm(null); });
    document.getElementById('cancelBtn').addEventListener('click', closeForm);
    document.getElementById('driveForm').addEventListener('submit', save);
    document.getElementById('genPw').addEventListener('click', generatePassword);
    document.getElementById('showPw').addEventListener('click', togglePassword);
    document.getElementById('f-negative').addEventListener('change', function (e) {
      document.getElementById('f-negmarks').disabled = !e.target.checked || isLocked();
    });
    var refresh = UI.debounce(checkAvailability, 350);
    document.querySelectorAll('.mix').forEach(function (n) { n.addEventListener('input', refresh); n.addEventListener('change', refresh); });
    await loadList();
    if (window.location.hash === '#new') openForm(null);
  });

  function isLocked() { return editing && editing.status === 'ACTIVE'; }

  async function loadList() {
    var notice = document.getElementById('notice');
    try {
      drives = await Api.get('/api/admin/drives');
      renderList();
    } catch (e) { Admin.handleError(e, notice); }
  }

  function renderList() {
    var tbody = UI.clear(document.getElementById('driveRows'));
    if (!drives.length) {
      tbody.appendChild(el('tr', {}, [el('td', { colspan: '7', class: 'empty', text: 'No drives yet. Press "New drive" to create one.' })]));
      return;
    }
    drives.forEach(function (d) {
      var actions = el('div', { class: 'row-actions' });
      if (d.status !== 'CLOSED') actions.appendChild(button('Edit', 'btn-secondary', function () { openForm(d); }));
      actions.appendChild(button('Check questions', 'btn-ghost', function () { showAvailability(d); }));
      actions.appendChild(el('a', { class: 'btn btn-ghost btn-sm', href: 'candidate-management.html?driveId=' + d.id, text: 'Candidates' }));
      if (d.status === 'DRAFT') actions.appendChild(button('Activate', 'btn-primary', function () { activate(d); }));
      if (d.status === 'ACTIVE') actions.appendChild(button('Back to draft', 'btn-secondary', function () { deactivate(d); }));
      if (d.status !== 'CLOSED') actions.appendChild(button('Close', 'btn-danger', function () { closeDrive(d); }));
      if (d.status === 'CLOSED') actions.appendChild(el('a', { class: 'btn btn-ghost btn-sm', href: 'results-dashboard.html?driveId=' + d.id, text: 'Results' }));

      tbody.appendChild(el('tr', {}, [
        el('td', {}, [el('strong', { class: 'mono', text: d.code }), el('div', { class: 'small', text: d.name }),
          d.passwordSet ? null : el('div', {}, [UI.badge('No password set', 'amber')])]),
        el('td', {}, [Admin.phaseBadge(d.phase)]),
        el('td', { class: 'small' }, [UI.formatDateTime(d.startAt), el('br'), '→ ' + UI.formatDateTime(d.endAt)]),
        el('td', { class: 'small' }, [d.totalQuestions + ' Q · ' + d.durationMinutes + ' min', el('br'),
          d.easyCount + 'E / ' + d.moderateCount + 'M / ' + d.hardCount + 'H · ' + Number(d.totalMarks) + ' marks' +
          (d.negativeMarkingEnabled ? ' · −' + Number(d.negativeMarks) : '')]),
        el('td', { class: 'num', text: String(d.authorizedCount) }),
        el('td', { class: 'num', text: d.attemptsStarted + ' / ' + d.attemptsCompleted }),
        el('td', {}, [actions])
      ]));
    });
  }

  function button(text, kind, onClick) {
    var b = el('button', { type: 'button', class: 'btn btn-sm ' + kind, text: text });
    b.addEventListener('click', function () { onClick(b); });
    return b;
  }

  // ------------------------------------------------------------------ form

  function openForm(d) {
    editing = d;
    var card = document.getElementById('formCard');
    card.classList.remove('hidden');
    UI.hideNotice(document.getElementById('formNotice'));
    document.getElementById('formTitle').textContent = d ? 'Edit drive ' + d.code : 'New drive';
    var now = new Date();
    var start = d ? d.startAt : new Date(now.getTime() + 60 * 60000).toISOString();
    var end = d ? d.endAt : new Date(now.getTime() + 26 * 3600000).toISOString();
    setVal('f-name', d ? d.name : '');
    setVal('f-code', d ? d.code : '');
    setVal('f-desc', d ? (d.description || '') : '');
    setVal('f-start', UI.toLocalInput(start));
    setVal('f-end', UI.toLocalInput(end));
    setVal('f-duration', d ? d.durationMinutes : 40);
    setVal('f-threshold', d ? d.awayWarningThreshold : 3);
    setVal('f-easy', d ? d.easyCount : 5);
    setVal('f-moderate', d ? d.moderateCount : 15);
    setVal('f-hard', d ? d.hardCount : 10);
    setVal('f-marks', d ? Number(d.marksPerCorrect) : 1);
    document.getElementById('f-balanced').checked = d ? d.sectionBalanced : true;
    document.getElementById('f-shuffle').checked = d ? d.shuffleOptions : true;
    document.getElementById('f-negative').checked = d ? d.negativeMarkingEnabled : false;
    setVal('f-negmarks', d && d.negativeMarkingEnabled ? Number(d.negativeMarks) : 0.25);
    setVal('f-password', '');
    document.getElementById('f-password').placeholder = d ? 'Leave empty to keep the current password' : 'Set the password candidates will use';
    document.getElementById('pwState').textContent = d ? (d.passwordSet ? '(a password is set)' : '(not set yet)') : '';

    var locked = isLocked();
    LOCKED_WHEN_ACTIVE.forEach(function (id) { document.getElementById(id).disabled = locked; });
    document.getElementById('f-negmarks').disabled = locked || !document.getElementById('f-negative').checked;
    var note = document.getElementById('lockedNote');
    if (locked) {
      UI.showNotice(note, 'warning', 'This drive is active. Only the name, description, end time, warning threshold and password can change. To change the paper, move it back to draft (only possible before anyone has started).');
    } else {
      UI.hideNotice(note);
    }
    checkAvailability();
    card.scrollIntoView({ behavior: 'smooth', block: 'start' });
    document.getElementById('f-name').focus();
  }

  function closeForm() {
    editing = null;
    document.getElementById('formCard').classList.add('hidden');
    if (window.location.hash) history.replaceState(null, '', window.location.pathname);
  }

  function setVal(id, v) { document.getElementById(id).value = v; }
  function num(id) { var v = document.getElementById(id).value; return v === '' ? null : Number(v); }

  function collect() {
    var pw = document.getElementById('f-password').value;
    return {
      name: document.getElementById('f-name').value.trim(),
      code: document.getElementById('f-code').value.trim().toUpperCase(),
      description: document.getElementById('f-desc').value.trim() || null,
      // Locked fields of an active drive are sent back exactly as stored (no minute rounding).
      startAt: isLocked() ? editing.startAt : UI.fromLocalInput(document.getElementById('f-start').value),
      endAt: UI.fromLocalInput(document.getElementById('f-end').value),
      durationMinutes: num('f-duration'),
      easyCount: num('f-easy'),
      moderateCount: num('f-moderate'),
      hardCount: num('f-hard'),
      sectionBalanced: document.getElementById('f-balanced').checked,
      shuffleOptions: document.getElementById('f-shuffle').checked,
      marksPerCorrect: num('f-marks'),
      negativeMarkingEnabled: document.getElementById('f-negative').checked,
      negativeMarks: document.getElementById('f-negative').checked ? num('f-negmarks') : 0,
      awayWarningThreshold: num('f-threshold'),
      drivePassword: pw ? pw : null
    };
  }

  function validate(body) {
    var problems = [];
    if (!body.name) problems.push('Enter a drive name.');
    if (!/^[A-Z0-9][A-Z0-9-]{3,19}$/.test(body.code)) problems.push('Drive code: 4-20 letters, digits or hyphens.');
    if (!body.startAt || !body.endAt) problems.push('Enter both window times.');
    else if (Date.parse(body.endAt) <= Date.parse(body.startAt)) problems.push('The window must close after it opens.');
    else if (Date.parse(body.endAt) - Date.parse(body.startAt) < (body.durationMinutes || 0) * 60000) problems.push('The window must be at least as long as the duration.');
    if ((body.easyCount || 0) + (body.moderateCount || 0) + (body.hardCount || 0) < 1) problems.push('The paper needs at least one question.');
    if (body.drivePassword && (body.drivePassword.length < 8 || !/[A-Za-z]/.test(body.drivePassword) || !/\d/.test(body.drivePassword))) {
      problems.push('Drive password: at least 8 characters with letters and digits.');
    }
    return problems;
  }

  async function save(ev) {
    ev.preventDefault();
    var notice = document.getElementById('formNotice');
    UI.hideNotice(notice);
    var body = collect();
    var problems = validate(body);
    if (problems.length) { UI.showNotice(notice, 'error', 'Please fix the following:', problems); return; }
    var btn = document.getElementById('saveBtn');
    UI.setBusy(btn, true, 'Saving…');
    try {
      var saved = editing ? await Api.put('/api/admin/drives/' + editing.id, body) : await Api.post('/api/admin/drives', body);
      UI.setBusy(btn, false);
      closeForm();
      await loadList();
      var msg = 'Drive ' + saved.code + ' saved.';
      if (body.drivePassword) msg += ' Remember to share the drive code and password with authorised candidates only.';
      UI.showNotice(document.getElementById('notice'), 'success', msg);
    } catch (e) {
      UI.setBusy(btn, false);
      Admin.handleError(e, notice);
    }
  }

  function generatePassword() {
    // Unambiguous characters (no 0/O, 1/l/I) so it can be read out or typed easily.
    var letters = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
    var digits = '23456789';
    var all = letters + digits;
    var bytes = new Uint32Array(12);
    crypto.getRandomValues(bytes);
    var pw = '';
    for (var i = 0; i < bytes.length; i++) pw += all[bytes[i] % all.length];
    if (!/\d/.test(pw)) pw = pw.slice(0, 11) + digits[bytes[0] % digits.length];
    if (!/[A-Za-z]/.test(pw)) pw = letters[bytes[1] % letters.length] + pw.slice(1);
    var input = document.getElementById('f-password');
    input.value = pw;
    input.type = 'text';
    document.getElementById('showPw').textContent = 'Hide';
    document.getElementById('showPw').setAttribute('aria-pressed', 'true');
    input.select();
    UI.toast('Password generated. Copy it now — it cannot be shown again after saving.');
  }

  function togglePassword() {
    var input = document.getElementById('f-password');
    var btn = document.getElementById('showPw');
    var show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    btn.textContent = show ? 'Hide' : 'Show';
    btn.setAttribute('aria-pressed', String(show));
  }

  // ------------------------------------------------------------------ availability

  async function checkAvailability() {
    var box = document.getElementById('availability');
    var easy = num('f-easy') || 0, moderate = num('f-moderate') || 0, hard = num('f-hard') || 0;
    var balanced = document.getElementById('f-balanced').checked;
    try {
      var a = await Api.get('/api/admin/drives/availability-preview?easy=' + easy + '&moderate=' + moderate +
        '&hard=' + hard + '&balanced=' + balanced);
      renderAvailability(box, a);
    } catch (e) {
      UI.clear(box).appendChild(el('span', { class: 'field-error', text: e.message }));
    }
  }

  async function showAvailability(d) {
    var notice = document.getElementById('notice');
    try {
      var a = await Api.get('/api/admin/drives/' + d.id + '/availability');
      var holder = el('div');
      renderAvailability(holder, a);
      var dlg = el('dialog', { class: 'modal wide', 'aria-labelledby': 'avTitle' }, [
        el('div', { class: 'modal-head' }, [el('h2', { id: 'avTitle', text: 'Question availability · ' + d.code })]),
        el('div', { class: 'modal-body' }, [holder]),
        el('div', { class: 'modal-foot' }, [button('Close', 'btn-secondary', function () { dlg.close(); dlg.remove(); })])
      ]);
      document.body.appendChild(dlg);
      dlg.showModal();
    } catch (e) { Admin.handleError(e, notice); }
  }

  function renderAvailability(box, a) {
    UI.clear(box);
    box.className = 'small';
    var status = a.feasible
      ? el('div', { class: 'notice' }, ['Possible: the bank can supply ' + a.requested + ' questions with this mix (' + a.totalEligible + ' eligible in total).'])
      : el('div', { class: 'notice error' }, [a.problems.join(' ')]);
    box.appendChild(status);
    if (a.needsReviewInPool > 0) {
      box.appendChild(el('p', { class: 'muted', text: a.needsReviewInPool + ' eligible question(s) are still marked "Needs SME review" in the workbook. They can be used, but approve them in the Question Bank before a live drive.' }));
    }
    var table = el('table', { class: 'matrix' });
    table.appendChild(el('thead', {}, [el('tr', {}, [el('th', { text: 'Section' }), el('th', { text: 'Easy' }), el('th', { text: 'Moderate' }), el('th', { text: 'Hard' })])]));
    var tbody = el('tbody');
    var bySection = {};
    a.cells.forEach(function (c) { (bySection[c.sectionLabel] = bySection[c.sectionLabel] || {})[c.difficulty] = c; });
    Object.keys(bySection).forEach(function (s) {
      var tr = el('tr', {}, [el('td', { text: s })]);
      ['EASY', 'MODERATE', 'HARD'].forEach(function (d) {
        var c = bySection[s][d];
        var text = c.available + ' available' + (c.planned !== null && c.planned !== undefined ? ' · example paper: ' + c.planned : '');
        tr.appendChild(el('td', { class: c.available === 0 ? 'short' : '', text: text }));
      });
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    box.appendChild(el('div', { class: 'table-wrap section-gap' }, [table]));
  }

  // ------------------------------------------------------------------ lifecycle

  async function activate(d) {
    var ok = await UI.confirmDialog({
      title: 'Activate ' + d.code + '?',
      message: 'Authorised candidates will be able to log in from ' + UI.formatDateTime(d.startAt) + ' until ' + UI.formatDateTime(d.endAt) + '.',
      confirmLabel: 'Activate'
    });
    if (!ok) return;
    var notice = document.getElementById('notice');
    try {
      var res = await Api.post('/api/admin/drives/' + d.id + '/activate');
      await loadList();
      UI.showNotice(notice, res.warnings.length ? 'warning' : 'success', 'Drive ' + d.code + ' is active.', res.warnings);
    } catch (e) { Admin.handleError(e, notice); }
  }

  async function deactivate(d) {
    var ok = await UI.confirmDialog({
      title: 'Move ' + d.code + ' back to draft?',
      message: 'Candidates will not be able to log in until you activate it again. This is only possible if nobody has started the test.',
      confirmLabel: 'Back to draft'
    });
    if (!ok) return;
    var notice = document.getElementById('notice');
    try {
      await Api.post('/api/admin/drives/' + d.id + '/deactivate');
      await loadList();
      UI.showNotice(notice, 'success', 'Drive ' + d.code + ' is back in draft.');
    } catch (e) { Admin.handleError(e, notice); }
  }

  async function closeDrive(d) {
    var ok = await UI.confirmDialog({
      title: 'Close ' + d.code + ' permanently?',
      message: 'Closing cannot be undone. Nobody can start the test any more. Candidates already in the middle of the test can finish until their own deadline. All results stay available.',
      confirmLabel: 'Close drive', danger: true
    });
    if (!ok) return;
    var notice = document.getElementById('notice');
    try {
      await Api.post('/api/admin/drives/' + d.id + '/close');
      await loadList();
      UI.showNotice(notice, 'success', 'Drive ' + d.code + ' is closed.');
    } catch (e) { Admin.handleError(e, notice); }
  }
})();
