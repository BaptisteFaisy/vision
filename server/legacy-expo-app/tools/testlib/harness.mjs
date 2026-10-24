/** Mini-harnais de test : un test = une fonction async, l'échec est capturé et compté. */

export function createHarness() {
  const passed = [];
  const failed = [];

  async function test(name, fn) {
    try {
      await fn();
      passed.push(name);
      console.log(`  \x1b[32mPASS\x1b[0m ${name}`);
    } catch (error) {
      failed.push({ name, error });
      console.log(`  \x1b[31mFAIL\x1b[0m ${name} — ${error.message}`);
    }
  }

  function title(text) {
    console.log(`\n\x1b[1m${text}\x1b[0m`);
  }

  function report() {
    console.log(`\n${passed.length} OK, ${failed.length} KO`);
    for (const { name, error } of failed) {
      console.log(`  KO ${name}\n     ${error.message}`);
    }
    return failed.length === 0;
  }

  return { test, title, report };
}