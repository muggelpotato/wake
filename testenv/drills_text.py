#!/usr/bin/env python3
"""Text drills against the source tree and a running Wake server.

Covers what `core/text` owns. The first half needs no server: it reads the bundled language file and
the source that resolves it, and answers the two questions nobody can answer by eye -- every key the
code asks for exists, and every key the file carries is reachable. It also holds the file's colours
section to the fallbacks in WakeColors and its header to both, and to the placeholders, because a
comment that has drifted is worse than none, and it keeps both lists clear of names that are already
taken -- a name two resolvers claim goes to the nearer one, so a colour or a placeholder that
collides quietly takes the other's place. Last it pairs every `<hint>` with the call site that fills
it, in both directions.

The second half is the loading half, which only a reload can show: a deployed file overriding the
bundled one key by key, a key deleted out of it falling back rather than reaching the player as
`<some.key>`, a value that is not a string, one an admin blanked, a tag it cannot resolve, a file
that is not YAML at all, one the server cannot open, a template the resolver has to drop, a colour an
admin recoloured and one they spelled wrong, and a language that is not there. Plus the four things
every message depends on -- the palette reaching the client in both its `<tag>` and its `$var` form,
the hints switch taking the bulb away, the keys the code builds by concatenation resolving to
something, and a value the player chose arriving as text rather than as markup.

    python testenv/drills_text.py

What is left to TESTPLAN.md: how any of it looks, which needs a client -- alignment, a hover, a
click, and the `<shadow>` gate, which only shows on a server older than 1.21.4.

Needs a server up with RCON (./gradlew runServer). Exits non-zero if a check fails.
"""

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import ROOT, WAKE, Log, Rcon, step  # noqa: E402

LANG = ROOT / "src" / "main" / "resources" / "lang" / "en_us.yml"
BUNDLED_CONFIG = ROOT / "src" / "main" / "resources" / "config.yml"
COLORS = ROOT / "src" / "main" / "java" / "dev" / "muggel" / "wake" / "core" / "text" / "WakeColors.java"
SRC = ROOT / "src" / "main" / "java"
DEPLOYED = WAKE / "lang" / "en_us.yml"
CONFIG = WAKE / "config.yml"

COLOUR_KEY = "colors."
ENTRY = re.compile(r"^(\s*)([a-z_][a-z0-9_]*):(?: (.*))?$")
KEY_LITERAL = re.compile(r'"([a-z][a-z0-9_]*(?:\.[a-z0-9_]*)+)"')
PALETTE_ENTRY = re.compile(r'^\s+[A-Z_]+\("([a-z_]+)", 0x([0-9A-Fa-f]{6})\)')
HEADER_COLOUR = re.compile(r"^#\s+<([a-z_]+)>")
# a tag carrying no arguments: <click:...> and friends bring their own and are never placeholders
PLAIN_TAG = re.compile(r"(?<!\\)</?([a-z_]+)>")
HEX_COLOUR = re.compile(r"§x((?:§[0-9a-fA-F]){6})")
NAMED_COLOURS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
                 "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua", "red",
                 "light_purple", "yellow", "white"}
# what a value may hold besides the palette and a placeholder: everything MiniMessage brings itself
STANDARD_TAGS = NAMED_COLOURS | {
    "b", "bold", "i", "italic", "em", "u", "underlined", "st", "strikethrough", "obf", "obfuscated",
    "reset", "br", "newline", "rainbow", "pride", "gradient", "transition", "shadow", "sprite",
    "key", "lang", "tr", "tl", "translate", "lang_or", "tr_or", "translate_or", "insert", "click",
    "hover", "font", "score", "nbt", "data", "selector", "sel", "head", "color", "colour", "c",
}
PALETTE_BYPASS = re.compile(r"<#[0-9a-fA-F]{6}>|<(?:c|colou?r):#|&[0-9a-fk-or]|</?(?:" + "|".join(NAMED_COLOURS) + r")>")
BULB = "\U0001F4A1"
MESSAGE_CALL = re.compile(r"\.(?:send|getComponent)\(")
HINT_CALL = re.compile(r"\bhint\(")
# the resolver reaches a call either as that call or under the name it was kept in
HINT = re.compile(r"\bhint\b")
# a key the source hands around under a name instead of writing it into the call
LOCAL_KEY = re.compile(r"\bString (\w+) = ([^;]+);")
# a constant holding the front of a key, and the place a literal tail is joined onto one
CONSTANT_KEY = re.compile(r'\bString (\w+) = "([a-z][a-z0-9_.]*)"')
JOINED_KEY = re.compile(r'\b(\w+) \+ "([a-z][a-z0-9_]*)"')

