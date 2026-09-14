package forge.screens.workshop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;

import javax.imageio.ImageIO;

import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.gui.card.CardScriptInfo;
import forge.gui.card.CardScriptProbe;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.ZipUtil;

/**
 * The file-system half of the Workshop's custom-card features. Pure IO over plain paths: no Swing,
 * no singletons, no card database, so every leg where a silent failure would otherwise be
 * invisible can be exercised under test over a temp directory.
 */
public final class WorkshopFiles {
    private WorkshopFiles() {
    }

    /**
     * The edition the Workshop files user-supplied pictures of stock cards under. A printing there
     * carries the picture and nothing else: the card's rules stay the stock ones, so it is not a
     * custom card, and the stock printings and their downloaded scans are left alone.
     */
    public static final String ART_EDITION_CODE = CardEdition.WORKSHOP_ART_CODE;
    public static final String ART_EDITION_NAME = "Workshop Art";
    /** Its file under {@code custom/editions}; the reader keys editions by their Code, so the file name is free. */
    public static final String ART_EDITION_FILE = ART_EDITION_NAME + ".txt";
    /**
     * Dated before Alpha as belt and braces only. What keeps a Workshop printing out of the art
     * preference's pick is CardDb, which leaves {@link CardEdition#WORKSHOP_ART_CODE} out of a set-less
     * lookup whenever another edition is accepted and out of the unique-by-name index unless it is the
     * card's only print; the printing is chosen per deck slot. The date alone would not do it:
     * ORIGINAL_ART_ALL_EDITIONS takes the oldest date, and LATEST_ART_ALL_EDITIONS walks to the first
     * printing with a picture, which this one always has.
     */
    static final String ART_EDITION_DATE = "1993-01-01";
    /** The rarity letters the edition reader's card pattern accepts; any other letter would be read as part of the name. */
    private static final String CARD_RARITY_CODES = "SCURML";

    /** What {@link #appendArtVariant} filed: the printing's collector number and its art index within the edition. */
    public record ArtVariant(int collectorNumber, int artIndex) {
    }

    /** The script a brand-new card starts from; the shape of the shipped {@code g/grizzly_bears.txt}. */
    public static String template(final String name) {
        return "Name:" + name + "\nManaCost:1 G\nTypes:Creature\nPT:1/1\nOracle:\n";
    }

    /**
     * Throws unless the file is an image ImageIO can decode. Run BEFORE anything is written on a
     * picture's behalf: an edition entry and a database printing added for a file that turns out not
     * to be an image would be a printing without art.
     */
    public static void requireImage(final File src) throws IOException {
        if (src == null || !src.isFile()) {
            throw new IOException("not a file: " + src);
        }
        if (ImageIO.read(src) == null) {
            throw new IOException("not a readable image: " + src.getName());
        }
    }

    /**
     * Files a new printing of a card in the Workshop Art edition: appends {@code <number> <rarity> <name>}
     * to the {@code [cards]} section of the edition file, writing the file with its {@code [metadata]}
     * first when it does not exist. The number is one past the highest in the file; the art index is
     * one past the entries of this name already there, which is how CardDb numbers a name's duplicate
     * entries when it loads the edition at the next start.
     *
     * @param editionFile {@code custom/editions/Workshop Art.txt}
     * @param cardName    the card's rules name, the name an edition file lists
     * @param rarity      the rarity written for the printing; one the reader's pattern has no letter for
     *                    ({@code Unknown}, {@code Token}) is filed as Special
     * @return the collector number and art index of the new printing
     * @throws IOException when the file cannot be read or written; the file is then left as it was
     */
    public static ArtVariant appendArtVariant(final File editionFile, final String cardName, final CardRarity rarity) throws IOException {
        final List<String> existing = editionFile.isFile() ? Files.readAllLines(editionFile.toPath(), StandardCharsets.UTF_8) : null;
        int highest = 0;
        int sameName = 0;
        if (existing != null) {
            final Map<String, List<String>> sections = FileSection.parseSections(existing);
            final List<String> cards = sections.get(CardEdition.EditionSectionWithCollectorNumbers.CARDS.getName());
            for (final String line : cards == null ? List.<String>of() : cards) {
                final Matcher m = CardEdition.Reader.CARD_PATTERN.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                highest = Math.max(highest, numberOf(m.group(2)));
                if (cardName.equals(m.group(5).trim())) {
                    sameName++;
                }
            }
        }

        final List<String> out = new ArrayList<>();
        if (existing == null) {
            out.add("[metadata]");
            out.add("Code=" + ART_EDITION_CODE);
            out.add("Name=" + ART_EDITION_NAME);
            out.add("Date=" + ART_EDITION_DATE);
            out.add("Type=Custom");
            out.add("");
            out.add("[cards]");
        } else {
            out.addAll(existing);
            if (!lastSectionIsCards(existing)) {
                // an appended line joins the LAST section of the file; a hand-added [tokens] section
                // after [cards] would have taken the printing as a token (the reader merges a repeated header)
                out.add("");
                out.add("[cards]");
            }
        }
        final int number = highest + 1;
        final String line = number + " " + rarityCode(rarity) + " " + cardName;
        final Matcher check = CardEdition.Reader.CARD_PATTERN.matcher(line);
        if (!check.matches() || !cardName.equals(check.group(5))) {
            // the reader takes " @" as the artist and "$" as the start of parameters, and skips a line it
            // cannot parse: a printing filed under such a name would vanish at the next start
            throw new IOException("the edition reader cannot list this name: " + cardName);
        }
        out.add(line);
        writeWhole(editionFile, out);
        return new ArtVariant(number, sameName + 1);
    }

