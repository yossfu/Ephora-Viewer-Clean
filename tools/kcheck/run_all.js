// The whole verification pass, in one call.
//
//   const run = <this file, loaded with new Function("module","exports",src)>
//   await run({ fs, fetch })
//
// It compiles and RUNS the project's check suite against the app's real sources
// (plus hand-written Android/Filament stubs) using the Kotlin Playground
// compiler API, in groups -- the backend 500s on the whole suite at once, and
// the canonical file cannot compile as a whole because one of its checks needs
// the real Filament AAR. It also compiles `ui/world/FilamentWorldView.kt`,
// which the check groups cannot reach.
//
// The canonical harness is tools/kcheck/extra/tests_render.kt (53 checks). The
// groups are generated from it by make_group.js, so a check added there can only
// be missing here if this file is not updated -- TEST_COUNT below catches that.
const LIGHT = [
  ["shapes", "shapeClassificationCheck"], ["geomval", "geometryValidationCheck"],
  ["transform", "transformCheck"], ["objectlog", "objectLogCheck"],
  ["diagnostics", "diagnosticsCheck"], ["bytes", "byteReaderCheck"],
  ["compressed", "compressedUpdateCheck"], ["placeonly", "placementOnlyCheck"],
  ["incremental", "incrementalShapeCheck"], ["shapesource", "shapeSourceCheck"],
  ["parentparser", "parentParserCheck"], ["benchstate", "benchmarkStateCheck"],
  ["texentry", "textureEntryParseCheck"], ["texdecode", "textureEntryDecoderCheck"],
  ["texclass", "textureEntryClassificationCheck"], ["texdiag", "textureEntryDiagnosticsCheck"],
  ["texcache", "textureAssetCacheCheck"], ["texref", "textureEntryReferenceCheck"],
  ["texframing", "textureFramingCheck"], ["terseprefix", "tersePrefixCheck"],
  ["terseconsume", "terseConsumeCheck"], ["renderloop", "renderLoopAuditCheck"],
  ["moveaudit", "movementAuditCheck"],
  ["texasset", "texturePipelineCheck"],
];
const HEAVY = [
  ["pcodes", "pcodeClassificationCheck"], ["color", "colorCheck"],
  ["primparams", "primitiveParamsCheck"], ["testscene", "testSceneCheck"],
  ["world", "worldDiffCheck"], ["camera", "cameraCheck"], ["framing", "framingCheck"],
  ["scene", "scenePipelineCheck"], ["reject", "sceneRejectionCheck"],
  ["cache", "meshCacheCheck"], ["avatars", "avatarCheck"], ["probe", "probeMeshCheck"],
  ["terrain", "terrainMeshCheck"], ["census", "shapeCensusCheck"],
  ["samesource", "shapeSingleSourceCheck"], ["budget", "entityBudgetCheck"],
  ["frustum", "frustumMathCheck"], ["matrixfrustum", "matrixFrustumCheck"],
  ["camtrace", "cameraTraceCheck"], ["camprobe", "cameraProbeCheck"],
  ["primlock", "primLockCheck"], ["parentchain", "parentChainCheck"],
  ["parentresolve", "parentResolveCheck"], ["focusreport", "focusReportCheck"],
  ["scalability", "scalabilityCheck"], ["childfrustum", "childFrustumCheck"],
  ["texbind", "textureBindingCheck"],
];
// The capability/transport diagnosis does not need a scene, but it does need
// `slproto.caps` — which the light group does not compile — so it gets its own
// group. It is small (~176 kB, measured) and therefore passes even in the windows
// where the two big groups are refused, which is exactly when a failure diagnosis
// is most needed.
const CAPS = [
  ["capsfail", "capabilityFailureCheck"],
];
// The movement audit has the same problem as the texture checks: its group (the
// light one) is over the backend's limit in most windows. It needs `slproto.world`
// (for `Vector3`), so it cannot be as small as the caps group, but at ~400 kB it
// is the one that fits when the 504 kB light group does not -- and it is the check
// the movement correction of 2.13b-rev1 lives in.
const MOVE = [
  ["moveaudit", "movementAuditCheck"],
];
// bootstrapCheck is the one check that cannot be part of a runnable group.
const NOT_RUNNABLE = 1;

