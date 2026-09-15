/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.gui.card;

import forge.localinstance.properties.ForgeConstants;
import forge.util.BuildInfo;
import forge.util.FileUtil;
import forge.util.TextUtil;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The script text behind one card in the Workshop, together with where it came from and
 * where a save goes.
 * <p>
 * Lookup order matches the order the card database loads scripts (user custom files first,
 * then the loose {@code res/cardsfolder} tree, then {@code cardsfolder.zip}), so the pane
 * always shows the script the game is actually running.
 */
public final class CardScriptInfo {
    /** Where a script was found, which decides what a save does to it. */
    public enum Source {
        /** Packed in {@code res/cardsfolder/cardsfolder.zip}; a save writes a custom override. */
        STOCK_ZIP,
        /** A loose file under {@code res/cardsfolder}; edited in place. */
        STOCK_FILE,
        /** A user file that shadows a stock card of the same file stem. */
        CUSTOM_OVERRIDE,
        /** A user file with no stock counterpart. */
        CUSTOM_CARD
    }

    private static final String UPCOMING = "upcoming";
    private static final String REBALANCED = "rebalanced";
    /** The stock sub-folders that are not a first-letter folder (the card reader loads every folder). */
    private static final String[] EXTRA_STOCK_FOLDERS = { UPCOMING, REBALANCED };
    private static final String EXT = ".txt";

    private String text;
    private final File file;
    private final String stem;
    private Source source;
    private String lastError;

    public CardScriptInfo(final String text0, final File file0, final String stem0, final Source source0) {
        text = text0;
        file = file0;
        stem = stem0;
        source = source0;
    }

    public String getText() {
        return text;
    }

    /** The file a save writes to. For {@link Source#STOCK_ZIP} this is the override file that does not exist yet. */
    public File getFile() {
        return file;
    }

    public String getStem() {
        return stem;
    }

    public Source getSource() {
        return source;
    }

    /** The last write or delete failure, as text for a dialog; null when the last operation succeeded. */
    public String getLastError() {
        return lastError;
    }

    public boolean canEdit() {
        return file != null;
    }

    /**
     * Writes the script to {@link #getFile()} in UTF-8 (the charset every reader uses), creating
     * the directory on first use. Returns false and records {@link #getLastError()} on failure.
     */
    public boolean trySetText(final String text0) {
        if (file == null) {
            lastError = "no file";
            return false;
        }
        lastError = null;
        try {
            final File parent = file.getParentFile();
            if (parent != null && !FileUtil.ensureDirectoryExists(parent)) {
                throw new IOException("could not create directory " + parent);
            }
            try (PrintWriter p = new PrintWriter(file, StandardCharsets.UTF_8)) {
                p.print(text0);
                if (!text0.endsWith("\n")) {
                    p.print("\n");
                }
            }
            text = text0;
            if (source == Source.STOCK_ZIP) {
                source = Source.CUSTOM_OVERRIDE;
            }
            return true;
        } catch (final IOException | SecurityException ex) {
            lastError = ex.toString();
            System.err.println("Problem writing file - " + file);
            ex.printStackTrace();
            return false;
        }
    }

