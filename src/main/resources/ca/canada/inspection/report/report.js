(function () {
  // A filter run once typing stops, not on each key: a large run has tens of thousands of rows.
  // It can be called off, when a click shows other rows before it runs.
  function settled(run) {
    var timer;
    var later = function () { clearTimeout(timer); timer = setTimeout(run, 150); };
    later.cancel = function () { clearTimeout(timer); };
    return later;
  }
  var rows = Array.prototype.slice.call(document.querySelectorAll('#rows tbody tr'));
  // Each row's text cell by cell, a tab between cells: read as one string, sample S1 and assay 16S
  // were "s116s", which a search for S11 found.
  var texts = rows.map(function (tr) {
    return Array.prototype.map.call(tr.cells, function (td) { return td.textContent.toLowerCase(); }).join('\t');
  });
  var rowFilter = document.getElementById('rowFilter'), rowCount = document.getElementById('rowCount');
  var showAll = document.getElementById('showAll'), amplicons = document.getElementById('amplicons');
  function count(n) { return n + (n === 1 ? ' amplicon' : ' amplicons'); }
  function filterRows() {
    if (!rowFilter) return;
    var words = rowFilter.value.toLowerCase().split(/\s+/).filter(Boolean), shown = 0;
    rows.forEach(function (tr, i) {
      var keep = words.every(function (w) { return texts[i].indexOf(w) >= 0; });
      tr.hidden = !keep; shown += keep ? 1 : 0;
    });
    showAll.hidden = true;
    rowCount.textContent = words.length ? shown + ' of ' + rows.length + ' shown' : count(rows.length);
  }
  var typed = settled(filterRows);
  // The rows of one grid cell.
  function showCell(sample, assay) {
    typed.cancel();
    var shown = 0;
    rows.forEach(function (tr) {
      tr.hidden = !(tr.dataset.s === sample && tr.dataset.a === assay);
      shown += tr.hidden ? 0 : 1;
    });
    rowFilter.value = '';
    showAll.hidden = false;
    var capped = document.getElementById('rows').dataset.capped;
    rowCount.textContent = sample + ' · ' + assay + ': ' + (shown === 0 && capped
        ? 'not among the amplicons listed here; ' + capped + ' has them'
        : shown + ' of ' + rows.length + ' shown');
  }
  if (rowFilter) {
    rowFilter.addEventListener('input', typed);
    showAll.addEventListener('click', function () { rowFilter.value = ''; filterRows(); });
    filterRows();
  }
  document.querySelectorAll('#grid button.chip').forEach(function (chip) {
    chip.addEventListener('click', function () {
      showCell(chip.dataset.s, chip.dataset.a);
      // Back returns to the grid, with every row shown again.
      try { history.pushState({ s: chip.dataset.s, a: chip.dataset.a }, ''); } catch (e) { /* file: pages may refuse */ }
      amplicons.scrollIntoView({ behavior: 'smooth' });
      // The keyboard goes where the eye goes.
      var heading = amplicons.querySelector('h2');
      if (heading) { heading.setAttribute('tabindex', '-1'); heading.focus({ preventScroll: true }); }
    });
  });
  window.addEventListener('popstate', function (event) {
    if (!rowFilter) return;
    if (event.state && event.state.s !== undefined) {
      showCell(event.state.s, event.state.a);
    } else {
      rowFilter.value = '';
      filterRows();
      var grid = document.getElementById('grid');
      if (grid) grid.scrollIntoView();
    }
  });
  var sampleFilter = document.getElementById('sampleFilter'), only = document.getElementById('onlyProducts');
  function filterSamples() {
    var word = sampleFilter.value.trim().toLowerCase();
    document.querySelectorAll('#grid tbody tr').forEach(function (tr) {
      tr.hidden = tr.dataset.sample.toLowerCase().indexOf(word) < 0 || (only.checked && tr.dataset.any !== '1');
    });
  }
  if (sampleFilter) { sampleFilter.addEventListener('input', settled(filterSamples)); only.addEventListener('change', filterSamples); }
})();
