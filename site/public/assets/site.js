/* MPS website: everything works without this file; it only makes a few things nicer. */
(function () {
  'use strict';
  var doc = document;
  doc.documentElement.classList.add('js');

  // the menu on narrow screens
  var button = doc.querySelector('.menu-button');
  var nav = doc.getElementById('nav');
  if (button && nav) {
    var setOpen = function (open) {
      nav.classList.toggle('open', open);
      button.setAttribute('aria-expanded', open ? 'true' : 'false');
    };
    button.addEventListener('click', function () { setOpen(!nav.classList.contains('open')); });
    nav.addEventListener('click', function (event) { if (event.target.closest('a')) { setOpen(false); } });
    doc.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && nav.classList.contains('open')) { setOpen(false); button.focus(); }
    });
  }

  // arrows of the screenshot strip
  var gallery = doc.querySelector('.gallery');
  var arrows = doc.querySelectorAll('.arrow[data-scroll]');
  if (gallery && arrows.length) {
    var update = function () {
      var max = gallery.scrollWidth - gallery.clientWidth - 2;
      arrows.forEach(function (arrow) {
        arrow.disabled = arrow.dataset.scroll === '-1' ? gallery.scrollLeft <= 2 : gallery.scrollLeft >= max;
      });
    };
    arrows.forEach(function (arrow) {
      arrow.addEventListener('click', function () {
        var figure = gallery.querySelector('figure');
        var step = figure ? figure.getBoundingClientRect().width + 20 : gallery.clientWidth;
        gallery.scrollBy({ left: step * Number(arrow.dataset.scroll), behavior: 'smooth' });
      });
    });
    gallery.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update);
    update();
  }

  var form = doc.querySelector('form.form[data-check]');
  if (form) {
    // questions that only make sense after a certain answer to another one
    var dependent = form.querySelectorAll('[data-when]');
    var refresh = function () {
      dependent.forEach(function (box) {
        var chosen = form.querySelector('input[name="' + box.dataset.when + '"]:checked');
        var show = !!chosen && chosen.value === box.dataset.is;
        box.hidden = !show;
        box.querySelectorAll('input, textarea').forEach(function (field) { field.disabled = !show; });
      });
    };
    // "choose up to N"
    var limit = function (group) {
      var max = Number(group.dataset.max);
      var boxes = group.querySelectorAll('input[type="checkbox"]');
      var count = group.querySelectorAll('input[type="checkbox"]:checked').length;
      boxes.forEach(function (box) { box.disabled = !box.checked && count >= max; });
    };
    form.addEventListener('change', function (event) {
      refresh();
      var group = event.target.closest('.options[data-max]');
      if (group) { limit(group); }
      var block = event.target.closest('.bad');
      if (block) { block.classList.remove('bad'); }
    });
    form.addEventListener('input', function (event) {
      var block = event.target.closest('.bad');
      if (block) { block.classList.remove('bad'); }
    });
    form.querySelectorAll('.options[data-max]').forEach(limit);
    refresh();

    // unanswered required questions are shown at once, without a trip to the server
    form.addEventListener('submit', function (event) {
      var problems = [];
      var message = '';
      form.querySelectorAll('[data-required]').forEach(function (block) {
        if (block.hidden) { return; }
        var text = block.querySelector('textarea');
        var own = block.querySelector('.other input');
        var answered = text ? text.value.trim().length >= 3 : !!block.querySelector('input:checked') || (!!own && own.value.trim() !== '');
        block.classList.toggle('bad', !answered);
        if (!answered) { problems.push(block); }
      });
      var alertBox = doc.getElementById('form-error');
      if (problems.length) {
        message = alertBox ? alertBox.dataset.required : '';
      } else {
        var consent = form.querySelector('[data-consent]');
        if (consent) {
          var contact = consent.querySelector('input[type="text"]');
          var agreed = consent.querySelector('input[type="checkbox"]');
          if (contact && agreed && contact.value.trim() !== '' && !agreed.checked) {
            consent.classList.add('bad');
            problems.push(consent);
            message = consent.dataset.consent;
          }
        }
      }
      if (!problems.length) { return; }
      event.preventDefault();
      if (alertBox) { alertBox.textContent = message; alertBox.hidden = false; }
      problems[0].scrollIntoView({ block: 'center', behavior: 'smooth' });
      var first = problems[0].querySelector('input, textarea');
      if (first) { first.focus({ preventScroll: true }); }
    });
  }

  // "are you sure?" before something is deleted
  doc.querySelectorAll('[data-confirm]').forEach(function (control) {
    control.addEventListener('click', function (event) {
      if (!window.confirm(control.dataset.confirm)) { event.preventDefault(); }
    });
  });
})();
