#!/usr/bin/env node
/**
 * Tests du client Expo, rejouables hors React Native et hors CI :
 *   node app/tools/test-client.mjs          (Node ≥ 20, aucune dépendance à installer)
 *
 * 1. parsing des 4 réponses du contrat (src/parse.ts) ;
 * 2. client HTTP (src/api.ts) contre un serveur local factice, erreurs comprises ;
 * 3. colorisation de masque (src/utils/mask.ts) vérifiée pixel à pixel.
 *
 * Le TypeScript est compilé par le tsc local dans app/.test-build (gitignoré).
 * Code de sortie ≠ 0 dès qu'un test échoue.
 */
import { buildClient } from './testlib/tsbuild.mjs';
import { createHarness } from './testlib/harness.mjs';
import parseSuite from './testlib/suite-parse.mjs';
import maskSuite from './testlib/suite-mask.mjs';
import apiSuite from './testlib/suite-api.mjs';

const SUITES = [parseSuite, maskSuite, apiSuite];
const WATCHDOG_MS = 120_000;

async function main() {
  const watchdog = setTimeout(() => {
    console.error(`\nERREUR : tests bloqués (> ${WATCHDOG_MS / 1000} s)`);
    process.exit(1);
  }, WATCHDOG_MS);

  let ctx;
  try {
    ctx = buildClient();
  } catch (error) {
    console.error(`ERREUR : ${error.message}`);
    clearTimeout(watchdog);
    return 1;
  }

  const harness = createHarness();
  for (const suite of SUITES) {
    harness.title(suite.title);
    await suite.run(harness, ctx);
  }
  clearTimeout(watchdog);
  return harness.report() ? 0 : 1;
}

process.exit(await main());