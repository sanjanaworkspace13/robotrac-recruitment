/* Candidate login: client-side checks for fast feedback; the server verifies everything. */
(function () {
  'use strict';
  var EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

  document.addEventListener('DOMContentLoaded', function () {
    var form = document.getElementById('loginForm');
    var email = document.getElementById('email');
    var code = document.getElementById('driveCode');
    var password = document.getElementById('drivePassword');
    var toggle = document.getElementById('toggle');
    var submit = document.getElementById('submitBtn');
    var notice = document.getElementById('notice');

    // Already logged in as a candidate? Continue where they left off.
    Api.me().then(function (me) {
      if (me && me.authenticated && me.role === 'CANDIDATE') {
        window.location.replace('candidate-instructions.html');
      }
    }).catch(function () { /* stay on the login page */ });

    var params = new URLSearchParams(window.location.search);
    if (params.get('reason') === 'expired') {
      UI.showNotice(notice, 'warning', 'Your session expired. Log in again with the same details to continue — your timer and saved answers are kept.');
    }

    toggle.addEventListener('click', function () {
      var hidden = password.type === 'password';
      password.type = hidden ? 'text' : 'password';
      toggle.textContent = hidden ? 'HIDE' : 'SHOW';
      toggle.setAttribute('aria-pressed', String(hidden));
      toggle.setAttribute('aria-label', hidden ? 'Hide password' : 'Show password');
    });

    function setError(input, errorId, message) {
      var holder = document.getElementById(errorId);
      holder.textContent = message || '';
      if (message) input.setAttribute('aria-invalid', 'true'); else input.removeAttribute('aria-invalid');
    }

    function validate() {
      var ok = true;
      var e = email.value.trim();
      var c = code.value.trim();
      setError(email, 'email-error', '');
      setError(code, 'code-error', '');
      setError(password, 'password-error', '');
      if (!e) { setError(email, 'email-error', 'Please enter your registered email ID.'); ok = false; }
      else if (!EMAIL.test(e)) { setError(email, 'email-error', 'Please enter a valid email address.'); ok = false; }
      if (!c) { setError(code, 'code-error', 'Please enter the drive code.'); ok = false; }
      if (!password.value) { setError(password, 'password-error', 'Please enter the drive password.'); ok = false; }
      if (!ok) {
        var firstInvalid = form.querySelector('[aria-invalid="true"]');
        if (firstInvalid) firstInvalid.focus();
      }
      return ok;
    }

    form.addEventListener('submit', async function (ev) {
      ev.preventDefault();
      UI.hideNotice(notice);
      if (!validate()) return;
      UI.setBusy(submit, true, 'Verifying…');
      try {
        var res = await Api.post('/api/auth/candidate/login', {
          email: email.value.trim().toLowerCase(),
          driveCode: code.value.trim().toUpperCase(),
          drivePassword: password.value
        });
        password.value = '';
        Api.resetCsrf(); // the session changed; fetch a fresh token on the next page
        window.location.href = res.next === 'EXAM' ? 'candidate-exam.html' : 'candidate-instructions.html';
      } catch (err) {
        UI.setBusy(submit, false);
        var msg = err.message;
        if (err.code === 'DRIVE_NOT_STARTED' && err.details && err.details.startAt) {
          msg = 'This test has not opened yet. It opens on ' + UI.formatDateTime(err.details.startAt) + '.';
        }
        UI.showNotice(notice, err.status === 401 || err.status === 0 || err.status >= 500 ? 'error' : 'warning', msg);
      }
    });
  });
})();
