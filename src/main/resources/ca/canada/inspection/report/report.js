(function () {
  // Typing filters once the keys stop, not on each key: a large run has tens of thousands of rows.
  function settled(run) {
    var timer;
    return function () { clearTimeout(timer); timer = setTimeout(run, 150); };
  }
  var rows = Array.prototype.slice.call(document.querySelectorAll('#rows tbody tr'));
  var rowFilter = document.getElementById('rowFilter'), rowCount = document.getElementById('rowCount');
  var showAll = document.getElementById('showAll');
  function filterRows() {
    if (!rowFilter) return;
    var words = rowFilter.value.toLowerCase().split(/\s+/).filter(Boolean), shown = 0;
    rows.forEach(function (tr) {
      var text = tr.textContent.toLowerCase();
      var keep = words.every(function (w) { return text.indexOf(w) >= 0; });
      tr.hidden = !keep; shown += keep ? 1 : 0;
    });
    showAll.hidden = true;
    rowCount.textContent = words.length ? shown + ' of ' + rows.length + ' shown' : rows.length + ' amplicons';
  }
  if (rowFilter) {
    rowFilter.addEventListener('input', settled(filterRows));
    showAll.addEventListener('click', function () { rowFilter.value = ''; filterRows(); });
    filterRows();
  }
  document.querySelectorAll('#grid button.chip').forEach(function (chip) {
    chip.addEventListener('click', function () {
      var shown = 0;
      rows.forEach(function (tr) {
        tr.hidden = !(tr.dataset.s === chip.dataset.s && tr.dataset.a === chip.dataset.a);
        shown += tr.hidden ? 0 : 1;
      });
      rowFilter.value = '';
      showAll.hidden = false;
      rowCount.textContent = chip.dataset.s + ' \u00b7 ' + chip.dataset.a + ': ' + shown + ' of ' + rows.length + ' shown';
      document.getElementById('amplicons').scrollIntoView({ behavior: 'smooth' });
    });
  });
  var sampleFilter = document.getElementById('sampleFilter'), only = document.getElementById('onlyProducts');
  function filterSamples() {
    var word = sampleFilter.value.toLowerCase();
    document.querySelectorAll('#grid tbody tr').forEach(function (tr) {
      tr.hidden = tr.dataset.sample.toLowerCase().indexOf(word) < 0 || (only.checked && tr.dataset.any !== '1');
    });
  }
  if (sampleFilter) { sampleFilter.addEventListener('input', settled(filterSamples)); only.addEventListener('change', filterSamples); }
})();
