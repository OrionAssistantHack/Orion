// ===== Tabs (Use cases) =====
(function () {
  const tabs = document.querySelectorAll('[data-tab]');
  const panels = document.querySelectorAll('[data-panel]');
  if (!tabs.length) return;

  function activate(name) {
    tabs.forEach((t) => {
      const on = t.dataset.tab === name;
      t.classList.toggle('tab-active', on);
      t.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    panels.forEach((p) => p.classList.toggle('hidden', p.dataset.panel !== name));
  }

  tabs.forEach((t) => t.addEventListener('click', () => activate(t.dataset.tab)));

  // Keyboard navigation between tabs
  const list = Array.from(tabs);
  list.forEach((t, i) => {
    t.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
        e.preventDefault();
        const next = e.key === 'ArrowRight' ? (i + 1) % list.length : (i - 1 + list.length) % list.length;
        list[next].focus();
        activate(list[next].dataset.tab);
      }
    });
  });
})();

// ===== Reveal-on-scroll =====
(function () {
  const els = document.querySelectorAll('.reveal');
  if (!('IntersectionObserver' in window) || !els.length) {
    els.forEach((el) => el.classList.add('is-visible'));
    return;
  }
  const io = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          io.unobserve(entry.target);
        }
      });
    },
    { threshold: 0.12, rootMargin: '0px 0px -40px 0px' }
  );
  els.forEach((el) => io.observe(el));
})();