    /** The digits of a collector number ({@code 12a} counts as 12); 0 for none. */
    private static int numberOf(final String collectorNumber) {
        if (collectorNumber == null) {
            return 0;
        }
        final String digits = collectorNumber.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (final NumberFormatException ex) {
            return 0; // longer than an int: not a number the Workshop wrote
        }
    }

    private static boolean lastSectionIsCards(final List<String> lines) {
        String last = null;
        for (final String l : lines) {
            final String line = l.trim();
            if (line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                last = line.substring(1, line.length() - 1);
            }
        }
        // the reader matches section names exactly, so "[Cards]" is not the cards section either
        return CardEdition.EditionSectionWithCollectorNumbers.CARDS.getName().equals(last);
    }

    private static String rarityCode(final CardRarity rarity) {
        final String code = rarity.toString(); // the short name, one letter
        return code.length() == 1 && CARD_RARITY_CODES.contains(code) ? code : CardRarity.Special.toString();
    }

    /** Writes the lines to a .part beside the file and moves it into place, so a failure leaves the old file whole. */
    private static void writeWhole(final File file, final List<String> lines) throws IOException {
        final File parent = file.getParentFile();
        if (parent != null && !FileUtil.ensureDirectoryExists(parent)) {
            throw new IOException("could not create directory " + parent);
        }
        final File part = new File(file.getPath() + ".part");
        try {
            Files.write(part.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part.toPath()); // no-op after a successful move
        }
    }

    /** The file's bytes, or null when there is no such file: what {@link #restore} puts back. */
    public static byte[] snapshot(final File file) throws IOException {
        return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
    }

