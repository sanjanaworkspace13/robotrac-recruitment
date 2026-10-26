/*
 * Shared UI helpers. All text from the server is inserted with textContent (never innerHTML),
 * so question text, names and notes can never inject HTML or scripts.
 */
(function () {
  'use strict';

  /** el('div', {class: 'x', onclick: fn, 'aria-label': 'y'}, ['text', childNode]) */
  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === null || v === undefined || v === false) return;
        if (k === 'class') node.className = v;
        else if (k === 'text') node.textContent = v;
        else if (k.indexOf('on') === 0 && typeof v === 'function') node.addEventListener(k.slice(2), v);
        else if (k === 'dataset') Object.keys(v).forEach(function (d) { node.dataset[d] = v[d]; });
        else if (v === true) node.setAttribute(k, '');
        else node.setAttribute(k, String(v));
      });
    }
    (children || []).forEach(function (c) {
      if (c === null || c === undefined || c === false) return;
      node.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
    });
    return node;
  }

  function $(sel, root) { return (root || document).querySelector(sel); }

  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }

  function pad(n) { return n < 10 ? '0' + n : String(n); }

  function formatDateTime(iso) {
    if (!iso) return '—';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleString(undefined, { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }

  function formatTime(iso) {
    if (!iso) return '—';
    var d = new Date(iso);
    return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }

  /** ISO instant -> value for <input type="datetime-local"> in the browser's time zone. */
  function toLocalInput(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  /** <input type="datetime-local"> value (local time) -> ISO instant in UTC for the API. */
  function fromLocalInput(value) {
    if (!value) return null;
    var d = new Date(value);
    return isNaN(d.getTime()) ? null : d.toISOString();
  }

  function formatClock(totalSeconds) {
    var s = Math.max(0, Math.floor(totalSeconds));
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var sec = s % 60;
    return (h > 0 ? h + ':' + pad(m) : pad(m)) + ':' + pad(sec);
  }

  function showNotice(node, type, message, list) {
    if (!node) return;
    clear(node);
    node.className = 'notice' + (type && type !== 'success' ? ' ' + type : '');
    node.appendChild(document.createTextNode(message));
    if (list && list.length) {
      var ul = el('ul');
      list.forEach(function (item) { ul.appendChild(el('li', { text: item })); });
      node.appendChild(ul);
    }
    node.classList.remove('hidden');
    node.style.display = 'block';
  }

  function hideNotice(node) {
    if (!node) return;
    node.classList.add('hidden');
    node.style.display = 'none';
  }

  /** Puts a button into a loading state (prevents double clicks). */
  function setBusy(button, busy, busyLabel) {
    if (!button) return;
    if (busy) {
      if (!button.dataset.label) button.dataset.label = button.innerHTML;
      button.disabled = true;
      button.setAttribute('aria-busy', 'true');
      clear(button);
      button.appendChild(el('span', { class: 'spinner', 'aria-hidden': 'true' }));
      button.appendChild(document.createTextNode(busyLabel || 'Please wait…'));
    } else {
      button.disabled = false;
      button.removeAttribute('aria-busy');
      if (button.dataset.label !== undefined) {
        button.innerHTML = button.dataset.label; // restores our own static markup only
        delete button.dataset.label;
      }
    }
  }

  var toastArea = null;
  function toast(message, type) {
    if (!toastArea) {
      toastArea = el('div', { class: 'toast-area', role: 'status', 'aria-live': 'polite' });
      document.body.appendChild(toastArea);
    }
    var t = el('div', { class: 'toast' + (type === 'error' ? ' error' : ''), text: message });
    toastArea.appendChild(t);
    setTimeout(function () { t.remove(); }, type === 'error' ? 7000 : 4000);
  }

  /**
   * Accessible confirmation dialog. Resolves true/false.
   * options: { title, message, details: [..], confirmLabel, cancelLabel, danger, input: {label, placeholder} }
   * When options.input is set, resolves { ok, value }.
   */
  function confirmDialog(options) {
    return new Promise(function (resolve) {
      var input = null;
      var body = el('div', { class: 'modal-body' }, [el('p', { text: options.message || '' })]);
      if (options.details && options.details.length) {
        var ul = el('ul');
        options.details.forEach(function (d) { ul.appendChild(el('li', { text: d })); });
        body.appendChild(ul);
      }
      if (options.input) {
        input = el('textarea', { class: 'input', id: 'dlg-input', rows: '3', maxlength: '500', placeholder: options.input.placeholder || '' });
        body.appendChild(el('div', { class: 'form-field' }, [el('label', { for: 'dlg-input', text: options.input.label }), input]));
      }
      var cancel = el('button', { type: 'button', class: 'btn btn-secondary', text: options.cancelLabel || 'Cancel' });
      var ok = el('button', { type: 'button', class: 'btn ' + (options.danger ? 'btn-danger' : 'btn-primary'), text: options.confirmLabel || 'Confirm' });
      var dlg = el('dialog', { class: 'modal', 'aria-labelledby': 'dlg-title' }, [
        el('div', { class: 'modal-head' }, [el('h2', { id: 'dlg-title', text: options.title || 'Please confirm' })]),
        body,
        el('div', { class: 'modal-foot' }, [cancel, ok])
      ]);
      document.body.appendChild(dlg);
      function done(result) {
        dlg.close();
        dlg.remove();
        resolve(input ? { ok: result, value: input.value.trim() } : result);
      }
      cancel.addEventListener('click', function () { done(false); });
      ok.addEventListener('click', function () { done(true); });
      dlg.addEventListener('cancel', function (e) { e.preventDefault(); done(false); });
      dlg.showModal();
      (input || cancel).focus();
    });
  }

  /** Renders "Page x of y" with Previous/Next buttons. */
  function renderPager(container, page, totalPages, totalItems, onChange) {
    clear(container);
    if (!totalItems) return;
    var prev = el('button', { type: 'button', class: 'btn btn-secondary btn-sm', text: 'Previous', disabled: page <= 0 });
    var next = el('button', { type: 'button', class: 'btn btn-secondary btn-sm', text: 'Next', disabled: page >= totalPages - 1 });
    prev.addEventListener('click', function () { onChange(page - 1); });
    next.addEventListener('click', function () { onChange(page + 1); });
    container.appendChild(el('span', { text: 'Page ' + (page + 1) + ' of ' + Math.max(totalPages, 1) + ' · ' + totalItems + ' item(s)' }));
    container.appendChild(el('span', { class: 'btns' }, [prev, next]));
  }

  function badge(text, color) {
    return el('span', { class: 'badge' + (color ? ' ' + color : ''), text: text });
  }

  function debounce(fn, ms) {
    var t = null;
    return function () {
      var args = arguments;
      clearTimeout(t);
      t = setTimeout(function () { fn.apply(null, args); }, ms);
    };
  }

  window.UI = {
    el: el, $: $, clear: clear, formatDateTime: formatDateTime, formatTime: formatTime,
    toLocalInput: toLocalInput, fromLocalInput: fromLocalInput, formatClock: formatClock,
    showNotice: showNotice, hideNotice: hideNotice, setBusy: setBusy, toast: toast,
    confirmDialog: confirmDialog, renderPager: renderPager, badge: badge, debounce: debounce
  };
})();
