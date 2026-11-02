package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Temas guardados en la tele para cambiarlos con el control, sin PC.
 * Basado en el selector de abhishek00235 (fork codex/on-device-theme-picker de cleanyourtv), con estos cambios:
 * lee cualquier carpeta de files/theme-packs/ (no una lista fija), el nombre visible sale de la clave "name"
 * del config.json del tema (o del nombre de la carpeta), y el orden de la clave "order".
 * Subir un tema: adb push <tema>/ /sdcard/Android/data/com.gathin.tvhome/files/theme-packs/<id>/
 */
final class ThemeCatalog {
    static final String PACKS = "theme-packs";
    static final String ACTIVE_KEY = "active_theme";

    static final class Theme {
        final String id;
        final String label;
        final int order;
        Theme(String id, String label, int order) {
            this.id = id;
            this.label = label;
            this.order = order;
        }
    }

    private ThemeCatalog() {}

    static List<Theme> installed(Context context) {
        List<Theme> result = new ArrayList<>();
        File root = Config.dir(context);
        File[] folders = root == null ? null : new File(root, PACKS).listFiles(File::isDirectory);
        if (folders == null) return result;
        for (File folder : folders) {
            File config = new File(folder, Config.FILE_NAME);
            if (!config.isFile() || config.length() == 0) continue;
            String id = folder.getName();
            String label = prettify(id);
            int order = 1000;
            try {
                JSONObject json = new JSONObject(read(config));
                String name = json.optString("name", "").trim();
                if (!name.isEmpty()) label = name;
                order = json.optInt("order", order);
            } catch (Exception ignored) {
                // Un config.json roto no se ofrece: Config lo ignoraría al aplicarlo.
                continue;
            }
            result.add(new Theme(id, label, order));
        }
        Collections.sort(result, (a, b) -> a.order != b.order ? Integer.compare(a.order, b.order)
                : a.label.compareToIgnoreCase(b.label));
        return result;
    }

    static String prettify(String id) {
        StringBuilder label = new StringBuilder();
        for (String word : id.split("[-_]")) {
            if (word.isEmpty()) continue;
            if (label.length() > 0) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.length() == 0 ? id : label.toString();
    }

    /** El tema activo: el que tiene el mismo config.json que el activo; si ninguno coincide, -1. */
    static int currentIndex(Context context, List<Theme> themes) {
        File root = Config.dir(context);
        File active = root == null ? null : new File(root, Config.FILE_NAME);
        if (active == null || !active.isFile()) return -1;
        for (int i = 0; i < themes.size(); i++) {
            if (sameContents(active, packFile(root, themes.get(i).id, Config.FILE_NAME))) return i;
        }
        return -1;
    }

    static String currentLabel(Context context) {
        List<Theme> themes = installed(context);
        int i = currentIndex(context, themes);
        return i < 0 ? null : themes.get(i).label;
    }

    static void apply(Context context, String id) throws IOException {
        File root = Config.dir(context);
        if (root == null) throw new IOException("Sin almacenamiento");
        File pack = new File(new File(root, PACKS), id);
        if (!new File(pack, Config.FILE_NAME).isFile()) throw new IOException("Tema no instalado: " + id);

        copy(new File(pack, Config.FILE_NAME), new File(root, Config.FILE_NAME));
        copyOptional(new File(pack, Config.WALLPAPER_NAME), new File(root, Config.WALLPAPER_NAME));

        File activeWallpapers = new File(root, ThemeFiles.WALLPAPERS);
        if (!activeWallpapers.exists() && !activeWallpapers.mkdirs()) throw new IOException("No se pudo crear wallpapers");
        File[] existing = activeWallpapers.listFiles();
        if (existing != null) for (File file : existing) {
            if (isThemeImage(file.getName()) && !file.delete()) throw new IOException("No se pudo borrar " + file);
        }
        File[] images = new File(pack, ThemeFiles.WALLPAPERS).listFiles();
        if (images != null) for (File file : images) {
            if (isThemeImage(file.getName())) copy(file, new File(activeWallpapers, file.getName()));
        }

        // Lo que se haya cambiado a mano en Ajustes del launcher se suelta, para que el tema se vea como es.
        SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                .remove(Config.DOCK_OPACITY_KEY)
                .putString(ACTIVE_KEY, id)
                .putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1)
                .commit();
    }

    private static boolean isThemeImage(String name) {
        return ThemeFiles.collectionImage(name) || WallpaperCaptions.FILE_NAME.equals(name);
    }

    private static File packFile(File root, String id, String name) {
        return new File(new File(new File(root, PACKS), id), name);
    }

    private static boolean sameContents(File left, File right) {
        if (!right.isFile() || left.length() != right.length()) return false;
        try (InputStream a = new FileInputStream(left); InputStream b = new FileInputStream(right)) {
            byte[] x = new byte[4096];
            byte[] y = new byte[4096];
            while (true) {
                int nx = readFully(a, x), ny = readFully(b, y);
                if (nx != ny) return false;
                if (nx <= 0) return true;
                for (int i = 0; i < nx; i++) if (x[i] != y[i]) return false;
            }
        } catch (IOException ignored) {
            return false;
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static String read(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int n = readFully(in, data);
            return new String(data, 0, n, StandardCharsets.UTF_8);
        }
    }

    private static void copyOptional(File source, File target) throws IOException {
        if (source.isFile()) copy(source, target);
        else if (target.exists() && !target.delete()) throw new IOException("No se pudo quitar el fondo anterior");
    }

    private static void copy(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("No se pudo crear " + parent);
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.getFD().sync();
        }
    }
}