const P_LIGHT = [
  "com.lumiyaviewer.lumiya.renderer",
  "com.lumiyaviewer.lumiya.slproto.asset",
  "com.lumiyaviewer.lumiya.slproto.base",
  "com.lumiyaviewer.lumiya.slproto.messages",
  "com.lumiyaviewer.lumiya.slproto.movement",
  "com.lumiyaviewer.lumiya.slproto.world",
];
const P_HEAVY = P_LIGHT.concat(["com.lumiyaviewer.lumiya.slscene"]);

// What the capability group needs: the caps themselves, the asset layer they hand
// their failures to, and the two packages both already depend on.
const P_CAPS = [
  "com.lumiyaviewer.lumiya.slproto.caps",
  "com.lumiyaviewer.lumiya.slproto.asset",
  "com.lumiyaviewer.lumiya.slproto.base",
  "com.lumiyaviewer.lumiya.slproto.llsd",
];

// What the single movement-audit check needs: the movement package, the message
// template it encodes, and `slproto.world` for `Vector3`.
const P_MOVE = [
  "com.lumiyaviewer.lumiya.slproto.movement",
  "com.lumiyaviewer.lumiya.slproto.messages",
  "com.lumiyaviewer.lumiya.slproto.world",
  "com.lumiyaviewer.lumiya.slproto.base",
  "com.lumiyaviewer.lumiya.slproto.asset",
  "com.lumiyaviewer.lumiya.renderer",
];

// Everything `slproto.modules` needs, with nothing skipped: the session, the
// circuit, the capabilities, the login and the inventory. It is a compile-only
// pass, and its error count is therefore meaningful (unlike the ui-world check,
// which skips the packages it does not need).
const P_MODULES = P_LIGHT.concat([
  "com.lumiyaviewer.lumiya.slproto.modules",
  "com.lumiyaviewer.lumiya.slproto.circuit",
  "com.lumiyaviewer.lumiya.slproto.caps",
  "com.lumiyaviewer.lumiya.slproto.chat",
  "com.lumiyaviewer.lumiya.slproto.grids",
  "com.lumiyaviewer.lumiya.slproto.inventory",
  "com.lumiyaviewer.lumiya.slproto.login",
]);

// Packages the FilamentWorldView compile check does not need. Skipping them is
// what keeps that payload under the backend's limit; the errors they leave
// behind are inside the skipped packages' own files, never in the one file the
// check is about.
const P_UIWORLD_SKIP = [
  "com.lumiyaviewer.lumiya.slproto.circuit", "com.lumiyaviewer.lumiya.slproto.caps",
  "com.lumiyaviewer.lumiya.slproto.grids", "com.lumiyaviewer.lumiya.slproto.inventory",
  "com.lumiyaviewer.lumiya.slproto.login", "com.lumiyaviewer.lumiya.slproto.chat",
  "com.lumiyaviewer.lumiya.slproto.users", "com.lumiyaviewer.lumiya.slproto.selftest",
  "com.lumiyaviewer.lumiya.ui.service", "com.lumiyaviewer.lumiya.ui.chat",
  "com.lumiyaviewer.lumiya.ui.main", "com.lumiyaviewer.lumiya.ui.settings",
  "com.lumiyaviewer.lumiya.ui.inventory", "com.lumiyaviewer.lumiya.ui.login",
  "com.lumiyaviewer.lumiya.ui.diagnostics", "com.lumiyaviewer.lumiya.ui.diag",
  "com.lumiyaviewer.lumiya.ui.common",
];

