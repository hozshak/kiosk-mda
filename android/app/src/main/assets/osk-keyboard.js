/*
 * Kiosk-MDA — eigene Bildschirm-Tastatur (HTML/JS-Overlay).
 *
 * Wird vom nativen Code per evaluateJavascript in JEDE geladene Seite injiziert
 * (auch fremdes ERP). Schreibt Zeichen direkt ins fokussierte Feld via JS und
 * umgeht damit die Android-IME komplett (kein keyCode 229 / keine composition).
 *
 * Steuerung von nativ:
 *   window.__kioskOskOn = true|false   -> Tastatur an/aus
 *   window.__kioskKbUpdate()           -> nach Flag-Aenderung aufrufen
 */
(function () {
  if (window.__kioskKbInstalled) { try { window.__kioskKbUpdate(); } catch (e) {} return; }
  window.__kioskKbInstalled = true;

  var KB_ID = 'kioskKb';
  var shift = false;
  var layer = 'abc';
  var lastField = null;

  var LAYOUT = {
    abc: [
      ['1','2','3','4','5','6','7','8','9','0'],
      ['q','w','e','r','t','z','u','i','o','p'],
      ['a','s','d','f','g','h','j','k','l','ö','ä'],
      ['⇧','y','x','c','v','b','n','m','ü','ß','⌫'],
      ['?123','@','space','.','-','↵','✕']
    ],
    sym: [
      ['1','2','3','4','5','6','7','8','9','0'],
      ['@','#','$','%','&','*','-','+','(',')'],
      ['!','?','/',':',';','_','=','"','\'','€'],
      ['ABC',',','.','<','>','[',']','{','}','⌫'],
      ['ABC','@','space','.','_','↵','✕']
    ]
  };

  function isEditable(el) {
    if (!el) return false;
    if (el.isContentEditable) return true;
    var t = el.tagName;
    if (t === 'TEXTAREA') return true;
    if (t === 'INPUT') {
      var type = (el.getAttribute('type') || 'text').toLowerCase();
      return ['text','password','search','email','url','tel','number',''].indexOf(type) !== -1;
    }
    return false;
  }

  function activeField() {
    var a = document.activeElement;
    if (isEditable(a)) return a;
    if (lastField && document.contains(lastField)) return lastField;
    return null;
  }

  // ---- Einfuegen / Loeschen (frameworktauglich) ----
  function insert(text) {
    var el = activeField();
    if (!el) return;
    try { el.focus({ preventScroll: true }); } catch (e) {}
    var ok = false;
    try { ok = document.execCommand('insertText', false, text); } catch (e) {}
    if (!ok) insertManual(el, text);
  }

  function insertManual(el, text) {
    if (el.isContentEditable) { el.textContent += text; fireInput(el); return; }
    var start = el.selectionStart, end = el.selectionEnd;
    var v = el.value != null ? el.value : '';
    if (start == null) { start = end = v.length; }
    var nv = v.slice(0, start) + text + v.slice(end);
    setNativeValue(el, nv);
    var pos = start + text.length;
    try { el.setSelectionRange(pos, pos); } catch (e) {}
  }

  function backspace() {
    var el = activeField();
    if (!el) return;
    try { el.focus({ preventScroll: true }); } catch (e) {}
    var ok = false;
    try { ok = document.execCommand('delete', false); } catch (e) {}
    if (ok) return;
    if (el.isContentEditable) { el.textContent = el.textContent.slice(0, -1); fireInput(el); return; }
    var start = el.selectionStart, end = el.selectionEnd;
    var v = el.value != null ? el.value : '';
    if (start == null) { start = end = v.length; }
    var nv, pos;
    if (start !== end) { nv = v.slice(0, start) + v.slice(end); pos = start; }
    else if (start > 0) { nv = v.slice(0, start - 1) + v.slice(end); pos = start - 1; }
    else return;
    setNativeValue(el, nv);
    try { el.setSelectionRange(pos, pos); } catch (e) {}
  }

  function setNativeValue(el, value) {
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, value); else el.value = value;
    fireInput(el);
  }

  function fireInput(el) {
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function pressEnter() {
    var el = activeField();
    if (!el) return;
    ['keydown', 'keypress', 'keyup'].forEach(function (type) {
      el.dispatchEvent(new KeyboardEvent(type, {
        key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true
      }));
    });
    // Falls Single-Line-Input im Formular: ggf. submit
    if (el.form && (el.tagName === 'INPUT')) {
      try { if (typeof el.form.requestSubmit === 'function') el.form.requestSubmit(); } catch (e) {}
    }
  }

  // ---- System-Tastatur unterdruecken ----
  function suppressSystemKb(el) {
    if (el && el.setAttribute && el.getAttribute('inputmode') !== 'none') {
      try { el.setAttribute('inputmode', 'none'); } catch (e) {}
    }
  }

  // ---- UI ----
  function buildKey(label) {
    var k = document.createElement('div');
    k.className = 'kk-key';
    var wide = (label === 'space');
    if (wide) k.className += ' kk-space';
    if (['⇧','⌫','↵','?123','ABC','✕'].indexOf(label) !== -1) k.className += ' kk-fn';
    k.textContent = displayLabel(label);
    k.setAttribute('data-k', label);
    // pointerdown: Fokus im Feld halten + Aktion ausfuehren
    k.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      handleKey(label);
    });
    return k;
  }

  function displayLabel(label) {
    if (label === 'space') return '';
    if (label.length === 1 && /[a-zäöü]/.test(label)) return shift ? label.toUpperCase() : label;
    return label;
  }

  function handleKey(label) {
    switch (label) {
      case '⇧': shift = !shift; render(); return;
      case '⌫': backspace(); return;
      case '↵': pressEnter(); return;
      case 'space': insert(' '); return;
      case '?123': layer = 'sym'; render(); return;
      case 'ABC': layer = 'abc'; render(); return;
      case '✕': hide(); return;
      default:
        var ch = label;
        if (ch.length === 1 && /[a-zäöü]/.test(ch) && shift) ch = ch.toUpperCase();
        insert(ch);
        return;
    }
  }

  function getRoot() {
    var root = document.getElementById(KB_ID);
    if (root) return root;
    root = document.createElement('div');
    root.id = KB_ID;
    root.setAttribute('translate', 'no');
    // WICHTIG: an <body> haengen - direkte Kinder von <html> rendert Chromium nicht.
    (document.body || document.documentElement).appendChild(root);
    return root;
  }

  function render() {
    var root = getRoot();
    root.innerHTML = '';
    var rows = LAYOUT[layer];
    for (var r = 0; r < rows.length; r++) {
      var row = document.createElement('div');
      row.className = 'kk-row';
      for (var c = 0; c < rows[r].length; c++) {
        row.appendChild(buildKey(rows[r][c]));
      }
      root.appendChild(row);
    }
    // Shift-Status visuell
    var sk = root.querySelector('[data-k="⇧"]');
    if (sk && shift) sk.className += ' kk-active';
  }

  function show() {
    if (!window.__kioskOskOn) return;
    var root = getRoot();
    render();
    root.classList.add('kk-visible');
    document.documentElement.classList.add('kk-open');
    var el = activeField();
    if (el) { try { el.scrollIntoView({ block: 'center' }); } catch (e) {} }
  }

  function hide() {
    var root = document.getElementById(KB_ID);
    if (root) root.classList.remove('kk-visible');
    document.documentElement.classList.remove('kk-open');
  }

  // ---- Styles ----
  function injectStyle() {
    if (document.getElementById('kioskKbStyle')) return;
    var s = document.createElement('style');
    s.id = 'kioskKbStyle';
    s.textContent = [
      '#' + KB_ID + '{position:fixed;left:0;right:0;bottom:0;z-index:2147483647;',
      'background:#1a1f29;padding:6px;box-shadow:0 -4px 16px rgba(0,0,0,.5);',
      'display:none;flex-direction:column;gap:6px;font-family:system-ui,sans-serif;',
      'touch-action:manipulation;user-select:none;-webkit-user-select:none;}',
      '#' + KB_ID + '.kk-visible{display:flex;}',
      '#' + KB_ID + ' .kk-row{display:flex;gap:6px;justify-content:center;}',
      '#' + KB_ID + ' .kk-key{flex:1;min-width:0;height:52px;display:flex;align-items:center;',
      'justify-content:center;background:#2e3441;color:#e6e8eb;border-radius:6px;',
      'font-size:20px;font-weight:500;cursor:pointer;border:1px solid #3a4150;}',
      '#' + KB_ID + ' .kk-key:active{background:#4da3ff;color:#fff;}',
      '#' + KB_ID + ' .kk-fn{background:#252b38;font-size:16px;flex:1.3;}',
      '#' + KB_ID + ' .kk-space{flex:5;}',
      '#' + KB_ID + ' .kk-active{background:#4da3ff;color:#fff;}',
      'html.kk-open body{padding-bottom:300px !important;}'
    ].join('');
    (document.head || document.documentElement).appendChild(s);
  }

  // ---- Fokus-Logik ----
  document.addEventListener('focusin', function (e) {
    var el = e.target;
    if (!isEditable(el)) return;
    lastField = el;
    suppressSystemKb(el);
    if (window.__kioskOskOn) show();
  }, true);

  document.addEventListener('focusout', function () {
    setTimeout(function () {
      var a = document.activeElement;
      if (!isEditable(a)) hide();
    }, 150);
  }, true);

  // Von nativ nach Toggle aufgerufen
  window.__kioskKbUpdate = function () {
    if (window.__kioskOskOn) {
      var el = activeField();
      if (el) { suppressSystemKb(el); show(); }
    } else {
      hide();
    }
  };

  injectStyle();
  // Falls beim Laden schon ein Feld fokussiert ist
  if (window.__kioskOskOn && isEditable(document.activeElement)) {
    lastField = document.activeElement;
    suppressSystemKb(lastField);
    show();
  }
})();