# the message every live check reads back: a refusal a console can always provoke. It leads with a
# template that leads with another, so the chain, the prefix's $variables, an <accent> tag and a
# value the sender chose are all read back from one line
PROBE = "wo -context -delete zzz-no-such-context"
PROBE_KEY = "commands.obu.context.missing"
PROBE_TEMPLATE = '"<alert>Context <accent><context></accent> does not exist"'

failures = []
rcon = None


def ok(label):
    print(f"  ok    {label}")


def bad(label, detail):
    print(f"  FAIL  {label}: {str(detail).strip()[:200]}")
    failures.append(label)


def read_yaml(path):
    """{dotted key: raw value} plus the comment lines, out of the flat files Wake ships."""
    keys, comments, stack = {}, [], []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith("#"):
            comments.append(line)
            continue
        match = ENTRY.match(line)
        if not match:
            continue
        del stack[len(match.group(1)) // 2:]
        stack.append(match.group(2))
        if match.group(3):
            keys[".".join(stack)] = match.group(3).strip("'\"")
    return keys, comments


def palette_entries():
    """[(tag, hex)] straight out of WakeColors.java, in declaration order and with any duplicate kept."""
    return [match.groups() for match
            in map(PALETTE_ENTRY.match, COLORS.read_text(encoding="utf-8").splitlines()) if match]


def palette():
    """{tag: hex} straight out of WakeColors.java, in declaration order."""
    return dict(palette_entries())


def spelled_palette(lang):
    """[(tag, hex)] out of the language file's own colours section, in the order it lists them."""
    return [(key[len(COLOUR_KEY):], value.lstrip("#").upper())
            for key, value in lang.items() if key.startswith(COLOUR_KEY)]


def messages(lang):
    """The file without its colours section: the entries that are templates."""
    return {key: value for key, value in lang.items() if not key.startswith(COLOUR_KEY)}


def header_tags(comments):
    """The tags the header's table lists, in order: the run of `<tag>` lines under its heading."""
    at = next((index for index, line in enumerate(comments) if "Wake color tags" in line), -1)
    listed = []
    for line in comments[at + 1:]:
        match = HEADER_COLOUR.match(line)
        if not match:
            break
        listed.append(match.group(1))
    return listed


def source_keys(lang_roots, config_keys):
    """Message keys named in the source: the exact ones, the prefixes a key is built onto, and the
    ones only half written -- a constant holding the front, a literal tail joined onto it. Both checks
    below read literals, so a key spelled `PREFIX + "title"` is credited to its prefix and asked about
    by nobody: it is a finding, not a spelling to put back together."""
    exact, prefixes, halves = {}, {}, {}
    for file in sorted(SRC.rglob("*.java")):
        text = file.read_text(encoding="utf-8")
        for literal in KEY_LITERAL.findall(text):
            if literal.split(".")[0] not in lang_roots or literal in config_keys:
                continue
            (prefixes if literal.endswith(".") else exact).setdefault(literal, file)
        named = {name: value for name, value in CONSTANT_KEY.findall(text) if value.endswith(".")}
        for name, tail in JOINED_KEY.findall(text):
            if name in named and (named[name] + tail).split(".")[0] in lang_roots:
                halves.setdefault(named[name] + tail, file)
    return exact, prefixes, halves


def drill_keys(lang, exact, prefixes, halves):
    print("\nthe code and the language file name the same keys")
    missing = {key: path for key, path in exact.items() if key not in lang}
    for key, path in sorted(missing.items()):
        bad("key with no entry", f"{key} <- {path.relative_to(ROOT)}")
    for key, path in sorted(halves.items()):
        bad("key assembled from a constant and a tail", f"{key} <- {path.relative_to(ROOT)}")
    if not missing and not halves:
        ok(f"all {len(exact)} keys the code asks for have an entry, and each is written out in full")

    step(f"credited by a prefix the code builds onto: {', '.join(sorted(prefixes)) or 'none'}")
    # a template is credited by `templates.`, the prefix MessageManager reads them under; what asks
    # whether the messages still lean on each of them is drill_templates
    unreached = [key for key in lang if key not in exact
                 and not any(key.startswith(name) for name in prefixes)]
    for key in unreached:
        bad("entry nothing reads", key)
    if not unreached:
        ok(f"all {len(lang)} entries are reachable from the source")


def drill_palette(lang, comments):
    print("\nthe palette is the only source of colour")
    bypassed = [key for key, value in messages(lang).items() if PALETTE_BYPASS.search(value)]
    for key in bypassed:
        bad("colour outside the palette", f"{key}: {lang[key]}")
    if not bypassed:
        ok("no raw hex, legacy code or named colour in any message")

    # MessageManager stacks the palette in front of MiniMessage's own tags, so a name it reuses is a
    # standard tag quietly replaced everywhere; a name used twice does not boot at all
    entries = palette_entries()
    tags = [tag for tag, _ in entries]
    clashes = sorted({tag for tag in tags if tag in STANDARD_TAGS} | {tag for tag in tags if tags.count(tag) > 1})
    for tag in clashes:
        bad("palette tag on a name that is taken", tag)
    if not clashes:
        ok(f"every one of the {len(tags)} tags is its own name and none is a MiniMessage tag")

    # the file carries the palette an admin edits, WakeColors only what a tag it leaves out falls back to
    defined = [(tag, hex_value.upper()) for tag, hex_value in entries]
    spelled = spelled_palette(lang)
    if spelled == defined:
        ok(f"the colours section spells every one of WakeColors.java's defaults ({len(defined)} entries)")
    else:
        bad("colours section", f"file says {spelled}, WakeColors says {defined}")

    if header_tags(comments) == tags:
        ok("and the header's table names the same tags in the same order")
    else:
        bad("header colour table", f"header says {header_tags(comments)}, WakeColors says {tags}")

    variables = next((set(re.findall(r"\$([a-z_]+)", line)) for line in comments
                      if "Palette variables" in line), set())
    if variables == set(palette()):
        ok("and so does its list of $variables")
    else:
        bad("header $variables", f"{sorted(variables)} vs {sorted(palette())}")


def templates(lang):
    """The shared fragments every message may lean on, as {name: raw}."""
    return {key.split(".", 1)[1]: value for key, value in lang.items() if key.startswith("templates.")}


def drill_templates(lang, comments):
    print("\nthe templates every message leans on")
    shared = templates(lang)
    messages = {key: value for key, value in lang.items() if not key.startswith("templates.")}
    used = {tag for value in messages.values() for tag in PLAIN_TAG.findall(value)}
    used |= {name for value in messages.values() for name in re.findall(r"<([a-z][a-z0-9_]*):'", value)}

    # a template nothing leans on is decoration an admin would edit and never see move
    for name in sorted(set(shared) - used):
        bad("template nothing uses", name)
    if set(shared) <= used:
        ok(f"each of the {len(shared)} templates is used by at least one message")

    # a placeholder answers before a template, so one taking a name the plugin fills would apply only
    # in the messages that leave it unfilled: one name meaning two things, decided by the call site
    listed = next((comments[at + 1] for at, line in enumerate(comments[:-1])
                   if "Dynamic placeholders" in line), "")
    filled = set(re.findall(r"<([a-z_]+)>", listed))
    stolen = sorted(set(shared) & (filled | STANDARD_TAGS | set(palette())))
    for name in stolen:
        bad("template on a name that is already answered", name)
    if not stolen:
        ok("and none of them takes a name a placeholder, a colour or MiniMessage already answers to")

    # a message spelling out what a template holds is the drift the templates exist to end -- including
    # mid-value, which is where a panel that arranges its own rows hides one
    spelled = sorted({key for key, value in messages.items()
                      for name, raw in shared.items()
                      if "<text>" not in raw and len(raw) > 8 and raw in value})
    for key in spelled:
        bad("message spells out a template instead of using it", key)
    if not spelled:
        ok("and no message writes a template's decoration out by hand, at the front or inside")


def drill_placeholders(lang, comments):
    print("\nthe header names exactly the placeholders the file uses")
    shared = templates(lang)
    used = {tag for key, value in lang.items() if not key.startswith("templates.")
            for tag in PLAIN_TAG.findall(value)}
    used -= STANDARD_TAGS | set(palette()) | set(shared)
    listed = next((comments[at + 1] for at, line in enumerate(comments[:-1])
                   if "Dynamic placeholders" in line), "")
    declared = set(re.findall(r"<([a-z_]+)>", listed))
    for name in sorted(used - declared):
        bad("placeholder used but not documented", name)
    for name in sorted(declared - used):
        bad("placeholder documented but unused", name)
    if used == declared:
        ok(f"all {len(used)} placeholders are declared, and every declared one is used")

    # a placeholder answers before both, and only in the messages that supply it: a name it shares
    # with a colour is that colour everywhere else and the placeholder here
    taken = sorted(declared & (STANDARD_TAGS | set(palette()) | set(templates(lang))))
    for name in taken:
        bad("placeholder on a name that is taken", name)
    if not taken:
        ok("and no placeholder name is one a colour tag, a template or MiniMessage already answers to")


def arguments(text, at):
    """What sits between the parentheses of the call whose opening one is at `at`."""
    depth = 0
    for pos in range(at, len(text)):
        depth += (text[pos] == "(") - (text[pos] == ")")
        if depth == 0:
            return text[at + 1:pos]
    return text[at + 1:]


def drill_hints(lang):
    print("\nevery hint bulb and the line it sits on")
    before = len(failures)
    sources = {file: file.read_text(encoding="utf-8") for file in sorted(SRC.rglob("*.java"))}
    anchors = {key for key, value in lang.items() if "<hint>" in value}
    bulbs = {key for text in sources.values() for call in HINT_CALL.finditer(text)
             for key in KEY_LITERAL.findall(arguments(text, call.end() - 1))}
    filled = set()
    for file, text in sources.items():
        aliases = {name: KEY_LITERAL.findall(rhs) for name, rhs in LOCAL_KEY.findall(text)}
        for call in MESSAGE_CALL.finditer(text):
            args = arguments(text, call.end() - 1)
            named = set(KEY_LITERAL.findall(args)) - bulbs
            named.update(key for name, keys in aliases.items() if re.search(rf"\b{name}\b", args) for key in keys)
            if HINT.search(args):
                filled |= named
                # a resolver on a line that anchors nothing is dropped without a word
                for key in sorted(named - anchors):
                    bad("hint filled into a line that anchors none", f"{key} <- {file.relative_to(ROOT)}")
            else:
                # an unfilled anchor reaches the player as `<hint>`
                for key in sorted(named & anchors):
                    bad("line sent without the hint it anchors", f"{key} <- {file.relative_to(ROOT)}")
    for key in sorted(anchors - filled):
        bad("line anchors a hint no call site fills", key)
    for key, value in sorted((key, lang.get(key, "")) for key in bulbs):
        if not value.startswith(" ") or "<prefix>" in value:
            bad("bulb that is not a fragment of the line it joins", f"{key}: {value}")
    if len(failures) == before:
        ok(f"each of the {len(anchors)} anchored lines is filled by one of {len(bulbs)} bulbs, and no other line carries one")


def colours(reply):
    """The hex colours a raw reply carries, the way the client receives them."""
    return {codes.replace("§", "").upper() for codes in HEX_COLOUR.findall(reply)}


def reload_and_expect(label, needle, absent=None):
    rcon.run("wake reload")
    reply = rcon.run(PROBE)
    if needle in reply and (absent is None or absent not in reply):
        ok(label)
    else:
        bad(label, reply)
    return reply


def deploy(mutate):
    """Rewrites the deployed language file, answering with the text to put back."""
    original = DEPLOYED.read_text(encoding="utf-8")
    if PROBE_TEMPLATE not in original:
        raise SystemExit(f"{DEPLOYED} does not carry the probed template -- a previous run left it edited?")
    DEPLOYED.write_text(mutate(original), encoding="utf-8")
    return original


def recolour(text, tag, value):
    """The deployed file with one entry of its colours section rewritten."""
    recoloured = re.sub(rf"^(  {tag}: ).*$", lambda match: match.group(1) + value, text, count=1, flags=re.M)
    if recoloured == text:
        raise SystemExit(f"{DEPLOYED} spells no colour for {tag} -- it predates the section, "
                         f"delete it and let the server write it again.")
    return recoloured


def restore(original):
    DEPLOYED.write_text(original, encoding="utf-8")
    rcon.run("wake reload")


def drill_rendering():
    print("\nwhat reaches the client")
    reply = rcon.run(PROBE)
    if "does not exist" in reply and "zzz-no-such-context" in reply:
        ok("a message renders its text and the value it was given")
    else:
        bad("baseline render", reply)
    if "<" not in reply:
        ok("and no tag leaks through as text")
    else:
        bad("tag leaked", reply)

    reply = rcon.run("wo -context default")  # a console is not an entity
    if "/execute as <targets>" in reply:
        ok("an escaped tag shows as the text it spells, not as markup and not as its escape")
    else:
        bad("escaped tag", reply)

    # the $var in the prefix's gradient and the <tag> in the message are read off pinned colours in
    # drill_palette_override: the deployed palette is the admin's to edit, and one they spell as an exact
    # legacy colour reaches the client as §f rather than as a hex nothing here would find

    # the switch is the one half of a hint no source-tree check can see; it is left on, the way it ships
    for switch, shown in (("false", False), ("true", True)):
        rcon.run(f"wake hints {switch}")
        reply = rcon.run("wo -context")
        if (BULB in reply) == shown and "<" not in reply:
            ok(f"the bulb is {'there' if shown else 'gone'} with hints {switch}, and nothing of its tag either way")
        else:
            bad(f"the bulb with hints {switch}", reply)


def drill_built_keys():
    print("\nthe keys the code builds rather than writes")
    log = Log()
    for command, label in (("wake help", "every root's module description"),
                           ("wake reload", "every module's reload outcome")):
        reply = rcon.run(command)
        if reply.strip() and "<" not in reply:
            ok(f"{label} resolved in /{command}")
        else:
            bad(f"built key in /{command}", reply)
    if "Missing message key" not in log.read():
        ok("and the console named no key it could not find")
    else:
        bad("built key", log.read())


def drill_untrusted_value():
    print("\na value the player chose is text, never markup")
    hostile = "<red><click:run_command:'/op me'>owned"
    reply = rcon.run(f"wo -context -delete {hostile}")
    if hostile in reply:
        ok("MiniMessage in a name comes back verbatim from a message")
    else:
        bad("markup through a message", reply)
    reply = rcon.run(f"wo -sandbox create {hostile}")
    if hostile in reply:
        ok("and from an argument type's refusal")
    else:
        bad("markup through an argument type", reply)


def drill_deployed_file():
    print("\nthe deployed file wins, key by key")
    original = deploy(lambda text: text.replace(PROBE_TEMPLATE, PROBE_TEMPLATE.replace(
        "does not exist", "is nowhere to be found")))
    try:
        reload_and_expect("an edited value replaces the bundled one", "is nowhere to be found")
    finally:
        restore(original)
    reload_and_expect("and putting it back restores it", "does not exist")

    print("\na key deleted out of it falls back rather than reaching the player")
    original = deploy(lambda text: "\n".join(
        line for line in text.splitlines() if PROBE_TEMPLATE not in line))
    try:
        reload_and_expect("the bundled English still renders", "does not exist", absent=f"<{PROBE_KEY}>")
    finally:
        restore(original)

    print("\nand so does a value that is not a string")
    for shape, written in (("a bare number", "12345"), ("a boolean", "true"), ("a list", "[a, b]")):
        original = deploy(lambda text: text.replace(PROBE_TEMPLATE, written))
        try:
            reload_and_expect(f"{shape} is left to the bundled default", "does not exist", absent=written)
        finally:
            restore(original)

    print("\nbut a value an admin blanked is a value")
    original = deploy(lambda text: text.replace(PROBE_TEMPLATE, '""'))
    try:
        rcon.run("wake reload")
        reply = rcon.run(PROBE)
        # an empty string defines the key, so nothing backfills it: the refusal is silent, not English
        if not reply.strip():
            ok("an empty string sends nothing rather than falling back")
        else:
            bad("blank value", reply)
    finally:
        restore(original)


def drill_unresolvable_tag():
    print("\na tag MiniMessage cannot resolve costs that tag, not the message")
    original = deploy(lambda text: text.replace(
        PROBE_TEMPLATE, '"<alert>Context <click:run_command><context> does not exist"'))
    try:
        rcon.run("wake reload")
        reply = rcon.run(PROBE)
        # MiniMessage is lenient by default: a tag it will not build is left in the text as written
        if "<click:run_command>" in reply and "does not exist" in reply and "<prefix>" not in reply:
            ok("the tag prints as written and the rest of the message renders around it")
        else:
            bad("unresolvable tag", reply)
    finally:
        restore(original)


def drill_legacy_code():
    print("\na legacy § code is the one thing that costs the whole message")
    log = Log()
    original = deploy(lambda text: text.replace(
        PROBE_TEMPLATE, '"<alert>Context §cdoes not exist"'))
    try:
        rcon.run("wake reload")
        reply = rcon.run(PROBE)
        # the fallback is the template verbatim, so the template tag it leads with is still a tag here
        if "<alert>" in reply:
            ok("it arrives as plain text, tags and all")
        else:
            bad("legacy code", reply)
        if log.await_line(f"Malformed message template '{PROBE_KEY}'", 5):
            ok("and the console names the key to go and fix")
        else:
            bad("legacy code", "no warning in the log")
        if "Reloaded configuration" in rcon.run("wake reload"):
            ok("every other message still renders")
        else:
            bad("legacy code", "a sibling message broke too")
    finally:
        restore(original)


def drill_broken_file():
    print("\na file that is not YAML at all")
    original = deploy(lambda text: text + "\nthis: is: not: yaml\n\t- nor is this\n")
    try:
        reload_and_expect("every message falls back to the bundled file", "does not exist")
    finally:
        restore(original)


def drill_unreadable_file():
    print("\na file the server can see but not open")
    log = Log()
    kept = DEPLOYED.with_suffix(".yml.probe")
    DEPLOYED.rename(kept)
    DEPLOYED.mkdir()
    try:
        reload_and_expect("every message falls back to the bundled file", "does not exist")
        # the config layer logs a file it cannot parse and swallows one it cannot open, so Wake counts
        if "carried no messages" in log.read():
            ok("and the console names it, where the layer below says nothing")
        else:
            bad("unreadable file", "no warning in the log")
    finally:
        DEPLOYED.rmdir()
        kept.rename(DEPLOYED)
        rcon.run("wake reload")


def drill_broken_templates():
    print("\na template the resolver cannot take is dropped by name, and costs no message")
    log = Log()
    # a cycle written with an argument, a name no tag could carry, and one the palette already holds
    # -- the last one written to lead back to `alert`, which is a cycle only if it were ever a template
    extra = "  loop: \"<loop:'x'>\"\n  bad-name: \"x\"\n  danger: \"<alert>\"\n"
    original = deploy(lambda text: text.replace('  prefix: "', extra + '  prefix: "'))
    try:
        rcon.run("wake reload")
        for name, why in (("loop", "a cycle"), ("bad-name", "a name no tag could carry"),
                          ("danger", "a name the palette already answers to")):
            if log.await_line(f"Skipping template '{name}'", 5):
                ok(f"{why} is named on the console")
            else:
                bad(f"template {name}", "no warning in the log")
        # the probe leads with <alert>, so its colour off the wire says both that the palette answered
        # <danger> rather than the entry under it, and that <alert> was not dropped for leading there
        if palette()["danger"].upper() in colours(rcon.raw(PROBE)):
            ok("and the message leading with <alert> still renders in the palette's colour")
        else:
            bad("template on a taken name", sorted(colours(rcon.raw(PROBE))))
    finally:
        restore(original)


def drill_palette_override():
    print("\nthe colour an admin spells is the colour that renders")
    default = palette()
    original = deploy(lambda text: recolour(recolour(text, "accent", '"#123456"'), "secondary", '"#654321"'))
    try:
        rcon.run("wake reload")
        seen = colours(rcon.raw(PROBE))
        if {"123456", "654321"} <= seen and not {default["accent"].upper(), default["secondary"].upper()} & seen:
            ok("the <tag> in the message and the $var in the prefix both reach the client recoloured")
        else:
            bad("palette override", sorted(seen))

        # not a hex code at all, and the two Adventure's lenient parser would have truncated to a real colour
        for spelled in ('"not a colour"', '"#12345678"', '"#-1"'):
            log = Log()
            DEPLOYED.write_text(recolour(original, "accent", spelled), encoding="utf-8")
            rcon.run("wake reload")
            reply = rcon.run(PROBE)
            if default["accent"].upper() in colours(rcon.raw(PROBE)) and "does not exist" in reply:
                ok(f"{spelled} costs neither the tag nor the message: it is the built-in colour again")
            else:
                bad(f"malformed colour {spelled}", reply)
            if log.await_line(f"Malformed color '{COLOUR_KEY}accent'", 5):
                ok("and the console names the entry to go and fix")
            else:
                bad(f"malformed colour {spelled}", "no warning in the log")
    finally:
        restore(original)


def drill_unknown_variable():
    print("\nan unknown $token is left as the admin wrote it")
    original = deploy(lambda text: text.replace(
        PROBE_TEMPLATE, '"<alert>Context $notacolour does not exist"'))
    try:
        reload_and_expect("it survives to the client verbatim", "$notacolour")
    finally:
        restore(original)


def drill_unknown_language():
    print("\na language that is not there")
    log = Log()
    original = CONFIG.read_text(encoding="utf-8")
    CONFIG.write_text(re.sub(r"^language:.*$", "language: xx_xx", original, count=1, flags=re.M), encoding="utf-8")
    try:
        reload_and_expect("messages fall back to English", "does not exist")
        if "lang/xx_xx.yml not found" in log.read():
            ok("and the warning names the file it looked for")
        else:
            bad("unknown language", "no warning in the log")
    finally:
        CONFIG.write_text(original, encoding="utf-8")
        rcon.run("wake reload")


def main():
    global rcon
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="wake-dev")
    args = parser.parse_args()

    lang, comments = read_yaml(LANG)
    config_keys, _ = read_yaml(BUNDLED_CONFIG)
    exact, prefixes, halves = source_keys({key.split(".")[0] for key in lang}, config_keys)
    drill_keys(lang, exact, prefixes, halves)
    drill_palette(lang, comments)
    drill_templates(messages(lang), comments)
    drill_placeholders(messages(lang), comments)
    drill_hints(messages(lang))

    try:
        rcon = Rcon(args.host, args.port, args.password)
    except OSError as error:
        raise SystemExit(f"cannot reach RCON at {args.host}:{args.port} ({error}). "
                         f"Start ./gradlew runServer first.")
    if not DEPLOYED.is_file():
        raise SystemExit(f"{DEPLOYED} is missing -- the server writes it on first boot.")

    drill_rendering()
    drill_built_keys()
    drill_untrusted_value()
    drill_deployed_file()
    drill_unresolvable_tag()
    drill_legacy_code()
    drill_broken_file()
    drill_unreadable_file()
    drill_broken_templates()
    drill_palette_override()
    drill_unknown_variable()
    drill_unknown_language()

    print()
    if failures:
        print(f"{len(failures)} check(s) failed: {', '.join(failures)}")
        return 1
    print("all text drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