module.exports = async function runAll({
  fs, fetch, kotlinVersion = "2.0.21", kcheck = "tools/kcheck", appBase = "app/src/main/",
  /** How many times each big group may be retried per round (see the loop below). */
  retriesPerRound = 6,
  /** How many rounds of alternating heavy/light attempts before giving up. */
  rounds = 6,
  /** Wall clock budget for the two big groups, in milliseconds. */
  budgetMillis = 45 * 60 * 1000,
}) {
  const module = { exports: {} };
  new Function("module", "exports", await fs.readTextFile(kcheck + "/run_lean.js"))(module, module.exports);
  const run = module.exports;
  const buildModule = { exports: {} };
  new Function("module", "exports", await fs.readTextFile(kcheck + "/make_group.js"))(buildModule, buildModule.exports);
  const build = buildModule.exports;

  const extra = async (name, checks, note) => {
    const text =
      "package com.lumiyaviewer.lumiya.kcheck\n\nfun main() { println(\"ui compile check\") }\n";
    if (checks) await build({ fs, out: kcheck + "/extra/" + name, checks, note });
    else await fs.writeTextFile(kcheck + "/extra/" + name, text);
  };

  await extra("tests_light.kt", LIGHT, "Grupo ligero: no depende de slscene/slworld.");
  await extra("tests_heavy.kt", HEAVY, "Grupo pesado: depende de slscene/slworld.");
  // The two big groups are 504 kB and 659 kB, and the backend refuses payloads
  // over ~450 kB in most windows, so the texture pipeline's own check also gets a
  // one-check group of its own (389 kB, which passes reliably) as the fallback
  // the README describes: when the big groups are being refused, this is still a
  // real run of the newest code, not a compromise.
  await extra("tests_texasset.kt", [["texasset", "texturePipelineCheck"]],
    "Grupo minimo: solo texturePipelineCheck (2.13b). Pasa cuando los grandes no.");
  await extra("tests_capsfail.kt", CAPS,
    "Grupo de diagnostico: los fallos de GetTexture, sin escena ni red.");
  await extra("tests_moveaudit.kt", MOVE,
    "Grupo minimo: solo movementAuditCheck. Pasa cuando el grupo ligero no.");
  await extra("tiny_entry.kt", null);

  const common = { fs, fetch, kotlinVersion, appBase, harness: kcheck };
  // The backend 500s on submissions over roughly 450 kB, and *which* of them
  // get through is a matter of what else the machine is doing at that instant:
  // measured, the light group is 504 kB and passes in some windows and not in
  // others, and the heavy group is 659 kB and needs many tries. So a 500 says
  // nothing about the code (it is returned at ~50 s, before any result), and a
  // fixed number of retries is not enough -- the two big groups are therefore
  // retried in ROUNDS, alternating, until both have been through or the wall
  // clock budget runs out. Whatever *did* pass is reported either way, so a
  // partial run is still evidence (see the `attempts` field of the result).
  const attempt = async (opts, tries = opts.retries || 4) => {
    let last = null;
    for (let i = 0; i < tries; i++) {
      last = await run(Object.assign({}, common, opts));
      if (last.status === 200) return last;
      await new Promise((r) => setTimeout(r, 8000));
    }
    return last;
  };
  const attempts = [];
  const runGroup = async (label, opts) => {
    const started = Date.now();
    const result = await attempt(Object.assign({ retries: retriesPerRound }, opts));
    attempts.push({
      label, status: result.status, millis: Date.now() - started,
      verdict: (result.stdout || "").replace(/<\/?outStream>/g, "").trim().split("\n").pop(),
    });
    // A 500 is the backend refusing the payload, not a verdict about the code,
    // so it is *not* a group result: returning null is what makes the round loop
    // try this group again.
    return result.status === 200 ? result : null;
  };
  // The heavy group is given a moment of quiet first: the backend 500s when two
  // large submissions land close together (measured: the same submission passes
  // immediately when it is the only one in flight).
  await new Promise((r) => setTimeout(r, 15000));
  const deadline = Date.now() + budgetMillis;
  let heavy = null;
  let light = null;
  for (let round = 0; round < rounds && (!heavy || !light) && Date.now() < deadline; round++) {
    if (!heavy) heavy = await runGroup("heavy (intento " + (round + 1) + ")", { extras: ["tests_heavy.kt"], packages: P_HEAVY });
    if (!light && Date.now() < deadline) light = await runGroup("light (intento " + (round + 1) + ")", { extras: ["tests_light.kt"], packages: P_LIGHT });
  }
  if (!heavy) heavy = await attempt({ extras: ["tests_heavy.kt"], packages: P_HEAVY, retries: 1 });
  if (!light) light = await attempt({ extras: ["tests_light.kt"], packages: P_LIGHT, retries: 1 });
  const uiworld = await attempt({
    extras: ["tiny_entry.kt"], entry: false, retries: 6,
    packages: P_HEAVY.concat(["com.lumiyaviewer.lumiya.renderer.filament", "com.lumiyaviewer.lumiya.slproto", "com.lumiyaviewer.lumiya.ui.world"]),
    skipPackages: P_UIWORLD_SKIP,
    onlyFiles: ["ui__world__FilamentWorldView.kt"],
    narrowPackages: ["com.lumiyaviewer.lumiya.ui.world"],
  });
  // The session itself (the movement wiring, the sends, the handshake) needs the
  // packages the ui-world check skips, so it gets its own compile-only pass with
  // every dependency present -- no skips, so its error count is meaningful.
  const modules = await attempt({
    extras: ["tiny_entry.kt"], entry: false, retries: 6, packages: P_MODULES,
  });
  const caps = await attempt({ extras: ["tests_capsfail.kt"], packages: P_CAPS, retries: 6 });
  // The movement patch of 2.13b-rev1 lives in `moveaudit`, and the light group it
  // belongs to is 504 kB; this one-check group is ~400 kB and is run either way.
  const move = await attempt({ extras: ["tests_moveaudit.kt"], packages: P_MOVE, retries: 6 });

  const expected = LIGHT.length + HEAVY.length + CAPS.length + NOT_RUNNABLE;
  const results = [];
  // `attempts` is how many submissions that group needed: a 500 is the backend
  // refusing the payload at ~50 s, so when a group fails it matters whether it
  // failed twice or forty times, and only the caller can tell how patient it
  // was (this file's own budget is passed in as `budgetMillis`).
  const verdict = (label, r, wantOk, tries) => {
    const ok = r.status === 200 && (r.counts.mineErrors === 0) && (wantOk ? /OK \(/.test(r.stdout || "") : true);
    results.push({
      label, ok, status: r.status, mineErrors: r.counts ? r.counts.mineErrors : null, attempts: tries,
      stdout: (r.stdout || "").trim().split("\n").pop(),
    });
    return ok;
  };
  const triesOf = (prefix) => attempts.filter((a) => a.label.startsWith(prefix)).length;
  verdict("light (" + LIGHT.length + ")", light, true, triesOf("light"));
  verdict("heavy (" + HEAVY.length + ")", heavy, true, triesOf("heavy"));
  verdict("capsfail (" + CAPS.length + ")", caps, true);
  verdict("moveaudit (" + MOVE.length + ")", move, true);
  // Only FilamentWorldView counts here: the skipped packages are expected to
  // complain, so the file list is filtered to the one under test.
  const fwvErrors = (uiworld.mine || []).filter((e) => e.file.indexOf("FilamentWorldView") >= 0);
  results.push({
    label: "ui/world/FilamentWorldView.kt",
    ok: uiworld.status === 200 && fwvErrors.length === 0,
    status: uiworld.status,
    mineErrors: fwvErrors.length,
    stdout: (uiworld.stdout || "").trim().split("\n").pop(),
  });
  // Compile-only: no checks run, so the whole error count is the verdict.
  verdict("slproto.modules (compila)", modules, false);

  const allOk = results.every((r) => r.ok) && expected === 53;
  return { allOk, expectedChecks: expected, results, attempts };
};
