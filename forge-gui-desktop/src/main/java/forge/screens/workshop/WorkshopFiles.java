package forge.screens.workshop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import forge.util.FileUtil;

/**
 * The file-system half of the Workshop's custom-card features. Pure IO over plain paths: no Swing,
 * no singletons, no card database, so every leg where a silent failure would otherwise be
 * invisible can be exercised under test over a temp directory.
 */
public final class WorkshopFiles {
    private WorkshopFiles() {
    }

    /** The script a brand-new card starts from; the shape of the shipped {@code g/grizzly_bears.txt}. */
    public static String template(final String name) {
        return "Name:" + name + "\nManaCost:1 G\nTypes:Creature\nPT:1/1\nOracle:\n";
    }

    /**
     * The existing pictures {@link #installArt} would overwrite or delete for this key (the {@code .full}
     * and {@code .fullborder} twins in both extensions); never the source itself. For the prompt that
     * precedes an install: a hand-placed {@code Name.fullborder.jpg} is exactly a file the sweep removes.
     */
    public static List<File> filesInstallWouldReplace(final File src, final File picsRoot, final String imageKey) throws IOException {
        final List<File> out = new ArrayList<>();
        final File canonicalSrc = src.getCanonicalFile();
        for (final String key : new String[] { imageKey, imageKey.replace(".full", ".fullborder") }) {
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
        if (src == null || !src.isFile()) {
            throw new IOException("not a file: " + src);
        }
        if (ImageIO.read(src) == null) {
            throw new IOException("not a readable image: " + src.getName());
        }
        final String ext = src.getName().toLowerCase(Locale.ROOT).endsWith(".png") ? "png" : "jpg";
        final File dest = new File(picsRoot, imageKey + "." + ext);
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
            final String fullborderKey = imageKey.replace(".full", ".fullborder");
            for (final String staleKey : new String[] { imageKey, fullborderKey }) {
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
            // ImageUtil.toMWSFilename strips only " / : ? from the key; a card name holding * < > \ (or a control
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
}
