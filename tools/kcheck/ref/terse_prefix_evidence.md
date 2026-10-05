# Why the terse `TextureEntry` is four bytes longer than the others

Evidence file, kept in the repo so the causal closure of the 1259 `B` and the 47/50
`C` blobs survives the session that found it. Nothing here is a build input: it is
the proof that the terse field does not hold the bare entry.

Why it matters: on the device, **every** refusal came from
`ImprovedTerseObjectUpdate` (`ObjectUpdate` 513 accepted / 0 refused,
`ObjectUpdateCompressed` 639 / 0, terse 455 accepted / **1004 refused**). The two
clean paths share one property the terse one does not have — a four-byte length in
front of the entry inside their `Data` blob — and the sender below writes the same
four bytes *inside the terse field*. The decoder's compressed path already consumes
them (`reader.u32()`); the terse path does not. That offsets every field by four
bytes, which is exactly the signature the reference reader reports: a cut inside a
mandatory field (`C`) for most blobs, and a *silently wrong* entry for those whose
shifted read happens to complete — which the reference either rejects, or, worse,
**accepts with the wrong faces**.

The parser is deliberately still untouched. What the build ships is the instrument
that measures the prefix on the device
(`PARSER TextureEntry prefijo de 4 bytes …`).

## 1. The official template (Linden Lab)

`https://raw.githubusercontent.com/secondlife/viewer/main/scripts/messages/message_template.msg`
(downloaded to `scratch/ref/ll_official_message_template.msg` during the
investigation; 240 KB, do not re-commit). The terse message's `TextureEntry` is its
**own field** of the `ObjectData` block, *not* something inside `Data`:

```
// packed terse object update format
{
    ImprovedTerseObjectUpdate High 15 Trusted Unencoded
    {
        RegionData Single
        {   RegionHandle    U64 }
        {   TimeDilation    U16 }
    }
    {
        ObjectData Variable
        {   Data            Variable 1  }
        {   TextureEntry    Variable 2  }
    }
}
```

Our own `assets/message_template.msg` declares the same
`{ Data Variable 1 } { TextureEntry Variable 2 }`, so the template is right and the
field framing (its own two-byte length word) is right. What the template does **not**
say is what the field's *content* is. Both senders below are equally explicit about
that, and both write four bytes there.

## 2. The sender (OpenSim)

`https://github.com/opensim/opensim` — `OpenSim/Region/ClientStack/Linden/UDP/LLClientView.cs`,
the terse block writer (the current one, which takes the texture entry as a
parameter). `pos` is the position of the terse field's own two-byte length word
(the template's `Variable 2`), and the four bytes that follow it are a
**little-endian `U32` of the entry's length** — the two high bytes are always zero,
and the author's own `// wtf ???` is in the upstream source:

```csharp
// texture entry block size
            if (te is null)
            {
                data[pos++] = 0;
                data[pos++] = 0;
            }
            else
            {
                int len = te.Length & 0x7fff;
                int totlen = len + 4;
                data[pos++] = (byte)totlen;
                data[pos++] = (byte)(totlen >> 8);
                data[pos++] = (byte)len; // wtf ???
                data[pos++] = (byte)(len >> 8);
                data[pos++] = 0;
                data[pos++] = 0;
                Buffer.BlockCopy(te, 0, data, pos, len);
                pos += len;
            }
            // total size 63 or 47 + (texture size + 4)
```

Note the two words the writer emits for the *same* number: `totlen = len + 4` goes
into the field's length word (which is what `Variable 2` means, and it is why the
field is four bytes longer than the entry), and `len` goes into the field's content
as a `U32`-shaped prefix. Reading the field's content naively therefore hands the
parser four extra bytes. The `te is null` branch also shows what the "legal empty"
case is: a **zero-length field** (two zero bytes), not a truncated one.

## 3. The reader that talks to the real grid (LibreMetaverse)

`https://github.com/cinderblocks/libremetaverse` — `ObjectManager.cs`,
`ImprovedTerseObjectUpdateHandler`: it hands the field content to the entry parser
**starting four bytes in**, four bytes whose purpose the author could not name:

```csharp
// Textures
                    // FIXME: Why are we ignoring the first four bytes here?
                    if (block.TextureEntry.Length != 0)
                        update.Textures = new Primitive.TextureEntry(block.TextureEntry, 4, block.TextureEntry.Length - 4);
```

So the reference implementation used against Second Life itself skips the four
bytes. That is the same four bytes this project's compressed path already consumes.

## 4. The reproduction on real byte layouts

`extra/probe_prefix.kt` (standalone) and the `terseprefix` check in
`tests_render.kt` build the entries with this project's own
`LLPrimitive::packTEMessage`-faithful packer, prefix each with `[u32 len]`, and
report what our parser *and* `TextureEntryReference` do with them:

```
una cara   sin prefijo 64 B: 11 campos, completa, la referencia ACEPTA
una cara   con prefijo 68 B: el parser para en el campo 4 (offsetS), la referencia RECHAZA  -> categoria C
dos caras  sin prefijo 81 B: 11 campos, completa, la referencia ACEPTA
dos caras  con prefijo 85 B: 11 campos y la referencia ACEPTA, pero 7 de 8 caras no son las del objeto -> falso positivo silencioso
saltando los 4 bytes, en los dos casos: la entrada vuelve exacta (11 campos, cara 0 11111111) y la referencia ACEPTA
```

That is the device's signature reproduced without a device: the four bytes move
every field, and skipping them restores the entry exactly.

## Re-downloading

```sh
curl -o message_template.msg \
  https://raw.githubusercontent.com/secondlife/viewer/main/scripts/messages/message_template.msg
grep -n -A 12 'packed terse object update format' message_template.msg

curl -o LLClientView.cs \
  https://raw.githubusercontent.com/opensim/opensim/master/OpenSim/Region/ClientStack/Linden/UDP/LLClientView.cs
grep -n -B 4 -A 20 'int totlen = len + 4' LLClientView.cs
```
