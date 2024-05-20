package redstore

import java.io.File
import java.nio.file.Path
import java.util.UUID

fun calcDirSize(file: File): Long {
    if (!file.isDirectory()) {
        if (file.getName().endsWith(".bin")) {
            return file.length();
        } else {
            return 0;
        }
    }

    var size = 0L;
    for (f in file.listFiles()) {
        size += calcDirSize(f);
    }

    return size;
}

fun calcDirFileCount(file: File): Int {
    if (!file.isDirectory()) {
        if (file.getName().endsWith(".bin")) {
            return 1;
        } else {
            return 0;
        }
    }

    var count = 0;
    for (f in file.listFiles()) {
        count += calcDirFileCount(f);
    }

    return count;
}

fun listDirFiles(name: String, file: File, cb: (name: String, f: File) -> Unit) {
    if (!file.isDirectory()) {
        if (name.endsWith(".bin")) {
            cb(name, file)
        }
        return;
    }

    val dir = when (name) {
        "" -> "";
        else -> "${name}/";
    }

    for (f in file.listFiles()) {
        listDirFiles("${dir}${f.getName()}", f, cb);
    }
}

fun getPlayerBasePath(template: String, playerUUID: UUID): Path {
    return Path.of(template.replace("%uuid%", playerUUID.toString())).normalize();
}

fun calcPlayerSpaceUsage(template: String, playerUUID: UUID): Long {
    return calcDirSize(getPlayerBasePath(template, playerUUID).toFile());
}

fun calcPlayerFileCount(template: String, playerUUID: UUID): Int {
    return calcDirFileCount(getPlayerBasePath(template, playerUUID).toFile());
}

fun listPlayerFiles(
    template: String,
    playerUUID: UUID,
    cb: (name: String, f: File) -> Unit,
) {
    val base = getPlayerBasePath(template, playerUUID);
    return listDirFiles("", base.toFile(), cb);
}

fun deletePlayerFile(
    template: String,
    playerUUID: UUID,
    name: String,
): Boolean {
    val base = getPlayerBasePath(template, playerUUID);
    val path = base.resolve(name);
    if (!path.startsWith(base)) {
        return false;
    }

    val file = path.toFile();
    if (!file.exists()) {
        return false;
    }

    file.delete();
    return true;
}