    /** Puts a file back as {@link #snapshot} found it: these bytes, through a .part like every write here, or no file for null. */
    public static void restore(final File file, final byte[] before) throws IOException {
        if (before == null) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        final File part = new File(file.getPath() + ".part");
        try {
            Files.write(part.toPath(), before);
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part.toPath()); // no-op after a successful move
        }
    }

    /**
     * Moves the picture(s) filed under one key to another key, both extensions, and returns the files
     * at their new place. For the Workshop Art edition's first printing of a card: its key is
     * {@code Name.full} while it is the edition's only printing of that name and {@code Name1.full}
     * once a second is filed (ImageUtil appends the art index when a set holds several), so the file
     * has to follow or the printing reads as having no picture. A missing source is not an error: the
     * printing may never have had art.
     *
     * @throws IOException when a picture already sits under the new key (a leftover of a hand-removed
     *                     entry is not this printing's to overwrite) or a key leaves the picture folder,
     *                     both before anything moves; or when a move fails
     */
    public static List<File> renameArt(final File picsRoot, final String oldKey, final String newKey) throws IOException {
        final List<File> moved = new ArrayList<>();
        if (oldKey == null || newKey == null || oldKey.equals(newKey)) {
            return moved;
        }
        final List<File[]> moves = new ArrayList<>();
        for (final String ext : new String[] { "jpg", "png" }) {
            final File from = pictureFile(picsRoot, oldKey, ext);
            if (!from.isFile()) {
                continue;
            }
            final File to = pictureFile(picsRoot, newKey, ext);
            if (to.exists()) {
                throw new IOException("a picture already exists at " + to);
            }
            moves.add(new File[] { from, to });
        }
        for (final File[] move : moves) {
            Files.move(move[0].toPath(), move[1].toPath());
            moved.add(move[1]);
        }
        return moved;
    }

    /**
     * The existing pictures {@link #installArt} would overwrite or delete for this key (the {@code .full}
     * and {@code .fullborder} twins in both extensions); never the source itself. For the prompt that
     * precedes an install: a hand-placed {@code Name.fullborder.jpg} is exactly a file the sweep removes.
     */
    public static List<File> filesInstallWouldReplace(final File src, final File picsRoot, final String imageKey) throws IOException {
        final List<File> out = new ArrayList<>();
        final File canonicalSrc = src.getCanonicalFile();
        pictureFile(picsRoot, imageKey, "jpg"); // refuses a key outside the picture folder before anything is listed
        for (final String key : new String[] { imageKey, fullborderKey(imageKey) }) {
            for (final String ext : new String[] { "jpg", "png" }) {
                final File f = new File(picsRoot, key + "." + ext);
                if (f.isFile() && !f.getCanonicalFile().equals(canonicalSrc)) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /**
     * The picture file for a key under the picture root, refusing a key that resolves outside it. The
     * key is built from a card name, and ImageUtil.toMWSFilename keeps a backslash, which Windows reads
     * as a path separator: a name holding {@code \..\} (say, a card from another player's pack) would
     * otherwise write, and sweep, files outside the picture cache.
     */
    static File pictureFile(final File picsRoot, final String key, final String ext) throws IOException {
        final File f = new File(picsRoot, key + "." + ext);
        if (!f.getCanonicalPath().startsWith(picsRoot.getCanonicalPath() + File.separator)) {
            throw new IOException("the image key leaves the picture folder: " + key);
        }
        return f;
    }

    /**
     * The fullborder twin of a {@code .full} key. Only the suffix changes: a card name may hold ".full"
     * itself (ImageKeys' own folder preload replaces every ".fullborder" and splits on the first dot, so
     * such a name is unreliable there regardless).
     */
    static String fullborderKey(final String imageKey) {
        return imageKey.endsWith(".full") ? imageKey.substring(0, imageKey.length() - ".full".length()) + ".fullborder" : imageKey;
    }

    /**
     * Copies an image into the picture cache under the exact key Forge will look the card up by.
     *
     * @param src      a jpg/jpeg/png the user picked
     * @param picsRoot the card picture cache root ({@code CACHE_CARD_PICS_DIR})
     * @param imageKey the relative key without extension, e.g. {@code USER/My Card.full}
     * @return the file written
     * @throws IOException when the source is not a readable image, or the copy fails. Never
     *                     swallows: a silent failure here is an art change the user cannot see.
     */
    public static File installArt(final File src, final File picsRoot, final String imageKey) throws IOException {
        requireImage(src);
        final String ext = src.getName().toLowerCase(Locale.ROOT).endsWith(".png") ? "png" : "jpg";
        final File dest = pictureFile(picsRoot, imageKey, ext);
        final File parent = dest.getParentFile();
        if (parent != null && !FileUtil.ensureDirectoryExists(parent)) {
            throw new IOException("could not create directory " + parent);
        }
        final File part = new File(dest.getPath() + ".part");
        try {
            final File canonicalSrc = src.getCanonicalFile();
            final boolean sourceIsDest = canonicalSrc.equals(dest.getCanonicalFile());
            // copy FIRST so a failure leaves every existing picture in place, then clear the stale
            // siblings, then move into place. ImageKeys.findFile probes .jpg before .png, and
            // ImageKeys.hasImage's folder preload files "Name.fullborder.*" under the SAME key as
            // "Name.full.*" (last listed wins), so both twins have to go - but never the source:
            // the user may have picked the card's own cached picture.
            if (!sourceIsDest) {
                Files.copy(src.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            // the sweep runs even when the source already is the destination (nothing to copy): the
            // fullborder twin the prompt named is listed after the .full file, so it would keep serving
            for (final String staleKey : new String[] { imageKey, fullborderKey(imageKey) }) {
                for (final String stale : new String[] { "jpg", "png" }) {
                    final File sibling = new File(picsRoot, staleKey + "." + stale);
                    if (!sibling.equals(dest) && sibling.isFile() && !sibling.getCanonicalFile().equals(canonicalSrc)) {
                        Files.delete(sibling.toPath());
                    }
                }
            }
            if (!sourceIsDest) {
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final InvalidPathException ex) {
            // ImageUtil.toMWSFilename strips only " / : ? from the key; a card name holding * < > (or a control
            // character) is a key the file system refuses. Unchecked, it would escape the caller's IOException handling.
            throw new IOException("the image key is not a legal file name on this system: " + imageKey, ex);
        } finally {
            try {
                Files.deleteIfExists(part.toPath()); // no-op after a successful move
            } catch (final InvalidPathException | IOException ignored) {
                // an illegal key already threw above; a leftover .part must not mask that exception
            }
        }
        return dest;
    }

    // ---------------------------------------------------------------------------------------
    // Export Set

    /** Entry prefix (forward slashes) each root is packed under. */
    public static final String EXPORT_CARDS = "custom/cards";
    public static final String EXPORT_EDITIONS = "custom/editions";
    public static final String EXPORT_TOKENS = "custom/tokens";
    public static final String EXPORT_PICS = "Cache/pics/cards";
    public static final String README_NAME = "README.txt";

    /**
     * The directories an exported set is made of, keyed by the prefix they are packed under. The
     * Workshop Art edition's picture folder is never one of them (see {@link #exportExclusions}), nor
     * is a folder name that is not a single plain path segment: an edition's Code2 names its folder.
     *
     * @param customDir  the user's {@code custom/} dir (holding cards/, editions/, tokens/)
     * @param picsRoot   the card picture cache root
     * @param setFolders the picture sub-folders to include - one per custom edition (plus USER)
     */
    public static LinkedHashMap<String, File> exportRoots(final File customDir, final File picsRoot, final Collection<String> setFolders) {
        final LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        roots.put(EXPORT_CARDS, new File(customDir, "cards"));
        roots.put(EXPORT_EDITIONS, new File(customDir, "editions"));
        roots.put(EXPORT_TOKENS, new File(customDir, "tokens"));
        for (final String folder : new TreeSet<>(setFolders)) {
            if (folder == null || folder.isEmpty() || folder.equals(".") || folder.equals("..")
                    || folder.indexOf('/') >= 0 || folder.indexOf('\\') >= 0 || folder.equalsIgnoreCase(ART_EDITION_CODE)) {
                continue;
            }
            roots.put(EXPORT_PICS + "/" + folder, new File(picsRoot, folder));
        }
        return roots;
    }

    /**
     * What an export leaves out of its roots: the Workshop Art edition file, found by its name or its
     * {@code Code=} line. That edition is each player's own (Add Art Variant files their pictures of
     * stock cards there, under one fixed file name and code), so a copy installed from someone else's
     * pack would replace theirs, and the printings it lists would lose their pictures.
     */
    static List<File> exportExclusions(final Map<String, File> roots) {
        final List<File> out = new ArrayList<>();
        final File editions = roots.get(EXPORT_EDITIONS);
        final File[] files = editions == null ? null : editions.listFiles();
        if (files == null) {
            return out;
        }
        for (final File f : files) {
            if (f.isFile() && (f.getName().equalsIgnoreCase(ART_EDITION_FILE) || declaresWorkshopArtCode(f))) {
                out.add(f);
            }
        }
        return out;
    }

    private static boolean declaresWorkshopArtCode(final File editionFile) {
        try {
            for (final String line : Files.readAllLines(editionFile.toPath(), StandardCharsets.UTF_8)) {
                final int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).trim().equalsIgnoreCase("Code")) {
                    return line.substring(eq + 1).trim().equalsIgnoreCase(ART_EDITION_CODE);
                }
            }
        } catch (final IOException | RuntimeException ex) {
            // unreadable: the reader cannot load it either, so it is packed as it is
        }
        return false;
    }

    /** True when at least one root holds a file the archive would carry (see {@link ZipUtil#packedFiles}). */
    public static boolean hasAnythingToExport(final Map<String, File> roots) {
        final List<File> exclude = exportExclusions(roots);
        for (final File root : roots.values()) {
            try {
                if (!ZipUtil.packedFiles(root, exclude).isEmpty()) {
                    return true;
                }
            } catch (final IOException ex) {
                // unreadable root: nothing exportable in it
            }
        }
        return false;
    }

    /**
     * The packed root a destination sits inside, or null. The walk skips the archive and its
     * {@code .part} already; this turns a destination inside a packed folder into a clear refusal
     * up front, before anything is written.
     */
    public static File rootContaining(final File dest, final Map<String, File> roots) throws IOException {
        final String destPath = dest.getCanonicalPath();
        for (final File root : roots.values()) {
            if (root != null && root.isDirectory() && destPath.startsWith(root.getCanonicalPath() + File.separator)) {
                return root;
            }
        }
        return null;
    }

    /** What went into an export, per root. */
    public static final class ExportReport {
        public final Map<String, Integer> filesPerRoot = new LinkedHashMap<>();

        public int totalFiles() {
            int n = 0;
            for (final int c : filesPerRoot.values()) {
                n += c;
            }
            return n;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            for (final Map.Entry<String, Integer> e : filesPerRoot.entrySet()) {
                if (e.getValue() > 0) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                }
            }
            return sb.toString().trim();
        }
    }

    /**
     * Packs the roots (see {@link #exportRoots}), less {@link #exportExclusions}, and an install README
     * into {@code destZip}. Goes through {@link ZipUtil#zipRoots}, so a failure never leaves a truncated
     * archive behind, and refuses a destination inside one of the roots (see {@link #rootContaining}).
     *
     * @param isStockName whether a stock card goes by a card name (see {@link #isStockCardName}): a packed
     *                    script declaring that name (see {@link #cardName}) replaces the stock card, which
     *                    the README names for whoever installs the pack
     */
    public static ExportReport exportPack(final File destZip, final Map<String, File> roots, final Predicate<String> isStockName) throws IOException {
        final File inside = rootContaining(destZip, roots);
        if (inside != null) {
            throw new IOException("the archive cannot be written inside a folder it packs: " + inside);
        }
        final List<File> exclude = exportExclusions(roots);
        final ExportReport report = new ExportReport();
        for (final Map.Entry<String, File> root : roots.entrySet()) {
            report.filesPerRoot.put(root.getKey(), ZipUtil.packedFiles(root.getValue(), exclude).size());
        }
        final List<String> overrides = new ArrayList<>();
        final File cards = roots.get(EXPORT_CARDS);
        for (final File script : ZipUtil.packedFiles(cards, exclude)) {
            final String name = script.getName().endsWith(".txt") ? cardName(script) : null;
            if (name != null && isStockName.test(name)) {
                final String entry = EXPORT_CARDS + "/" + cards.toPath().relativize(script.toPath()).toString().replace(File.separatorChar, '/');
                overrides.add(name + " (" + entry + ")");
            }
        }
        ZipUtil.zipRoots(destZip, roots, Map.of(README_NAME, readme(overrides)), exclude);
        return report;
    }

    /**
     * The name the card reader files a script's rules under, which is what a custom script replaces a
     * stock card by (its file name plays no part): {@code CardRules.getPreInitName()}, so a split card
     * is "Left // Right". The first {@code Name:} line when the script does not parse that far, and
     * null when it has neither. Called on .txt files only, the only kind the reader loads.
     */
    static String cardName(final File script) {
        final String text;
        try {
            text = new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
        } catch (final IOException | RuntimeException ex) {
            return null;
        }
        final String fileName = script.getName();
        try {
            final String name = CardScriptProbe.parseRules(text, fileName.substring(0, fileName.length() - ".txt".length())).getPreInitName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (final RuntimeException ex) {
            // a split script without its second face: its Name: line below
        }
        for (final String line : CardScriptProbe.scriptLines(text)) {
            if (line.startsWith("Name:")) {
                return line.substring("Name:".length()).trim();
            }
        }
        return null;
    }

    /**
     * Whether a stock card goes by this name, compared the way the card reader keys rules (ignoring
     * case). A stem is shared by names that differ in punctuation ("Fire Ice" has the stem of
     * "Fire // Ice"), so the stock script's own name decides.
     */
    public static boolean isStockCardName(final String name) {
        final String stem = CardScriptInfo.stockStemForName(name);
        final String stock = stem == null ? null : CardScriptInfo.readStockScript(stem);
        if (stock == null) {
            return false;
        }
        try {
            final CardRules rules = CardScriptProbe.parseRules(stock, stem);
            return name.equalsIgnoreCase(rules.getPreInitName());
        } catch (final RuntimeException ex) {
            return true; //a stock script under this name's stem that does not parse: warn rather than stay silent
        }
    }

    /**
     * Plain English on purpose (not a Localizer key): it ships inside the pack to other players,
     * whose Forge language is unknown.
     *
     * @param overrides the pack's custom scripts that replace a stock card, as "Name (entry)"; the
     *                  README lists them with a warning, since installing one changes that card for
     *                  whoever installs the pack
     */
    public static String readme(final List<String> overrides) {
        final List<String> lines = new ArrayList<>(List.of(
                "Forge custom card set",
                "=====================",
                "",
                "This archive was exported from Forge's Workshop. It holds custom card scripts,",
                "custom edition files and card pictures. Every player in a network game needs",
                "this same pack installed, or the custom cards will not load on their side.",
                "",
                "Contents",
                "--------",
                "  custom/cards/        card scripts      -> <user dir>/custom/cards/",
                "  custom/editions/     edition files     -> <user dir>/custom/editions/",
                "  custom/tokens/       token scripts     -> <user dir>/custom/tokens/",
                "  Cache/pics/cards/    card pictures     -> <picture dir>/ (the set folders)",
                "  README.txt           this file",
                "",
                "Where to put it",
                "---------------",
                "Copy the custom/ folder into the Forge user directory, so that it becomes",
                "<user dir>/custom/ (merge it with a custom/ folder that is already there), and",
                "copy the CONTENTS of Cache/pics/cards/ (the set folders inside it) into the card",
                "picture directory. The default locations are:",
                "",
                "  Windows   user dir:     %APPDATA%\\Forge\\",
                "            picture dir:  %LOCALAPPDATA%\\Forge\\Cache\\pics\\cards\\",
                "  macOS     user dir:     ~/Library/Application Support/Forge/",
                "            picture dir:  ~/Library/Caches/Forge/pics/cards/",
                "  Linux     user dir:     ~/.forge/",
                "            picture dir:  ~/.cache/forge/pics/cards/",
                "",
                "The \"Cache\" folder name in this archive is only where Windows keeps the picture",
                "cache; on macOS and Linux the set folders go straight under pics/cards/.",
                "If forge.profile.properties (in the Forge program folder) sets userDir, cacheDir",
                "or cardPicsDir, those directories are used instead: the user dir is userDir,",
                "and the picture dir is cardPicsDir, else <cacheDir>/pics/cards/.",
                "",
                "Restart Forge after copying. Custom cards appear in the deck editor and the",
                "Workshop; set-less custom cards are filed under the USER edition.",
                "",
                "Not included: art for printings that belong to a stock (non-custom) set, such",
                "as a custom override of a stock card. That art lives in the stock set's folder",
                "next to downloaded pictures and is left for each player to supply. The",
                "exporter's Workshop Art edition (custom/editions/Workshop Art.txt and its",
                "pictures) is not included either: it holds each player's own art variants,",
                "and installing someone else's would replace yours.",
                ""));
        if (!overrides.isEmpty()) {
            lines.addAll(List.of(
                    "Changed stock cards",
                    "-------------------",
                    "WARNING: these scripts replace the stock card of the same name. Once they are",
                    "installed, that card plays by this pack's rules in every game, against the AI",
                    "and online, until the file is removed again:",
                    ""));
            for (final String override : overrides) {
                lines.add("  " + override);
            }
            lines.add("");
        }
        lines.addAll(List.of(
                "Card pictures",
                "-------------",
                "The pictures in this pack are whatever the person who exported it chose. Only",
                "share a pack whose pictures you have the right to share.",
                ""));
        return String.join("\n", lines);
    }
}
