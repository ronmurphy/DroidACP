package com.droidacp.core.people

/**
 * One agent's look, as it chose it once (or a default until it does): the option ids of
 * `sprites.txt`. [shirt] isn't chosen: it's the agent's ([PixelPeople.shirtFor]), so people
 * from different agents stay apart. [letter] goes on the shirt (A-Z, 0-9), or none.
 */
data class Look(
    val skin: String = "light",
    val hair: String = "short",
    val hairColor: String = "brown",
    val accessories: List<String> = emptyList(),
    val letter: Char? = null,
    val shirt: String = "unknown",
)

/** A drawn person, [width] x [height] pixels of ARGB; 0 is transparent. */
class Sprite(val width: Int, val height: Int, val pixels: IntArray)

/**
 * The Pixel People: small people drawn from `sprites.txt`, one per agent, posed by what the
 * agent is doing. The file's format is described at its top; TabDesk reads the same file, so
 * keep this reader and `tools/pixelpeople/preview.py` in step with it.
 */
class PixelPeople private constructor(
    private val palette: Map<Char, Int>,
    private val options: Map<String, Map<String, Int>>,
    private val shirtColors: Map<String, List<Int>>,
    private val face: Section,
    private val hairs: Map<String, Section>,
    private val accessoryDefs: Map<String, Section>,
    private val parts: Map<String, Grid>,
    private val poseDefs: Map<String, Section>,
    private val signs: Map<String, Grid>,
    private val glyphs: Map<Char, List<String>>,
) {
    val poses: Set<String> get() = poseDefs.keys
    val skins: List<String> get() = options["skin"].orEmpty().keys.toList()
    val hairStyles: List<String> get() = hairs.keys.toList()
    val hairColors: List<String> get() = options["haircolor"].orEmpty().keys.toList()
    val accessories: List<String> get() = accessoryDefs.keys.toList()
    val shirts: List<String> get() = shirtColors.keys.toList()
    val letters: Set<Char> get() = glyphs.keys

    /** [pose] drawn for [look]; an unknown pose is drawn as [IDLE_POSE], unknown options as their first. */
    fun render(pose: String, look: Look): Sprite {
        val px = IntArray(SIZE * SIZE)
        val p = poseDefs[pose] ?: poseDefs.getValue(IDLE_POSE)
        val skin = options["skin"]?.get(look.skin) ?: options.getValue("skin").values.first()
        val hairColor = options["haircolor"]?.get(look.hairColor) ?: options.getValue("haircolor").values.first()
        val shirt = shirtColors[look.shirt] ?: shirtColors.getValue("unknown")
        val colors = HashMap(palette).apply {
            put('S', skin)
            put('s', shade(skin, 0.85))
            put('r', mix(skin, 0xE0707A, 0.35))
            put('H', hairColor)
            put('h', shade(hairColor, 0.75))
            put('L', if (shirt.map(::luminance).average() > 150) getValue('k') else getValue('W'))
        }

        fun color(ch: Char, y: Int, extra: Map<Char, Int>): Int? = extra[ch] ?: when (ch) {
            'T' -> shirt[y % shirt.size]
            't' -> shade(shirt[y % shirt.size], 0.8)
            else -> colors[ch]
        }

        fun stamp(rows: List<String>, ox: Int, oy: Int, extra: Map<Char, Int> = emptyMap()) {
            for ((j, row) in rows.withIndex()) {
                for ((i, ch) in row.withIndex()) {
                    if (ch == '.') continue
                    val x = ox + i
                    val y = oy + j
                    if (x !in 0 until SIZE || y !in 0 until SIZE) continue
                    val c = color(ch, y, extra) ?: continue
                    px[y * SIZE + x] = (0xFF shl 24) or c
                }
            }
        }

        val head = p.pair("head")
        val eyes = p.word("eyes") ?: "open"
        val hair = hairs[look.hair] ?: hairs.values.first()
        if (head != null) {
            hair.grids.filter { it.label == "back" }.forEach { stamp(it.rows, head.first + it.x, head.second + it.y) }
        }
        for (line in p.lines) {
            when (line.first()) {
                "use" -> parts[line[1]]?.let { stamp(it.rows, line[2].toInt(), line[3].toInt()) }
                "grid" -> p.gridsByLine[line]?.let { stamp(it.rows, it.x, it.y) }
                "@head" -> if (head != null) {
                    val (hx, hy) = head
                    face.grids.forEach { stamp(it.rows, hx + it.x, hy + it.y) }
                    face.lines.firstOrNull { it.first() == "eyes" && it.getOrNull(1) == eyes }?.drop(2)?.forEach { xy ->
                        val (ex, ey) = xy.split(',').map(String::toInt)
                        val x = hx + ex
                        val y = hy + ey
                        if (x in 0 until SIZE && y in 0 until SIZE) px[y * SIZE + x] = (0xFF shl 24) or colors.getValue('E')
                    }
                    hair.grids.filter { it.label == "front" }.forEach { stamp(it.rows, hx + it.x, hy + it.y) }
                    for (name in look.accessories.distinct().take(MAX_ACCESSORIES)) {
                        val acc = accessoryDefs[name] ?: continue
                        val extra = acc.lines.filter { it.first() == "A" || it.first() == "a" }
                            .associate { it.first().single() to it[1].toInt(16) }
                        acc.grids.forEach { stamp(it.rows, hx + it.x, hy + it.y, extra) }
                    }
                }
                "letter" -> look.letter?.uppercaseChar()?.let(glyphs::get)?.let { g ->
                    stamp(g.map { it.replace('#', 'L') }, line[1].toInt(), line[2].toInt())
                }
                "effect" -> signs[line[1]]?.let { stamp(it.rows, line[2].toInt(), line[3].toInt()) }
            }
        }
        return Sprite(SIZE, SIZE, px)
    }

    /** The shirt for an agent, from its ACP `agentInfo` name (or title). */
    fun shirtFor(agentName: String?): String {
        val n = agentName?.lowercase() ?: return "unknown"
        val id = SHIRT_NAMES.firstOrNull { (key, _) -> key in n }?.second ?: return "unknown"
        return if (id in shirtColors) id else "unknown"
    }

    /**
     * A look for an agent that hasn't chosen its own yet: the same every time for [seed] (its
     * project folder, say), so its person doesn't change between restarts or devices.
     */
    fun defaultLook(seed: String, shirt: String, letter: Char?): Look {
        var h = 17
        for (c in seed) h = h * 31 + c.code
        val n = h and 0x7FFFFFFF
        val styles = hairStyles.filter { it != "bald" }
        return Look(
            skin = skins[n % skins.size],
            hair = styles[(n / 7) % styles.size],
            hairColor = NATURAL_HAIR.filter { it in hairColors }.let { it[(n / 53) % it.size] },
            letter = letter?.uppercaseChar()?.takeIf { it in glyphs },
            shirt = shirt,
        )
    }

    private class Grid(val label: String, val x: Int, val y: Int, val rows: MutableList<String> = mutableListOf())

    private class Section(val kind: String, val name: String, val args: List<String>) {
        val lines = mutableListOf<List<String>>()
        val grids = mutableListOf<Grid>()
        val gridsByLine = HashMap<List<String>, Grid>()

        fun pair(key: String): Pair<Int, Int>? {
            val l = lines.firstOrNull { it.first() == key } ?: return null
            if (l.getOrNull(1) == "none") return null
            return l[1].toInt() to l[2].toInt()
        }

        fun word(key: String): String? = lines.firstOrNull { it.first() == key }?.getOrNull(1)
    }

    companion object {
        const val SIZE = 32
        const val IDLE_POSE = "sleep"
        const val MAX_ACCESSORIES = 2

        private val KINDS = setOf("palette", "option", "shirt", "face", "hair", "accessory", "part", "pose", "sign", "glyph")

        // Agent names (as ACP adapters report them) to shirts; the first that matches wins.
        private val SHIRT_NAMES = listOf(
            "claude" to "claude",
            "gemini" to "gemini",
            "codex" to "codex",
            "openai" to "codex",
            "copilot" to "copilot",
            "qwen" to "qwen",
            "mistral" to "mistral",
            "vibe" to "mistral",
            "deepseek" to "deepseek",
            "opencode" to "opencode",
            "goose" to "goose",
            "ollama" to "local",
            "llama" to "local",
            "lmstudio" to "local",
        )

        private val NATURAL_HAIR = listOf("black", "dark-brown", "brown", "auburn", "ginger", "blond", "platinum", "grey")

        /** The people in this library's `sprites.txt`. */
        val default: PixelPeople by lazy {
            // An absolute path: R8 may move this class to another package in a release build.
            val text = PixelPeople::class.java.getResourceAsStream("/com/droidacp/core/people/sprites.txt")
                ?.bufferedReader()?.use { it.readText() }
                ?: error("sprites.txt is missing")
            parse(text)
        }

        fun parse(text: String): PixelPeople {
            val sections = mutableListOf<Section>()
            var cur: Section? = null
            var grid: Grid? = null
            for (raw in text.lines()) {
                if (grid != null) {
                    if (raw.isBlank()) grid = null else grid.rows += raw.trim()
                    continue
                }
                val s = raw.trim()
                if (s.isEmpty() || s.startsWith("#")) continue
                val w = s.split(Regex("\\s+"))
                when {
                    w[0] in KINDS -> {
                        cur = Section(w[0], w.getOrElse(1) { "" }, w.drop(2)).also(sections::add)
                        if (w[0] == "glyph") grid = Grid("glyph", 0, 0).also { cur.grids += it }
                    }
                    w[0] == "grid" -> {
                        val section = cur ?: error("a grid outside a section")
                        grid = Grid(w[1], w[2].toInt(), w[3].toInt())
                        section.grids += grid
                        section.lines += w
                        section.gridsByLine[w] = grid
                    }
                    else -> (cur ?: error("\"$s\" outside a section")).lines += w
                }
            }
            val palette = HashMap<Char, Int>()
            val options = LinkedHashMap<String, LinkedHashMap<String, Int>>()
            val shirts = LinkedHashMap<String, List<Int>>()
            val hairs = LinkedHashMap<String, Section>()
            val accessories = LinkedHashMap<String, Section>()
            val parts = HashMap<String, Grid>()
            val poses = LinkedHashMap<String, Section>()
            val signs = HashMap<String, Grid>()
            val glyphs = HashMap<Char, List<String>>()
            var face: Section? = null
            for (sec in sections) {
                when (sec.kind) {
                    "palette" -> sec.lines.forEach { palette[it[0].single()] = it[1].toInt(16) }
                    "option" -> sec.lines.forEach { options.getOrPut(sec.name, ::LinkedHashMap)[it[0]] = it[1].toInt(16) }
                    "shirt" -> shirts[sec.name] = sec.args.map { it.toInt(16) }
                    "face" -> face = sec
                    "hair" -> hairs[sec.name] = sec
                    "accessory" -> accessories[sec.name] = sec
                    "part" -> parts[sec.name] = sec.grids.single()
                    "pose" -> poses[sec.name] = sec
                    "sign" -> signs[sec.name] = sec.grids.single()
                    "glyph" -> glyphs[sec.name.single()] = sec.grids.single().rows
                }
            }
            return PixelPeople(
                palette, options, shirts, face ?: error("no face in sprites.txt"), hairs, accessories, parts, poses, signs, glyphs,
            )
        }

        private fun shade(c: Int, f: Double): Int =
            (((c shr 16 and 0xFF) * f).toInt() shl 16) or (((c shr 8 and 0xFF) * f).toInt() shl 8) or ((c and 0xFF) * f).toInt()

        private fun mix(a: Int, b: Int, t: Double): Int {
            fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt()
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private fun luminance(c: Int): Double = 0.3 * (c shr 16 and 0xFF) + 0.59 * (c shr 8 and 0xFF) + 0.11 * (c and 0xFF)
    }
}
