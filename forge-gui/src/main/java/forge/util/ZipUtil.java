package forge.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/*
*  https://www.baeldung.com/java-compress-and-uncompress
*/
public class ZipUtil {
    public static String backupAdvFile = "forge.adv";
    public static String backupClsFile = "forge.cls";
    private static boolean isClassic = false;
    public static void zip(File source, File dest, String name) throws IOException {
        isClassic = backupClsFile.equalsIgnoreCase(name);
        try(
        FileOutputStream fos = new FileOutputStream(dest.getAbsolutePath() + File.separator + name);
        ZipOutputStream zipOut = new ZipOutputStream(fos)) {
            zipFile(source, source.getName(), zipOut);
        }
    }

    /**
     * Write the given files to a flat zip archive at {@code zipFile}. Each entry uses the
     * source file's basename; no directory structure is preserved. Files that don't exist
     * are skipped silently so callers don't have to pre-filter.
     */
    public static void zipFiles(List<File> files, File zipFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zipOut = new ZipOutputStream(fos)) {
            byte[] buffer = new byte[1024];
            for (File file : files) {
                if (file == null || !file.isFile()) continue;
                try (FileInputStream fis = new FileInputStream(file)) {
                    zipOut.putNextEntry(new ZipEntry(file.getName()));
                    int length;
                    while ((length = fis.read(buffer)) >= 0) {
                        zipOut.write(buffer, 0, length);
                    }
                    zipOut.closeEntry();
                }
            }
        }
    }

    /**
     * Write several directory trees into one archive, each under its own top-level prefix,
     * plus any number of generated text entries.
     * <p>
     * {@code roots} maps an entry prefix (forward slashes, e.g. {@code custom/cards}) to the
     * directory whose contents go under it; a root that is not a directory is skipped silently,
     * same contract as {@link #zipFiles}. What each root contributes is exactly
     * {@link #packedFiles}, with {@code exclude} left out. {@code textEntries} maps an entry name
     * to its UTF-8 content. The archive is written to {@code dest + ".part"} and moved into place
     * only once it is complete, so a failure never leaves a truncated zip at {@code dest}.
     */
    public static void zipRoots(File dest, Map<String, File> roots, Map<String, String> textEntries, Collection<File> exclude) throws IOException {
        final File part = new File(dest.getPath() + ".part");
        // never pack the archive into itself: a destination under one of the roots would otherwise have
        // the growing .part streamed into its own entry until the disk was full
        final Set<File> skip = new HashSet<>(exclude);
        skip.add(part);
        skip.add(dest);
        try {
            try (FileOutputStream fos = new FileOutputStream(part);
                 ZipOutputStream zipOut = new ZipOutputStream(fos)) {
                final byte[] buffer = new byte[8192];
                for (Map.Entry<String, File> root : roots.entrySet()) {
                    final File dir = root.getValue();
                    for (File file : packedFiles(dir, skip)) {
                        final String relative = dir.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
                        zipOut.putNextEntry(new ZipEntry(root.getKey() + "/" + relative));
                        try (FileInputStream fis = new FileInputStream(file)) {
                            int length;
                            while ((length = fis.read(buffer)) >= 0) {
                                zipOut.write(buffer, 0, length);
                            }
                        }
                        zipOut.closeEntry();
                    }
                }
                if (textEntries != null) {
                    for (Map.Entry<String, String> text : textEntries.entrySet()) {
                        zipOut.putNextEntry(new ZipEntry(text.getKey()));
                        zipOut.write(text.getValue().getBytes(StandardCharsets.UTF_8));
                        zipOut.closeEntry();
                    }
                }
            }
            try {
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException | Error ex) { // an OutOfMemoryError over a large picture folder must not leave the .part behind either
            Files.deleteIfExists(part.toPath());
            throw ex;
        }
    }

    /**
     * The regular files {@link #zipRoots} packs from one root, in a stable order; empty for a root
     * that is not a directory. Left out: a hidden or dot-named file or folder ({@code File.isHidden}
     * is only the DOS attribute on Windows, and the card reader skips dot-folders), a symbolic link
     * or Windows junction (it would pack files from outside the root, or loop), and every file or
     * folder in {@code exclude}.
     */
    public static List<File> packedFiles(File root, Collection<File> exclude) throws IOException {
        final List<File> out = new ArrayList<>();
        if (root == null || !root.isDirectory()) {
            return out;
        }
        final Set<File> skip = new HashSet<>();
        for (File f : exclude) {
            skip.add(f.getCanonicalFile());
        }
        collectPacked(root, skip, out);
        return out;
    }

    private static void collectPacked(File dir, Set<File> skip, List<File> out) throws IOException {
        final File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children);
        for (File child : children) {
            if (child.isHidden() || child.getName().startsWith(".") || isLink(child) || skip.contains(child.getCanonicalFile())) {
                continue;
            }
            if (child.isDirectory()) {
                collectPacked(child, skip, out);
            } else if (child.isFile()) {
                out.add(child);
            }
        }
    }

    /** True for a symbolic link or a Windows junction, and for an entry whose attributes cannot be read. */
    private static boolean isLink(File f) {
        try {
            final Path p = f.toPath();
            final BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
                return true;
            }
            // a junction reads as "other" (a reparse point), and so does a cloud-storage placeholder file;
            // only the junction resolves to somewhere else (File.getCanonicalPath does not resolve it)
            return attrs.isOther() && !p.toRealPath().equals(p.getParent().toRealPath().resolve(p.getFileName()));
        } catch (IOException | InvalidPathException ex) {
            return true;
        }
    }

    private static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }
        //skip loose files like forge.log, etc
        if (fileToZip.isFile() && isClassic && "Forge".equalsIgnoreCase(fileToZip.getParentFile().getName()))
            return;
        if (fileToZip.isDirectory()) {
            //skip adventure since we don't want to overwrite it and adventure has its own backup method
            if (isClassic && "adventure".equalsIgnoreCase(fileToZip.getName()) && "Forge".equalsIgnoreCase(fileToZip.getParentFile().getName()))
                return;
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }
            File[] children = fileToZip.listFiles();
            if (children != null) {
                for (File childFile : children) {
                    zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
                }
            }
            return;
        }
        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);
        byte[] bytes = new byte[1024];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }

    public static String unzip(File fileZip, File destDir) throws IOException {
        isClassic = backupClsFile.equalsIgnoreCase(fileZip.getName());
        StringBuilder val = new StringBuilder();
        byte[] buffer = new byte[1024];
        ZipInputStream zis = new ZipInputStream(Files.newInputStream(fileZip.toPath()));
        ZipEntry zipEntry = zis.getNextEntry();
        while (zipEntry != null) {
            File newFile = newFile(destDir, zipEntry);
            if (zipEntry.isDirectory()) {
                if (!newFile.isDirectory() && !newFile.mkdirs()) {
                    throw new IOException("Failed to create directory " + newFile);
                }
                if (isClassic && "Forge".equalsIgnoreCase(newFile.getParentFile().getName()))
                    val.append(" * "). append(newFile.getName()).append("\n");
            } else {
                // fix for Windows-created archives
                File parent = newFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory " + parent);
                }

                if (!isClassic)
                    val.append(" * "). append(newFile.getName()).append("\n");
                // write file content
                try(FileOutputStream fos = new FileOutputStream(newFile)) {
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
            zipEntry = zis.getNextEntry();
        }

        zis.closeEntry();
        zis.close();
        return val.toString();
    }

    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }
}