    /** Deletes {@link #getFile()}. Returns false and records {@link #getLastError()} on failure. */
    public boolean deleteFile() {
        if (file == null) {
            lastError = "no file";
            return false;
        }
        lastError = null;
        try {
            Files.delete(file.toPath());
            return true;
        } catch (final IOException | SecurityException ex) {
            lastError = ex.toString();
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------
    // static lookup

    private static final Map<String, CardScriptInfo> allScripts = new ConcurrentHashMap<>();

    private static ZipFile stockZip;
    private static boolean stockZipTried;
    private static String zipProblem;
    /** Zip scripts filed under a folder that is not their first character, by stem; built once when the zip is opened. */
    private static Map<String, String> oddZipEntries;

    /**
     * The file stem Forge uses for a card script: accents transliterated, lower-cased, punctuation
     * stripped, spaces and hyphens to underscores. The stock stems are {@code dandan.txt},
     * {@code jotun_grunt.txt} and {@code lim_duls_vault.txt}, so the accent is folded to its base
     * letter (as {@code PaperCard.getImageKey} and {@code CardDb} do), never dropped.
     * May be empty for a name made entirely of punctuation; callers must check.
     */
    public static String toFileStem(final String name) {
        if (name == null) {
            return "";
        }
        return StringUtils.stripAccents(name).toLowerCase(Locale.ROOT)
                .replaceAll("[^-a-z0-9_\\s]", "").replaceAll("[-\\s]", "_").replaceAll("_+", "_");
    }

    /** {@code USER_CUSTOM_CARDS_DIR/<first letter>/<stem>.txt}. */
    public static File overrideFileFor(final String stem) {
        return new File(new File(ForgeConstants.USER_CUSTOM_CARDS_DIR, String.valueOf(stem.charAt(0))), stem + EXT);
    }

    /** An unsaved custom-card target for a stem that has no script yet (New Card, rename). */
    public static CardScriptInfo customTargetFor(final String stem) {
        return new CardScriptInfo("", overrideFileFor(stem), stem, Source.CUSTOM_CARD);
    }

    /** Why the stock zip could not be opened, or null when it could (or does not exist and loose files serve). */
    public static String getZipProblem() {
        openStockZip();
        return zipProblem;
    }

    public static void register(final String stem, final CardScriptInfo info) {
        if (stem != null && info != null) {
            allScripts.put(stem, info);
        }
    }

    /**
     * Finds the script for a card by its file stem (the card's normalized name). Never throws;
     * null when no script exists anywhere.
     */
    public static CardScriptInfo getScriptFor(final String stem) {
        if (stem == null || stem.isEmpty()) {
            return null;
        }
        CardScriptInfo script = allScripts.get(stem);
        if (script != null) {
            return script;
        }
        try {
            script = locate(stem);
        } catch (final RuntimeException ex) {
            System.err.println("Problem locating script for " + stem + ": " + ex);
            script = null;
        }
        if (script != null) {
            allScripts.put(stem, script);
        }
        return script;
    }

    /**
     * As {@link #getScriptFor(String)}, but a custom file is only taken for the card whose {@code Name:}
     * it carries. A hand-renamed file under a stock stem ({@code custom/cards/g/grizzly_bears.txt} holding
     * {@code Name:Grizzly Bearz}) loads as a second card, so the stock Grizzly Bears selected in the catalog
     * must not show that file or save over it: it gets its stock text, read-only ({@link #canEdit()} false).
     */
    public static CardScriptInfo getScriptFor(final String stem, final String cardName) {
        final CardScriptInfo script = getScriptFor(stem);
        if (script == null || cardName == null || script.getSource() == Source.STOCK_ZIP || script.getSource() == Source.STOCK_FILE
                || scriptNamesCard(script.getText(), cardName)) {
            return script;
        }
        final File stockFile = looseStockFileFor(stem);
        if (stockFile != null) {
            return new CardScriptInfo(FileUtil.readFileToString(stockFile), null, stem, Source.STOCK_FILE);
        }
        final String zipText = readZipScript(stem);
        return zipText == null ? null : new CardScriptInfo(zipText, null, stem, Source.STOCK_ZIP);
    }

    /** True when the script's first {@code Name:} line is this card (a split card's name is "Left // Right"; the line holds Left). */
    static boolean scriptNamesCard(final String text, final String cardName) {
        for (final String line : text.split("\r?\n")) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("Name:")) {
                final String name = trimmed.substring("Name:".length()).trim();
                return cardName.equals(name) || cardName.startsWith(name + " // ");
            }
        }
        return true; // no Name: line: nothing to contradict
    }

