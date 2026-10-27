// Même cadence et mêmes états de mots que IseePortrait.wordsSpoken / IseeSpokenLine.
export function wordsSpoken(text, elapsedMs) {
  const count = text.split(' ').filter(Boolean).length;
  const total = count * 385;
  const progress = Math.max(0, Math.min(1, (elapsedMs - 150) / Math.max(300, total - 300)));
  return Math.min(count, Math.floor(progress * count) + (elapsedMs > 150 ? 1 : 0));
}

export function spokenLine(element) {
  let current = '', active = false, frame = 0, words = [];
  function colorWords(shown) {
    words.forEach((word, index) => {
      word.className = shown >= words.length || index < shown - 1 ? 'said' : index === shown - 1 ? 'current' : 'upcoming';
    });
  }
  return (text, speaking = false) => {
    if (text === current && speaking === active) return;
    cancelAnimationFrame(frame);
    current = text; active = speaking;
    words = text.split(' ').filter(Boolean).map(value => {
      const span = document.createElement('span');
      span.textContent = value;
      return span;
    });
    element.replaceChildren(...words.flatMap((word, index) => index ? [document.createTextNode(' '), word] : [word]));
    element.setAttribute('aria-label', text);
    colorWords(speaking ? 0 : words.length);
    if (!speaking || matchMedia('(prefers-reduced-motion: reduce)').matches) return colorWords(words.length);
    const start = performance.now();
    function tick(now) {
      const shown = wordsSpoken(text, now - start);
      colorWords(shown);
      if (shown < words.length) frame = requestAnimationFrame(tick);
    }
    frame = requestAnimationFrame(tick);
  };
}
