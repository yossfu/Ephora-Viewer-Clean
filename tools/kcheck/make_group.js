// Builds a REDUCED copy of the canonical tests_render.kt containing only a
// chosen set of top-level declarations (with their transitive dependencies),
// plus a renderPipelineChecks() entry point registering the chosen checks.
//
// Why: the canonical tests_render.kt is the single source of truth, but a few
// of its checks touch com.lumiyaviewer.lumiya.renderer.filament (which cannot
// compile without the real Filament AAR), so the canonical file is never run
// whole. Groups of checks are generated from it instead, and the canonical file
// is compile-checked separately (its only error must be the expected
// `Unresolved reference 'filament'`).
//
// Usage (from execute_js):
//   const build = <this file loaded via new Function(module, exports, src)>
//   await build({ fs, out: "<kcheck>/extra/tests_all.kt", checks: [["texentry","textureEntryParseCheck"], ...] })
//
// The canonical file is read from the directory the group is written into, so
// the harness works wherever it is copied -- `tools/kcheck/extra/` in the repo,
// `scratch/kcheck/extra/` while iterating in the editor. Pass `from` to override.
module.exports = async function build({ fs, out, checks, note = "", from = null }) {
  const src = await fs.readTextFile(from || out.replace(/[^/]*$/, "") + "tests_render.kt");
  const lines = src.split("\n");
  const isDecl = (l) =>
    !/^\s/.test(l) &&
    /^(private |internal |public |open |abstract |inline )?(fun|class|object|const val|val|var|enum class|data class|interface|sealed class|typealias) /.test(l);
  const firstDecl = lines.findIndex(isDecl);
  const imports = lines.slice(0, firstDecl).join("\n");
  // Skip the canonical renderPipelineChecks()/TEST_COUNT block: take only the
  // declarations that come after it.
  const bodyStart = lines.findIndex((l, i) => i > firstDecl && /^private const val TEST_COUNT/.test(l));
  const chunks = [];
  let cur = null;
  for (let i = bodyStart; i < lines.length; i++) {
    const l = lines[i];
    if (isDecl(l)) {
      if (cur) chunks.push(cur);
      const m = l.match(/^(?:private |internal |public )?(?:fun|class|object|const val|val|var)\s+([A-Za-z0-9_]+)/);
      cur = { name: m ? m[1] : "?", text: l };
    } else if (cur) {
      cur.text += "\n" + l;
    }
  }
  if (cur) chunks.push(cur);
  const byName = new Map(chunks.map((c) => [c.name, c]));
  // Transitive closure over identifier mentions inside the chunk text.
  const wanted = new Set();
  const queue = checks.map(([, fn]) => fn);
  const checkFns = new Set(checks.map(([, fn]) => fn));
  while (queue.length) {
    const name = queue.pop();
    if (wanted.has(name) || !byName.has(name)) continue;
    wanted.add(name);
    const text = byName.get(name).text;
    for (const other of chunks) {
      if (other.name === name) continue;
      if (new RegExp("\\b" + other.name.replace(/\$/g, "\\$") + "\\b").test(text)) queue.push(other.name);
    }
  }
  const missing = checks.map(([, fn]) => fn).filter((n) => !byName.has(n));
  // The one declaration that drags in the real Filament package (it is inside
  // bootstrapCheck) can never be present in a runnable group.
  if (wanted.has("bootstrapCheck")) {
    return { error: "bootstrapCheck cannot be included: it references renderer.filament" };
  }
  // Preserve source order so generated files read like the original.
  const parts = chunks.filter((c) => wanted.has(c.name)).map((c) => c.text);
  const header =
    "// GENERADO desde tests_render.kt por tools/kcheck/make_group.js. NO EDITAR.\n" +
    "// " + (note || "Grupo de comprobaciones, para que el backend de Kotlin\n// Playground pueda EJECUTARLO (el fichero completo no compila sin el\n// AAR de Filament, y ademas supera el limite de tamano del backend).") + "\n";
  const regs = checks.map(([n, fn]) => '    check("' + n + '", ::' + fn + ")\n").join("");
  const file =
    header + imports + "\n\n" + parts.join("\n\n") + "\n\n" +
    "private const val TEST_COUNT = " + checks.length + "\n\n" +
    "fun renderPipelineChecks(): String {\n" +
    "    val failures = ArrayList<String>()\n" +
    "    fun check(name: String, body: () -> String) {\n" +
    "        val result = try { body() } catch (error: Throwable) { \"EXCEPCION \" + error.javaClass.simpleName + \": \" + error.message }\n" +
    "        if (result != \"OK\") failures.add(name + \" -> \" + result)\n" +
    "    }\n" +
    regs +
    "    return if (failures.isEmpty()) \"OK (\" + TEST_COUNT + \" comprobaciones)\" else failures.joinToString(\" | \")\n" +
    "}\n";
  await fs.writeTextFile(out, file);
  return { out, bytes: file.length, chunks: wanted.size, missing };
};