    private static CardScriptInfo locate(final String stem) {
        final String filename = stem + EXT;
        final char letter = stem.charAt(0);

        // 1-2. user custom files shadow everything else, exactly as at load time; the reader walks every
        //      sub-folder, so a script anywhere under custom/cards (a set kept in its own folder) is a loaded card too
        File customFile = null;
        for (final File file : new File[] {
                new File(new File(ForgeConstants.USER_CUSTOM_CARDS_DIR, String.valueOf(letter)), filename),
                new File(ForgeConstants.USER_CUSTOM_CARDS_DIR, filename) }) {
            if (file.isFile()) {
                customFile = file;
                break;
            }
        }
        if (customFile == null) {
            customFile = findCustomFile(new File(ForgeConstants.USER_CUSTOM_CARDS_DIR), filename);
        }
        if (customFile != null) {
            final Source source = readStockScript(stem) != null ? Source.CUSTOM_OVERRIDE : Source.CUSTOM_CARD;
            return new CardScriptInfo(FileUtil.readFileToString(customFile), customFile, stem, source);
        }

        // 3. loose stock files (first-letter, upcoming, rebalanced) are edited in place
        final File stockFile = looseStockFileFor(stem);
        if (stockFile != null) {
            return new CardScriptInfo(FileUtil.readFileToString(stockFile), stockFile, stem, Source.STOCK_FILE);
        }

        // 4. packed stock script (same folders); a save goes to the override file
        final String zipText = readZipScript(stem);
        if (zipText != null) {
            return new CardScriptInfo(zipText, overrideFileFor(stem), stem, Source.STOCK_ZIP);
        }
        return null;
    }

    /**
     * Depth-first search of a custom cards tree for a file name, walking it the way
     * {@code CardStorageReader.collectCardFiles} does: every sub-folder, skipping dot-folders and
     * (outside a development build) {@code upcoming/}. Null when no such file is loaded from it.
     */
    public static File findCustomFile(final File dir, final String filename) {
        final File[] children = dir == null ? null : dir.listFiles();
        if (children == null) {
            return null;
        }
        for (final File child : children) {
            if (child.isDirectory()) {
                final String folder = child.getName();
                if (folder.startsWith(".") || (folder.equalsIgnoreCase(UPCOMING) && !BuildInfo.isDevelopmentVersion())) {
                    continue;
                }
                final File found = findCustomFile(child, filename);
                if (found != null) {
                    return found;
                }
            } else if (child.getName().equals(filename)) {
                return child;
            }
        }
        return null;
    }

    /** The stock folders a script for this stem could sit in, first-letter folder first. */
    private static String[] stockFoldersFor(final String stem) {
        final String[] folders = new String[EXTRA_STOCK_FOLDERS.length + 1];
        folders[0] = String.valueOf(stem.charAt(0));
        System.arraycopy(EXTRA_STOCK_FOLDERS, 0, folders, 1, EXTRA_STOCK_FOLDERS.length);
        return folders;
    }

    /** The loose {@code res/cardsfolder} file for a stem, or null when there is none (a zip-packed install). */
    private static File looseStockFileFor(final String stem) {
        for (final String folder : stockFoldersFor(stem)) {
            final File file = new File(new File(ForgeConstants.CARD_DATA_DIR, folder), stem + EXT);
            if (file.isFile()) {
                return file;
            }
        }
        // a few stock scripts sit in a folder that is not their first character (p/+2_mace.txt,
        // n/1996_world_champion.txt); the reader loads every folder, so look in each before giving up
        final String[] folders = new File(ForgeConstants.CARD_DATA_DIR).list();
        if (folders != null) {
            for (final String folder : folders) {
                final File file = new File(new File(ForgeConstants.CARD_DATA_DIR, folder), stem + EXT);
                if (file.isFile()) {
                    return file;
                }
            }
        }
        return null;
    }

