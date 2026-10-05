// Lean compile-check harness: like run.js but without the protocol template
// blob, so the payload stays small enough for the flaky playground endpoint.
const TAGTYPE = {"LinearLayout":"android.widget.LinearLayout","FrameLayout":"android.widget.FrameLayout","ScrollView":"android.widget.ScrollView","ImageView":"android.widget.ImageView","TextView":"android.widget.TextView","EditText":"android.widget.EditText","ImageButton":"android.widget.ImageButton","ProgressBar":"android.widget.ProgressBar","Spinner":"android.widget.Spinner","View":"android.view.View","androidx.recyclerview.widget.RecyclerView":"androidx.recyclerview.widget.RecyclerView","com.google.android.material.appbar.MaterialToolbar":"com.google.android.material.appbar.MaterialToolbar","com.google.android.material.button.MaterialButton":"com.google.android.material.button.MaterialButton","com.google.android.material.card.MaterialCardView":"com.google.android.material.card.MaterialCardView","com.google.android.material.textfield.TextInputLayout":"com.google.android.material.textfield.TextInputLayout","com.google.android.material.textfield.TextInputEditText":"com.google.android.material.textfield.TextInputEditText","com.lumiyaviewer.lumiya.ui.world.MinimapView":"com.lumiyaviewer.lumiya.ui.world.MinimapView"};
// Test files are cut out of the big tests_render.kt, so they keep that file's
// import list. Left alone, those imports drag half the project into the payload
// (and blow past the playground's size limit), so drop every import the file
// body never mentions by its simple name.
const IMPORT_RE = /^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$/;
function stripUnusedImports(text) {
  const lines = text.split("\n");
  const imports = [];
  const bodyLines = [];
  for (const line of lines) {
    const m = line.match(IMPORT_RE);
    if (m) imports.push({ raw: line, simple: m[2] || m[1].split(".").pop() });
    else bodyLines.push(line);
  }
  const body = bodyLines.join("\n");
  const keep = new Set();
  for (const im of imports) {
    if (new RegExp("\\b" + im.simple.replace(/[$]/g, "\\$") + "\\b").test(body)) keep.add(im.raw);
  }
  return lines.filter((l) => !IMPORT_RE.test(l) || keep.has(l)).join("\n");
}
// The playground's execute path 500s once the submitted code gets big (the whole
// slscene+slworld closure alone is ~684 kB, measured). Dropping *whole-line*
// comments (//, /*, *, */) is safe -- unlike a regex over the text, it cannot
// touch a string literal -- and buys ~200 kB, which is what makes the heavy
// groups small enough to run. Raw strings (""" ... """) are tracked so a line
// that merely *looks* like a comment inside one is left alone.
function stripWholeLineComments(text) {
  const lines = text.split("\n");
  const out = [];
  let raw = false;
  for (const line of lines) {
    const x = line.trim();
    if (!raw) {
      if (x.length && (x.startsWith("//") || x.startsWith("*") || x.startsWith("/*") || x.startsWith("*/"))) continue;
      if (x.length === 0) continue;
    }
    out.push(line);
    const quotes = (line.match(/"""/g) || []).length;
    if (quotes % 2 === 1) raw = !raw;
  }
  return out.join("\n");
}
module.exports = async function run({ fs, fetch, kotlinVersion = "2.0.21", extras = ["tests_render.kt"], entry = true, packages = null, skipPackages = null, onlyFiles = null, narrowPackages = null, dryRun = false, minify = true, appBase = "app/src/main/", harness = "tools/kcheck" }) {
  const base = appBase;
  const all = await fs.listFiles();
  const myFiles = {};
  for (const f of all) {
    if (f.path.startsWith(base + "java/") && f.path.endsWith(".kt")) {
      const text = await fs.readTextFile(f.path);
      myFiles[f.path.slice((base + "java/").length).replace(/\//g, "__")] = minify ? stripWholeLineComments(text) : text;
    }
  }
  const extraTexts = {};
  for (const f of all) {
    if (f.path.startsWith(harness + "/extra/") && f.path.endsWith(".kt") && f.bytes > 0 && extras.indexOf(f.path.split("/").pop()) >= 0) {
      const text = stripUnusedImports(await fs.readTextFile(f.path));
      extraTexts[f.path.split("/").pop()] = minify ? stripWholeLineComments(text) : text;
    }
  }
  let selected = myFiles;
  if (packages && packages.length) {
    // Package-level transitive closure: a file may use a class of its own
    // package without importing it, so packages are included whole.
    const packageOf = {};
    const symbols = {};
    for (const [name, text] of Object.entries(myFiles)) {
      const p = text.match(/^package\s+([\w.]+)/m);
      const pkg = p ? p[1] : "";
      packageOf[name] = pkg;
      for (const m of text.matchAll(/^(?:@\w+\s+)*(?:public |internal |private |open |sealed |abstract |data |enum |annotation |expect |actual )*(?:class|interface|object) (\w+)/gm)) {
        symbols[pkg + "." + m[1]] = pkg;
      }
    }
    const wanted = new Set(packages);
    const knownPackages = new Set(Object.values(packageOf));
    for (const text of Object.values(extraTexts)) {
      for (const m of text.matchAll(/^import\s+(com\.lumiyaviewer\.lumiya\.[\w.]+)/gm)) {
        const fqn = m[1];
        const pkg = symbols[fqn] !== undefined ? symbols[fqn] : fqn.replace(/\.[^.]+$/, "");
        if (knownPackages.has(pkg)) wanted.add(pkg);
      }
    }
    let grew = true;
    while (grew) {
      grew = false;
      for (const [name, text] of Object.entries(myFiles)) {
        if (!wanted.has(packageOf[name])) continue;
        for (const m of text.matchAll(/^import\s+(com\.lumiyaviewer\.lumiya\.[\w.]+)/gm)) {
          const fqn = m[1];
          const pkg = symbols[fqn] || fqn.replace(/\.[^.]+$/, "");
          if (packageOf[name] !== pkg && !wanted.has(pkg) && (symbols[fqn] !== undefined || Object.values(packageOf).includes(pkg))) {
            wanted.add(pkg);
            grew = true;
          }
        }
      }
    }
    selected = {};
    for (const [name, text] of Object.entries(myFiles)) {
      if (wanted.has(packageOf[name])) selected[name] = text;
    }
    // For a narrow check (e.g. "does ui.world still compile") the closure drags
    // in packages the check does not need and pushes the payload over the
    // backend's limit; these two knobs cut the closure down.
    if (skipPackages && skipPackages.length) {
      for (const name of Object.keys(selected)) {
        if (skipPackages.includes(packageOf[name])) delete selected[name];
      }
    }
    if (onlyFiles && onlyFiles.length && narrowPackages) {
      for (const name of Object.keys(selected)) {
        if (!narrowPackages.includes(packageOf[name])) continue;
        if (!onlyFiles.some((f) => name.endsWith(f))) delete selected[name];
      }
    }
    console.log("render subset: " + Object.keys(selected).length + "/" + Object.keys(myFiles).length +
      " files, packages " + [...wanted].sort().join(","));
  }
  const stubs = {};
  for (const f of all) {
    if (f.path.startsWith(harness + "/stubs/") && f.path.endsWith(".kt") && f.bytes > 0) {
      const text = await fs.readTextFile(f.path);
      if (text.trim().length) stubs["stub__" + f.path.split("/").pop()] = text;
    }
  }
  for (const f of all) {
    if (f.path.startsWith(harness + "/extra/") && f.path.endsWith(".kt") && f.bytes > 0 && extras.indexOf(f.path.split("/").pop()) >= 0) {
      if (extraTexts[f.path.split("/").pop()] !== undefined) stubs[f.path.split("/").pop()] = extraTexts[f.path.split("/").pop()];
    }
  }
  const entryStubs = {};
  if (entry) {
    entryStubs["aaa_entry.kt"] = 'package com.lumiyaviewer.lumiya.slproto.world\nfun main() { println("render pipeline: " + com.lumiyaviewer.lumiya.kcheck.renderPipelineChecks()) }\n';
  }
  const strings = await fs.readTextFile(base + "res/values/strings.xml");
  const stringKeys = [...strings.matchAll(/<string name="([^"]+)"/g)].map((m) => m[1]);
  const drawables = all.filter((f) => f.path.startsWith(base + "res/drawable/")).map((f) => f.path.split("/").pop().replace(".xml", ""));
  const layoutFiles = all.filter((f) => f.path.startsWith(base + "res/layout/"));
  stubs["stub__R.kt"] = `package com.lumiyaviewer.lumiya
object R {
    object string { ${stringKeys.map((k) => `const val ${k} = 1`).join("; ")} }
    object drawable { ${drawables.map((k) => `const val ${k} = 1`).join("; ")} }
    object layout { ${layoutFiles.map((f) => "const val " + f.path.split("/").pop().replace(".xml", "") + " = 1").join("; ")} }
    object id { const val toolbar = 1 }
}`;
  const camel = (n) => n.charAt(0).toUpperCase() + n.slice(1);
  const unknownTags = [];
  for (const f of layoutFiles) {
    const layoutName = f.path.split("/").pop().replace(".xml", "");
    const xml = await fs.readTextFile(f.path);
    const ids = [];
    const re = /<([A-Za-z0-9_.]+)([^>]*?)android:id="@\+id\/([A-Za-z0-9_]+)"/g;
    let m;
    let skipLayout = false;
    while ((m = re.exec(xml))) {
      const tag = m[1]; const id = m[3]; const type = TAGTYPE[tag];
      if (packages && tag.startsWith("com.lumiyaviewer")) {
        const pkg = tag.replace(/\.[^.]+$/, "");
        if (packages.indexOf(pkg) < 0) { skipLayout = true; break; }
      }
      if (!type) { unknownTags.push(tag); ids.push({ id, type: "android.view.View" }); } else ids.push({ id, type });
    }
    if (skipLayout) continue;
    const cls = layoutName.split("_").map(camel).join("") + "Binding";
    stubs["stub__binding_" + layoutName + ".kt"] = `package com.lumiyaviewer.lumiya.databinding
class ${cls} private constructor(val root: android.view.View) {
${ids.map((i) => `    val ${i.id}: ${i.type} get() = TODO()`).join("\n")}
    companion object {
        fun inflate(inflater: android.view.LayoutInflater): ${cls} = TODO()
        fun inflate(inflater: android.view.LayoutInflater, parent: android.view.ViewGroup?, attachToParent: Boolean): ${cls} = TODO()
        fun bind(view: android.view.View): ${cls} = TODO()
    }
}`;
  }
  const files = Object.assign({}, entryStubs, stubs, selected);
  const fileEntries = Object.entries(files);
  const orderedFileEntries = fileEntries
    .filter(([name]) => !name.startsWith("stub__") && !name.startsWith("com__"))
    .concat(fileEntries.filter(([name]) => name.startsWith("stub__") || name.startsWith("com__")));
  if (dryRun) {
    // Measure the submission without sending it: the playground's execute path
    // rejects large payloads, and this says how large is too large.
    let codeBytes = 0;
    for (const [, text] of orderedFileEntries) codeBytes += text.length;
    return {
      dryRun: true,
      fileCount: orderedFileEntries.length,
      codeBytes,
      biggest: orderedFileEntries
        .map(([name, text]) => ({ name, bytes: text.length }))
        .sort((a, b) => b.bytes - a.bytes)
        .slice(0, 6),
    };
  }
  const r = await fetch("https://api.kotlinlang.org/api/" + kotlinVersion + "/compiler/run", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ args: "", confType: "java", files: orderedFileEntries.map(([name, text]) => ({ name, text })), javaVersion: "1.8", hideCompileOutput: false })
  });
  const body = await r.text();
  let parsed = {};
  try { parsed = JSON.parse(body); } catch (e) { return { status: r.status, raw: body.slice(0, 500) }; }
  if (r.status !== 200) return { status: r.status, rawBody: body.slice(0, 300) };
  const out = [];
  for (const [file, list] of Object.entries(parsed.errors || {})) {
    for (const e of list) {
      out.push({ file, sev: e.severity, msg: e.message, line: e.interval && e.interval.start ? e.interval.start.line + 1 : null });
    }
  }
  const isMine = (o) => !o.file.startsWith("stub__");
  return {
    status: r.status,
    exception: parsed.exception ? parsed.exception.message : null,
    stdout: parsed.text || "",
    payloadBytes: body.length,
    counts: { total: out.length, mineErrors: out.filter((o) => isMine(o) && o.sev === "ERROR").length, stubErrors: out.filter((o) => !isMine(o) && o.sev === "ERROR").length },
    unknownTags: [...new Set(unknownTags)],
    mine: out.filter((o) => isMine(o) && o.sev === "ERROR"),
    stubErrs: out.filter((o) => !isMine(o) && o.sev === "ERROR").slice(0, 12)
  };
};
