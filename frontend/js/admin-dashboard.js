(function () {
  'use strict';
  var el = UI.el;

  document.addEventListener('DOMContentLoaded', async function () {
    try { await Admin.init(); } catch (e) { return; }
    document.getElementById('refreshBtn').addEventListener('click', load);
    load();
  });

  async function load() {
    var notice = document.getElementById('notice');
    UI.hideNotice(notice);
    try {
      var s = await Api.get('/api/admin/dashboard');
      render(s);
    } catch (e) {
      Admin.handleError(e, notice);
    }
  }

  function render(s) {
    document.getElementById('asOf').textContent = 'Live figures from the database · ' + UI.formatDateTime(s.serverNow);
    var tiles = UI.clear(document.getElementById('tiles'));
    [
      ['Recruitment drives', s.totalDrives, s.activeDrives + ' active · ' + s.liveDrives + ' live now'],
      ['Authorised candidates', s.authorizedCandidates, 'across all drives'],
      ['Attempts started', s.attemptsStarted, s.notStarted + ' authorised not started'],
      ['Submitted', s.submitted, 'by candidate or browser timer'],
      ['Timed out', s.timedOut, 'closed at the deadline'],
      ['In progress', s.inProgress, 'attempts running now'],
      ['Eligible questions', s.questionsEligible, s.questionsFlagged + ' flagged for review']
    ].forEach(function (t, i) {
      tiles.appendChild(el('div', { class: 'tile' + (i === 0 ? ' accent-left' : '') }, [
        el('div', { class: 'k', text: t[0] }), el('div', { class: 'v', text: String(t[1]) }), el('div', { class: 'h', text: t[2] })
      ]));
    });

    var tbody = UI.clear(document.getElementById('driveRows'));
    if (!s.drives.length) {
      tbody.appendChild(el('tr', {}, [el('td', { colspan: '7', class: 'empty' }, [
        'No drives yet. ', el('a', { href: 'drive-management.html#new', text: 'Create the first drive' }), '.'])]));
      return;
    }
    s.drives.forEach(function (d) {
      tbody.appendChild(el('tr', {}, [
        el('td', {}, [el('strong', { class: 'mono', text: d.code }), el('div', { class: 'small muted', text: d.name })]),
        el('td', {}, [Admin.phaseBadge(d.phase)]),
        el('td', { class: 'num', text: String(d.authorized) }),
        el('td', { class: 'num', text: String(d.notStarted) }),
        el('td', { class: 'num', text: String(d.inProgress) }),
        el('td', { class: 'num', text: String(d.completed) }),
        el('td', {}, [el('div', { class: 'row-actions' }, [
          el('a', { class: 'btn btn-ghost btn-sm', href: 'candidate-management.html?driveId=' + d.id, text: 'Candidates' }),
          el('a', { class: 'btn btn-ghost btn-sm', href: 'results-dashboard.html?driveId=' + d.id, text: 'Results' })
        ])])
      ]));
    });
  }
})();