    /**
     * The stock script text for a stem (loose file first, then the zip), or null when there is none.
     * Used to classify a custom file as an override and to restore a stock card.
     */
    public static String readStockScript(final String stem) {
        if (stem == null || stem.isEmpty()) {
            return null;
        }
        final File stockFile = looseStockFileFor(stem);
        if (stockFile != null) {
            return FileUtil.readFileToString(stockFile);
        }
        return readZipScript(stem);
    }

    /** The zip entry name a stock script for this stem is stored under, or null when the zip has none. */
    public static String stockZipEntryFor(final String stem) {
        final ZipFile zip = openStockZip();
        if (zip == null || stem == null || stem.isEmpty()) {
            return null;
        }
        for (final String folder : stockFoldersFor(stem)) {
            final String name = folder + "/" + stem + EXT;
            if (zip.getEntry(name) != null) {
                return name;
            }
        }
        // same odd-folder scripts as looseStockFileFor: the reader loads every entry whatever its folder
        return oddZipEntries == null ? null : oddZipEntries.get(stem);
    }

    /**
     * The scripts the first-letter probe cannot find: a few stock scripts sit in a folder that is not
     * their first character (p/+2_mace.txt, n/1996_world_champion.txt), and the reader loads every
     * entry whatever its folder. One walk of the archive when it is opened; a lookup is then a map read,
     * so a stem with no script anywhere (every click on such a card) never enumerates the archive.
     */
    private static Map<String, String> indexOddEntries(final ZipFile zip) {
        final Map<String, String> odd = new HashMap<>();
        for (final Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
            final String entry = en.nextElement().getName();
            final int slash = entry.indexOf('/');
            if (slash <= 0 || !entry.endsWith(EXT) || entry.indexOf('/', slash + 1) >= 0) {
                continue; // no folder, not a script, or nested deeper than the one level the reader files scripts at
            }
            final String stem = entry.substring(slash + 1, entry.length() - EXT.length());
            if (!stem.isEmpty() && !entry.startsWith(stem.charAt(0) + "/")) {
                odd.putIfAbsent(stem, entry);
            }
        }
        return odd;
    }

    /** Test hook: serves stock scripts from this zip (null: probe the real one again) and forgets every cached script. */
    static synchronized void useStockZipForTests(final File zip) throws IOException {
        if (stockZip != null) {
            stockZip.close();
        }
        stockZip = zip == null ? null : new ZipFile(zip);
        oddZipEntries = stockZip == null ? null : indexOddEntries(stockZip);
        stockZipTried = zip != null;
        zipProblem = null;
        allScripts.clear();
    }

    private static String readZipScript(final String stem) {
        final String entryName = stockZipEntryFor(stem);
        if (entryName == null) {
            return null;
        }
        final ZipFile zip = openStockZip();
        final ZipEntry entry = zip.getEntry(entryName);
        try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
            // same shape as FileUtil.readFileToString so the dirty check compares like with like
            return TextUtil.join(FileUtil.readAllLines(reader, false), "\n");
        } catch (final IOException | RuntimeException ex) {
            zipProblem = ex.toString();
            System.err.println("Problem reading " + entryName + " from cardsfolder.zip: " + ex);
            return null;
        }
    }

    private static synchronized ZipFile openStockZip() {
        if (stockZipTried) {
            return stockZip;
        }
        stockZipTried = true;
        final File zipFile = new File(ForgeConstants.CARD_DATA_DIR, "cardsfolder.zip");
        if (!zipFile.isFile()) {
            // a source checkout serves loose files; that is not a problem
            return null;
        }
        try {
            stockZip = new ZipFile(zipFile); // kept open for the life of the process, like CardStorageReader's
            oddZipEntries = indexOddEntries(stockZip);
        } catch (final IOException ex) {
            zipProblem = ex.toString();
            System.err.println("Problem opening " + zipFile + ": " + ex);
        }
        return stockZip;
    }
}
